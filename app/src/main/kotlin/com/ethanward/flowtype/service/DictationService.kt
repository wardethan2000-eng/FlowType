package com.ethanward.flowtype.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.Trace
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.LiveChunker
import com.ethanward.flowtype.asr.SpokenCommands
import com.ethanward.flowtype.asr.joinPieces
import com.ethanward.flowtype.asr.ModelStore
import com.ethanward.flowtype.asr.Transcriber
import com.ethanward.flowtype.audio.AudioCapture
import com.ethanward.flowtype.audio.OtherAudio
import com.ethanward.flowtype.audio.SignalStats
import com.ethanward.flowtype.audio.Wav
import com.ethanward.flowtype.cleanup.ApiKeyStore
import com.ethanward.flowtype.cleanup.AppStyle
import com.ethanward.flowtype.cleanup.Cleaner
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.NoteTitler
import com.ethanward.flowtype.cleanup.UsageStore
import com.ethanward.flowtype.dictionary.DictionaryPass
import com.ethanward.flowtype.dictionary.DictionaryStore
import com.ethanward.flowtype.history.HistoryEntry
import com.ethanward.flowtype.history.HistoryStore
import com.ethanward.flowtype.insert.Inserter
import com.ethanward.flowtype.insert.InsertionLog
import com.ethanward.flowtype.insert.InsertionRecord
import com.ethanward.flowtype.insert.InsertionRules
import com.ethanward.flowtype.insert.Outcome
import com.ethanward.flowtype.notes.NotesStore
import com.ethanward.flowtype.notes.VolumeDoublePress
import com.ethanward.flowtype.overlay.ButtonPlacement
import com.ethanward.flowtype.overlay.MicButton
import java.util.concurrent.Executors

/**
 * The dictation service. Its own input method (flagInputMethodEditor) tells it
 * when a text field takes input; the button shows while that field is live and
 * the keyboard window is up. Tap: listen. ✓: decode on the phone, apply the
 * dictionary, clean it up with the AI model when there's a key (falling back
 * to the local text on any problem), and type it at the cursor (PLAN §4.1,
 * §4.4, §4.5, §4.6). ✕: discard.
 *
 * All fields are touched on the main thread, except [transcriber] (asr thread).
 */
class DictationService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val asr = Executors.newSingleThreadExecutor { Thread(it, "flowtype-asr") }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "flowtype-insert") }
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var log: InsertionLog
    private lateinit var dictionary: DictionaryStore
    private lateinit var history: HistoryStore
    private lateinit var keys: ApiKeyStore
    private lateinit var cleaner: Cleaner
    private lateinit var otherAudio: OtherAudio
    private lateinit var notes: NotesStore
    private val titler by lazy { NoteTitler(keys, UsageStore(this)) }
    private val net = Executors.newSingleThreadExecutor { Thread(it, "flowtype-cleanup") }
    private lateinit var windowManager: WindowManager

    private var button: MicButton? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var state = MicButton.State.IDLE

    /** The field that has input now, from onStartInput; null after onFinishInput. */
    private var editor: EditorInfo? = null
    /** Bumped on each new (non-restarting) input: "same field as when recording started". */
    private var session = 0
    private var keyboardTop: Int? = null
    private var fieldBounds: Rect? = null

    private var capture: AudioCapture? = null
    private var dictationSession = -1
    private var dictationField: EditorInfo? = null

    private var transcriber: Transcriber? = null
    /** asr thread only: the live chunker for the dictation in progress. */
    private var chunker: LiveChunker? = null
    @Volatile private var acceptErrors = 0
    @Volatile private var recordingStartedAt = 0L

    private val releaseIdleModel = Runnable { asr.execute { unloadModel("idle") } }
    private val refreshKeyboard = Runnable { refreshKeyboard() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        store = ModelStore(this)
        log = InsertionLog(this)
        dictionary = DictionaryStore(this)
        history = HistoryStore(this) { prefs.historyDays }
        keys = ApiKeyStore(this)
        cleaner = Cleaner(keys, UsageStore(this))
        otherAudio = OtherAudio(this)
        notes = NotesStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        Trace.event("service_connected")
    }

    override fun onCreateInputMethod(): InputMethod = FieldWatcher()

    private inner class FieldWatcher : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            main.post {
                if (!restarting) session++
                editor = attribute
                Trace.event(
                    "start_input", "app" to attribute.packageName,
                    "type" to "0x%x".format(attribute.inputType), "restarting" to restarting,
                )
                preloadModel()
                scheduleKeyboardChecks()
            }
        }

        override fun onFinishInput() {
            super.onFinishInput()
            main.post {
                editor = null
                updateButton()
            }
        }
    }

    // ---- Voice notes: double-press volume up (PLAN §4.11) ----

    private val volumeDouble = VolumeDoublePress()
    /** Volume-up key-ups to swallow, matching key-downs we kept. */
    private var keptVolumeDown = false
    private var noteCapture: AudioCapture? = null
    private var noteOverlay: MicButton? = null

    /** Whether Android is sending us key events (needs the service switched on since the update). */
    val filtersKeys: Boolean
        get() = serviceInfo?.let {
            it.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0 &&
                it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        } == true

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP || !prefs.volumeNotes) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return keptVolumeDown
                if (noteCapture != null) {
                    // Volume up again while taking a note: save it.
                    keptVolumeDown = true
                    main.post { finishNote() }
                    return true
                }
                if (volumeDouble.onDown(event.eventTime) && capture == null && noteOverlay == null) {
                    keptVolumeDown = true
                    // The first press already raised the volume: put it back.
                    getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, 0,
                    )
                    main.post { startNote() }
                    return true
                }
                keptVolumeDown = false
                return false
            }
            KeyEvent.ACTION_UP -> {
                val kept = keptVolumeDown
                keptVolumeDown = false
                return kept
            }
        }
        return false
    }

    /** Starts a voice note: listen with a floating ✕/✓ panel, no text field needed. */
    fun startNote() {
        if (noteCapture != null || noteOverlay != null || capture != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Flowtype and allow the microphone")
            return
        }
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        if (!store.isInstalled(model)) {
            toast("Download a speech model in Flowtype first")
            return
        }
        if (prefs.pauseOtherAudio) otherAudio.pause()
        val overlay = MicButton(this).apply {
            setState(MicButton.State.RECORDING)
            onCancel = { cancelNote() }
            onAccept = { finishNote() }
        }
        val density = resources.displayMetrics.density
        val bounds = windowManager.currentWindowMetrics.bounds
        val p = WindowManager.LayoutParams(
            (MicButton.PANEL_WINDOW_DP * density).toInt(), (MicButton.WINDOW_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (bounds.height() * 0.72).toInt()
        }
        val cap = AudioCapture(onLevel = { level -> main.post { noteOverlay?.setLevel(level) } })
        if (!cap.start()) {
            otherAudio.resume()
            toast("Couldn't open the microphone")
            return
        }
        windowManager.addView(overlay, p)
        noteOverlay = overlay
        noteCapture = cap
        overlay.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast("Taking a note. Press volume up or ✓ when you're done.")
        preloadModel()
        Trace.event("note_started")
    }

    private fun cancelNote() {
        val cap = noteCapture ?: return
        noteCapture = null
        cap.stop()
        otherAudio.resume()
        removeNoteOverlay()
        Trace.event("note_cancelled")
    }

    private fun removeNoteOverlay() {
        noteOverlay?.let { runCatching { windowManager.removeView(it) } }
        noteOverlay = null
    }

    /** Transcribes, cleans up in the "notes" style when cleanup is on, and saves. */
    private fun finishNote() {
        val cap = noteCapture ?: return
        noteCapture = null
        val pcm = cap.stop()
        otherAudio.resume()
        noteOverlay?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        noteOverlay?.setState(MicButton.State.BUSY)
        asr.execute {
            val dict = dictionary.load()
            val pass = DictionaryPass(dict, prefs.soundsLike)
            val local = runCatching {
                pass.apply(SpokenCommands.apply(loadModel().first.decode(Wav.toFloats(pcm)))).text
            }.getOrElse { "" }
            if (local.isBlank()) {
                main.post {
                    removeNoteOverlay()
                    toast("Didn't catch that, so no note was saved")
                }
                return@execute
            }
            val save = { text: String, cleanup: String ->
                // Saved at once with its first words as the title; the AI title follows.
                val note = notes.add(text, local, NoteTitler.fallback(text))
                Trace.event("note_saved", "audioMs" to pcm.size / 16, "chars" to text.length, "cleanup" to cleanup)
                main.post {
                    removeNoteOverlay()
                    toast("Note saved in Flowtype")
                }
                if (prefs.cleanupEnabled && keys.has()) net.execute {
                    val title = titler.title(text, CleanupConfig.byId(prefs.cleanupModel))
                    if (title != null) notes.setTitle(note.id, title)
                    Trace.event("note_titled", "ai" to (title != null), "chars" to (title?.length ?: 0))
                }
            }
            if (prefs.cleanupEnabled && keys.has()) {
                net.execute {
                    val words = dict.words + dict.replacements.map { it.to }.filter { t -> t.any(Char::isUpperCase) }
                    val (text, outcome) = cleanup(local, AppStyle.NOTES, pass, words, null)
                    save(text, outcome)
                }
            } else save(local, "off")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            // A compose bar grows as you type; only its size is read, never the text.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                main.removeCallbacks(refreshKeyboard)
                main.postDelayed(refreshKeyboard, 50)
            }
        }
    }

    override fun onInterrupt() {}

    /** The keyboard's window can appear a moment after onStartInput. */
    private fun scheduleKeyboardChecks() {
        refreshKeyboard()
        main.postDelayed(refreshKeyboard, 300)
        main.postDelayed(refreshKeyboard, 800)
    }

    private fun refreshKeyboard() {
        val ime = runCatching { windows }.getOrDefault(emptyList())
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        keyboardTop = ime?.let { w -> Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }?.top }
        // Where the focused text box is (bounds only), so the button can sit above
        // a chat's compose bar instead of over it.
        fieldBounds = if (keyboardTop == null) null else runCatching {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { n -> Rect().also { n.getBoundsInScreen(it) }.takeIf { !it.isEmpty } }
        }.getOrNull()
        updateButton()
    }

    private fun updateButton() {
        val field = editor
        val show = state != MicButton.State.IDLE ||
            (field != null && keyboardTop != null && !InsertionRules.isPassword(field.inputType))
        if (!show) {
            if (attached) {
                runCatching { windowManager.removeView(button) }
                attached = false
            }
            return
        }
        val b = button ?: MicButton(this).also { v ->
            v.face.setOnTouchListener(DragOrTap())
            v.onCancel = { cancelDictation() }
            v.onAccept = { stopDictation() }
            v.onChip = { onChip() }
            button = v
        }
        val density = resources.displayMetrics.density
        val window = (MicButton.WINDOW_DP * density).toInt()
        val width = when {
            state == MicButton.State.RECORDING -> (MicButton.PANEL_WINDOW_DP * density).toInt()
            state == MicButton.State.IDLE && offer != null -> (MicButton.CHIP_WINDOW_DP * density).toInt()
            else -> window
        }
        val p = params ?: WindowManager.LayoutParams(
            width, window,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.TOP or Gravity.END
            params = it
        }
        // Where it was last dragged to; otherwise just above the keyboard at the
        // right edge. While recording with the keyboard gone, stay put.
        // The listening panel opens leftward from the button's right edge.
        p.width = width
        if (!dragging) {
            // "Follow the keyboard" ignores where the button was once dragged.
            val saved = if (prefs.buttonFollowsKeyboard) null else prefs.buttonPosition(isLandscape())
            if (saved != null) {
                p.x = saved.first
                p.y = saved.second
            } else {
                p.x = 0
                keyboardTop?.let {
                    p.y = ButtonPlacement.aboveKeyboard(
                        it, window, fieldBounds?.top, fieldBounds?.bottom,
                        nearGap = (NEAR_KEYBOARD_DP * density).toInt(), maxBoxHeight = (MAX_BOX_DP * density).toInt(),
                    )
                }
            }
            val bounds = windowManager.currentWindowMetrics.bounds
            val (x, y) = ButtonPlacement.clamp(p.x, p.y, width, window, bounds.width(), bounds.height())
            p.x = x
            p.y = y
        }
        b.setState(state)
        b.setChip(
            when (offer) {
                is Offer.Undo -> "Undo cleanup"
                is Offer.Retry -> "Type it here"
                null -> null
            },
        )
        if (attached) windowManager.updateViewLayout(b, p) else {
            windowManager.addView(b, p)
            attached = true
        }
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private var dragging = false

    /**
     * A tap dictates. Holding for a moment picks the button up; it then follows
     * the finger and stays where it's dropped (per orientation).
     */
    private inner class DragOrTap : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private val pickUp = Runnable {
            dragging = true
            button?.setDragging(true)
            button?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (prefs.buttonFollowsKeyboard) return holdToTalk.onTouch(v, e)
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    main.postDelayed(pickUp, HOLD_TO_DRAG_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging) {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        val (x, y) = ButtonPlacement.dragged(startX, startY, dx, dy)
                        val (cx, cy) = ButtonPlacement.clamp(x, y, p.width, p.height, bounds.width(), bounds.height())
                        p.x = cx
                        p.y = cy
                        windowManager.updateViewLayout(button, p)
                    } else if (!moved && dx * dx + dy * dy > slop * slop) {
                        // Slid off before the hold: neither a tap nor a drag.
                        moved = true
                        main.removeCallbacks(pickUp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(pickUp)
                    if (dragging) {
                        dragging = false
                        button?.setDragging(false)
                        prefs.setButtonPosition(isLandscape(), p.x, p.y)
                        Trace.event("button_moved", "landscape" to isLandscape())
                    } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                }
            }
            return true
        }
    }

    private val holdToTalk = HoldToTalk()

    /**
     * "Follow the keyboard" mode: recording starts the moment the finger lands,
     * so the first word isn't clipped. Let go within [HOLD_TO_TALK_MS] and it was
     * a tap: the ✕/✓ panel stays open as usual. Hold longer and letting go types
     * it, or discards it if the finger slid left past [CANCEL_SLIDE_DP].
     */
    private inner class HoldToTalk : View.OnTouchListener {
        private var downX = 0f
        private var downAt = 0L
        private var active = false
        private var cancelling = false
        private val holding = Runnable { button?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = state == MicButton.State.IDLE && startDictation()
                    if (!active) return true
                    downX = e.rawX
                    downAt = SystemClock.elapsedRealtime()
                    cancelling = false
                    main.postDelayed(holding, HOLD_TO_TALK_MS)
                }
                MotionEvent.ACTION_MOVE -> if (active) {
                    val slidLeft = downX - e.rawX > CANCEL_SLIDE_DP * resources.displayMetrics.density
                    if (slidLeft != cancelling) {
                        cancelling = slidLeft
                        button?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) {
                    active = false
                    main.removeCallbacks(holding)
                    val held = SystemClock.elapsedRealtime() - downAt >= HOLD_TO_TALK_MS
                    when {
                        !held && !cancelling -> v.performClick() // a tap: the panel stays open
                        cancelling || e.actionMasked == MotionEvent.ACTION_CANCEL -> cancelDictation()
                        else -> stopDictation()
                    }
                }
            }
            return true
        }
    }

    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private fun setState(s: MicButton.State) {
        state = s
        updateButton()
    }

    private fun onTap() {
        when (state) {
            MicButton.State.IDLE -> { startDictation() }
            // While listening, the panel's ✕ and ✓ decide; the circle is hidden.
            MicButton.State.RECORDING, MicButton.State.BUSY -> {}
        }
    }

    /** Returns true if it started listening. */
    private fun startDictation(): Boolean {
        val field = editor ?: return false
        if (prefs.testPhraseMode) {
            setState(MicButton.State.BUSY)
            insert(TEST_PHRASE, "test", session, field)
            return false
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Flowtype and allow the microphone")
            return false
        }
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        if (!store.isInstalled(model)) {
            toast("Download a speech model in Flowtype first")
            return false
        }
        // Transcribe while you talk when the pause detector is there (PLAN §4.3).
        val live = prefs.liveChunking && store.isVadInstalled()
        acceptErrors = 0
        recordingStartedAt = SystemClock.elapsedRealtime()
        asr.execute {
            chunker?.release()
            chunker = if (live) runCatching { LiveChunker(store.vadFile()) { loadModel().first.decode(it) } }.getOrNull() else null
        }
        // Pause a video or music first, so the mic hears you and not it.
        if (prefs.pauseOtherAudio) otherAudio.pause()
        val cap = AudioCapture(
            onLevel = { level -> main.post { if (state == MicButton.State.RECORDING) button?.setLevel(level) } },
            onFrame = if (live) { frame ->
                asr.execute {
                    runCatching { chunker?.accept(frame) }.onFailure {
                        if (acceptErrors++ == 0) Trace.warn("live_accept_failed", "error" to it.javaClass.simpleName)
                    }
                }
            } else null,
        )
        if (!cap.start()) {
            otherAudio.resume()
            asr.execute { dropChunker() }
            toast("Couldn't open the microphone")
            return false
        }
        setOffer(null)
        capture = cap
        dictationSession = session
        dictationField = field
        button?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        setState(MicButton.State.RECORDING)
        preloadModel()
        if (cleanupStyle(field) != null) net.execute { cleaner.prewarm() }
        return true
    }

    /** ✕: stop listening and throw the audio away. Nothing is typed. */
    private fun cancelDictation() {
        val cap = capture ?: return
        capture = null
        val audioMs = cap.stop().size / 16
        otherAudio.resume()
        asr.execute { dropChunker() }
        button?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Trace.event("dictation_cancelled", "app" to dictationField?.packageName, "audioMs" to audioMs)
        dictationField = null
        setState(MicButton.State.IDLE)
    }

    /** ✓: stop listening, transcribe, type it at the cursor. */
    private fun stopDictation() {
        val cap = capture ?: return
        capture = null
        val pcm = cap.stop()
        otherAudio.resume()
        button?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        setState(MicButton.State.BUSY)
        val startedSession = dictationSession
        val field = dictationField ?: return setState(MicButton.State.IDLE)
        val stoppedAt = SystemClock.elapsedRealtime()
        asr.execute {
            val samples = Wav.toFloats(pcm)
            val stats = SignalStats.of(samples)
            val dict = dictionary.load()
            val pass = DictionaryPass(dict, prefs.soundsLike)
            val live = chunker
            chunker = null
            val local = runCatching {
                val (t, loadMs) = loadModel()
                val started = SystemClock.elapsedRealtime()
                // Live: only the unfinished tail is left to decode. Otherwise the whole thing.
                val chunked = live?.let { c -> runCatching { c.finish() }.also { c.release() }.getOrNull() }
                val heard = chunked?.let { joinPieces(it.pieces, keepCase = dict.words.toSet()) } ?: t.decode(samples)
                if (chunked != null && live != null) {
                    // Diagnosis of live chunking: where each piece is and how much text it gave;
                    // whether the chunker got every captured sample. Numbers only.
                    Trace.event(
                        "live_pieces",
                        "pieces" to chunked.pieces.joinToString(",") { "${it.span.start / 16}-${it.span.end / 16}ms:${it.text.length}c" },
                        "capturedMs" to samples.size / 16, "fedMs" to live.samplesIn / 16,
                        "wallMs" to stoppedAt - recordingStartedAt, "acceptErrors" to acceptErrors,
                    )
                }
                val decodeMs = SystemClock.elapsedRealtime() - started
                val result = pass.apply(SpokenCommands.apply(heard))
                Trace.event(
                    "dictation", "app" to field.packageName, "model" to t.model.id,
                    "audioMs" to samples.size / 16, "peak" to "%.4f".format(stats.peak),
                    "rms" to "%.4f".format(stats.rms), "zeroPct" to "%.1f".format(stats.zeroFraction * 100),
                    "silent" to stats.silent, "loadMs" to loadMs,
                    "live" to (chunked != null), "pieces" to (chunked?.pieces?.size ?: 0),
                    "wholeFallback" to (chunked?.wholeFallback ?: false),
                    "decodedWhileTalkingMs" to (live?.decodedWhileRecordingMs ?: 0),
                    "afterStopDecodeMs" to decodeMs, "chars" to result.text.length,
                    "replaced" to result.replaced, "respelled" to result.respelled, "soundAlike" to result.soundAlike,
                )
                result.text
            }.getOrElse {
                Trace.warn("decode_failed", "error" to it.javaClass.simpleName)
                ""
            }
            val style = cleanupStyle(field)
            if (local.isBlank() || style == null) {
                main.post { finish(local, local, if (style == null) "off" else "empty", startedSession, field, stoppedAt) }
                return@execute
            }
            net.execute {
                // The ~80 characters before the cursor, so cleanup continues your sentence (PLAN §4.5).
                val before = runCatching {
                    inputMethod?.currentInputConnection?.getSurroundingText(CONTEXT_CHARS, 0, 0)?.let {
                        it.text.subSequence(0, it.selectionStart.coerceIn(0, it.text.length)).toString()
                    }
                }.getOrNull()
                val (text, outcome) = cleanup(local, style, pass, dict.words + dict.replacements.map { it.to }.filter { t -> t.any(Char::isUpperCase) }, before)
                main.post { finish(local, text, outcome, startedSession, field, stoppedAt) }
            }
        }
    }

    /** Cleanup is used when it's on, a key is saved, and the field suits it. */
    private fun cleanupStyle(field: EditorInfo): String? {
        if (!prefs.cleanupEnabled || !keys.has()) return null
        return AppStyle.forField(field.packageName, field.inputType, field.imeOptions)
    }

    /** net thread. The cleaned text, or [local] if cleanup fails in any way. */
    private fun cleanup(local: String, style: String, pass: DictionaryPass, words: List<String>, before: String?): Pair<String, String> {
        val config = CleanupConfig.byId(prefs.cleanupModel)
        val result = cleaner.clean(local, style, words, config, prefs.cleanupDeadlineMs, before)
        when (result) {
            is Cleaner.Result.Cleaned -> {
                Trace.event(
                    "cleanup", "outcome" to "cleaned", "model" to config.id, "style" to style,
                    "firstTokenMs" to result.firstTokenMs, "totalMs" to result.totalMs,
                    "cached" to result.cachedTokens, "input" to result.inputTokens,
                    "charsIn" to local.length, "charsOut" to result.text.length,
                )
                if (prefs.keyProblem != null) prefs.keyProblem = null
                // Pass 2: the model may have re-cased a dictionary word.
                return pass.apply(result.text).text to "cleaned"
            }
            is Cleaner.Result.Fallback -> {
                Trace.event(
                    "cleanup", "outcome" to result.reason, "model" to config.id, "style" to style,
                    "totalMs" to result.totalMs, "http" to result.http, "charsIn" to local.length,
                )
                if (result.reason.keyProblem) {
                    val first = prefs.keyProblem != result.reason.name
                    prefs.keyProblem = result.reason.name
                    if (first) main.post { toast(keyProblemMessage(result.reason)) }
                }
                return local to result.reason.name
            }
        }
    }

    private fun keyProblemMessage(reason: Cleaner.Reason) = when (reason) {
        Cleaner.Reason.KEY_REJECTED -> "OpenAI didn't accept your key, so this was typed without AI cleanup"
        Cleaner.Reason.NO_CREDIT -> "Your OpenAI account is out of credit, so this was typed without AI cleanup"
        else -> "Your key can't use the cleanup model, so this was typed without AI cleanup"
    }

    private fun finish(raw: String, typed: String, cleanup: String, startedSession: Int, field: EditorInfo, stoppedAt: Long) {
        Trace.event("stop_to_insert_call", "ms" to SystemClock.elapsedRealtime() - stoppedAt)
        insert(typed, "dictation", startedSession, field, raw, cleanup, stoppedAt)
    }

    /** What the chip beside the button offers after a dictation (PLAN §4.7). */
    private sealed interface Offer {
        /** Put back the phone's own text in place of the cleaned text just typed. */
        data class Undo(val session: Int, val typed: String, val original: String) : Offer
        /** Type text that couldn't be typed, into whatever field is open now. */
        data class Retry(val text: String) : Offer
    }

    private var offer: Offer? = null
    private val clearOffer = Runnable { setOffer(null) }

    private fun setOffer(o: Offer?, forMs: Long = 0) {
        offer = o
        main.removeCallbacks(clearOffer)
        if (o != null) main.postDelayed(clearOffer, forMs)
        updateButton()
    }

    private fun onChip() {
        when (val o = offer) {
            is Offer.Undo -> {
                setOffer(null)
                if (session != o.session) return toast("That field has closed")
                val method = inputMethod ?: return
                io.execute {
                    val ok = newInserter(method).replaceLast(o.typed, o.original)
                    if (!ok) main.post { toast("The text has changed since, so it was left as is") }
                }
            }
            is Offer.Retry -> {
                val field = editor ?: return toast("Tap into a text field first")
                setOffer(null)
                setState(MicButton.State.BUSY)
                insert(o.text, "retry", session, field, o.text, "retry", SystemClock.elapsedRealtime())
            }
            null -> {}
        }
    }

    private fun newInserter(method: InputMethod) = Inserter(
        method,
        focusedField = { runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() },
        clipboard = getSystemService(ClipboardManager::class.java),
        keepCase = dictionary.load().words.toSet(),
    )

    /**
     * Main thread. Inserts only into the input session recording started in,
     * then records the dictation in history and offers undo or retry.
     */
    private fun insert(
        text: String,
        source: String,
        startedSession: Int,
        field: EditorInfo,
        raw: String = text,
        cleanup: String = "off",
        stoppedAt: Long = SystemClock.elapsedRealtime(),
    ) {
        val record = { outcome: Outcome, chars: Int, checkMs: Long, retries: Int ->
            log.add(
                InsertionRecord(
                    System.currentTimeMillis(), field.packageName ?: "?", field.inputType,
                    source, chars, outcome, checkMs, retries,
                ),
            )
        }
        val remember = { outcome: Outcome ->
            if (source != "test" && text.isNotBlank()) history.add(
                HistoryEntry(
                    System.currentTimeMillis(), field.packageName ?: "?", raw, text, cleanup,
                    outcome.name, SystemClock.elapsedRealtime() - stoppedAt,
                ),
            )
        }
        if (session != startedSession || editor == null) {
            record(Outcome.FIELD_CHANGED, text.length, 0, 0)
            io.execute { remember(Outcome.FIELD_CHANGED) }
            setState(MicButton.State.IDLE)
            if (text.isNotBlank()) {
                toast("You left that field. Tap \"Type it here\" to put it where you are now.")
                setOffer(Offer.Retry(text), RETRY_MS)
            }
            return
        }
        val method = inputMethod ?: return setState(MicButton.State.IDLE)
        val keepCase = dictionary.load().words.toSet()
        io.execute {
            val result = newInserter(method).insert(text)
            record(result.outcome, text.length, result.checkMs, result.retries)
            remember(result.outcome)
            // Undo cleanup puts back the phone's own text, fitted the same way.
            val original = if (cleanup == "cleaned" && raw != text && result.typed != null) {
                InsertionRules.fitToContext(raw, result.before, keepCase)
            } else null
            main.post {
                when (result.outcome) {
                    Outcome.EMPTY -> toast("Didn't catch that")
                    Outcome.NOT_VERIFIED -> toast("Couldn't confirm the text went in")
                    Outcome.NO_CONNECTION -> toast("The field closed before the text was ready")
                    Outcome.COPIED -> toast("Copied. Long-press the field and tap Paste.")
                    Outcome.PASTED -> if (!prefs.pasteNoticeShown) {
                        prefs.pasteNoticeShown = true
                        toast("This app needed a paste, so your dictation is now on the clipboard")
                    }
                    else -> {}
                }
                setState(MicButton.State.IDLE)
                when {
                    original != null && result.typed != null ->
                        setOffer(Offer.Undo(startedSession, result.typed, original), UNDO_MS)
                    result.outcome == Outcome.COPIED || result.outcome == Outcome.NO_CONNECTION ->
                        setOffer(Offer.Retry(text), RETRY_MS)
                }
                main.removeCallbacks(releaseIdleModel)
                main.postDelayed(releaseIdleModel, IDLE_RELEASE_MS)
            }
        }
    }

    private fun preloadModel() {
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        if (!store.isInstalled(model)) return
        main.removeCallbacks(releaseIdleModel)
        asr.execute { runCatching { loadModel() } }
    }

    /** asr thread. Returns the model and how long this call spent loading it. */
    private fun loadModel(): Pair<Transcriber, Long> {
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        transcriber?.let { if (it.model.id == model.id) return it to 0L }
        unloadModel("switch")
        val t = Transcriber.load(model, store.dir(model), prefs.asrThreads)
        transcriber = t
        Trace.event("model_loaded", "model" to model.id, "loadMs" to t.loadMs, "threads" to prefs.asrThreads)
        return t to t.loadMs
    }

    /** asr thread. */
    private fun dropChunker() {
        chunker?.release()
        chunker = null
    }

    /** asr thread. */
    private fun unloadModel(reason: String) {
        val t = transcriber ?: return
        transcriber = null
        t.release()
        Trace.event("model_released", "model" to t.model.id, "reason" to reason)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && state == MicButton.State.IDLE) {
            asr.execute { unloadModel("trim_$level") }
        }
    }

    override fun onDestroy() {
        instance = null
        cancelNote()
        if (capture != null) otherAudio.resume()
        capture?.stop()
        capture = null
        if (attached) runCatching { windowManager.removeView(button) }
        attached = false
        asr.execute { unloadModel("destroy") }
        asr.shutdown()
        io.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val TEST_PHRASE = "Flowtype insertion test."
        private const val IDLE_RELEASE_MS = 15 * 60 * 1000L
        private const val HOLD_TO_DRAG_MS = 300L
        private const val HOLD_TO_TALK_MS = 350L
        private const val CANCEL_SLIDE_DP = 100
        /** A text box whose bottom is this close to the keyboard counts as sitting on it. */
        private const val NEAR_KEYBOARD_DP = 120
        /** Taller than this, a box is a page (notes, email body), not a compose bar. */
        private const val MAX_BOX_DP = 220
        private const val CONTEXT_CHARS = 80
        private const val UNDO_MS = 6_000L
        private const val RETRY_MS = 2 * 60_000L

        /** Set while the system has the service bound; the main screen's health line reads it. */
        @Volatile
        var instance: DictationService? = null
            private set
    }
}
