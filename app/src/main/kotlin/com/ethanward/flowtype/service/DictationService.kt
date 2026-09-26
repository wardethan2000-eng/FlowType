package com.ethanward.flowtype.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.Trace
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.ModelStore
import com.ethanward.flowtype.asr.Transcriber
import com.ethanward.flowtype.audio.AudioCapture
import com.ethanward.flowtype.audio.SignalStats
import com.ethanward.flowtype.audio.Wav
import com.ethanward.flowtype.insert.Inserter
import com.ethanward.flowtype.insert.InsertionLog
import com.ethanward.flowtype.insert.InsertionRecord
import com.ethanward.flowtype.insert.InsertionRules
import com.ethanward.flowtype.insert.Outcome
import java.util.concurrent.Executors

/**
 * The dictation service. Its own input method (flagInputMethodEditor) tells it
 * when a text field takes input; the button shows while that field is live and
 * the keyboard window is up. Tap: record. Tap again: decode on the phone and
 * commitText at the cursor (PLAN §4.1, §4.6). No cleanup yet (Phase 0).
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

    private var capture: AudioCapture? = null
    private var dictationSession = -1
    private var dictationField: EditorInfo? = null

    private var transcriber: Transcriber? = null

    private val releaseIdleModel = Runnable { asr.execute { unloadModel("idle") } }
    private val refreshKeyboard = Runnable { refreshKeyboard() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        store = ModelStore(this)
        log = InsertionLog(this)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
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
            button = v
        }
        val density = resources.displayMetrics.density
        val window = (MicButton.WINDOW_DP * density).toInt()
        val width = if (state == MicButton.State.RECORDING) (MicButton.PANEL_WINDOW_DP * density).toInt() else window
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
            val saved = prefs.buttonPosition(isLandscape())
            if (saved != null) {
                p.x = saved.first
                p.y = saved.second
            } else {
                p.x = 0
                keyboardTop?.let { p.y = it - window }
            }
            val bounds = windowManager.currentWindowMetrics.bounds
            val (x, y) = ButtonPlacement.clamp(p.x, p.y, width, window, bounds.width(), bounds.height())
            p.x = x
            p.y = y
        }
        b.setState(state)
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

    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private fun setState(s: MicButton.State) {
        state = s
        updateButton()
    }

    private fun onTap() {
        when (state) {
            MicButton.State.IDLE -> startDictation()
            // While listening, the panel's ✕ and ✓ decide; the circle is hidden.
            MicButton.State.RECORDING, MicButton.State.BUSY -> {}
        }
    }

    private fun startDictation() {
        val field = editor ?: return
        if (prefs.testPhraseMode) {
            setState(MicButton.State.BUSY)
            insert(TEST_PHRASE, "test", session, field)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Flowtype and allow the microphone")
            return
        }
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        if (!store.isInstalled(model)) {
            toast("Download a speech model in Flowtype first")
            return
        }
        val cap = AudioCapture { level -> main.post { if (state == MicButton.State.RECORDING) button?.setLevel(level) } }
        if (!cap.start()) {
            toast("Couldn't open the microphone")
            return
        }
        capture = cap
        dictationSession = session
        dictationField = field
        button?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        setState(MicButton.State.RECORDING)
        preloadModel()
    }

    /** ✕: stop listening and throw the audio away. Nothing is typed. */
    private fun cancelDictation() {
        val cap = capture ?: return
        capture = null
        val audioMs = cap.stop().size / 16
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
        button?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        setState(MicButton.State.BUSY)
        val startedSession = dictationSession
        val field = dictationField ?: return setState(MicButton.State.IDLE)
        val stoppedAt = SystemClock.elapsedRealtime()
        asr.execute {
            val samples = Wav.toFloats(pcm)
            val stats = SignalStats.of(samples)
            val text = runCatching {
                val (t, loadMs) = loadModel()
                val started = SystemClock.elapsedRealtime()
                val text = t.decode(samples)
                Trace.event(
                    "dictation", "app" to field.packageName, "model" to t.model.id,
                    "audioMs" to samples.size / 16, "peak" to "%.4f".format(stats.peak),
                    "rms" to "%.4f".format(stats.rms), "zeroPct" to "%.1f".format(stats.zeroFraction * 100),
                    "silent" to stats.silent, "loadMs" to loadMs,
                    "decodeMs" to SystemClock.elapsedRealtime() - started, "chars" to text.length,
                )
                text
            }.getOrElse {
                Trace.warn("decode_failed", "error" to it.javaClass.simpleName)
                ""
            }
            main.post {
                Trace.event("stop_to_insert_call", "ms" to SystemClock.elapsedRealtime() - stoppedAt)
                insert(text, "dictation", startedSession, field)
            }
        }
    }

    /** Main thread. Inserts only into the input session recording started in. */
    private fun insert(text: String, source: String, startedSession: Int, field: EditorInfo) {
        val record = { outcome: Outcome, chars: Int, checkMs: Long, retries: Int ->
            log.add(
                InsertionRecord(
                    System.currentTimeMillis(), field.packageName ?: "?", field.inputType,
                    source, chars, outcome, checkMs, retries,
                ),
            )
        }
        if (session != startedSession || editor == null) {
            record(Outcome.FIELD_CHANGED, text.length, 0, 0)
            toast("You left that field, so nothing was typed")
            setState(MicButton.State.IDLE)
            return
        }
        val method = inputMethod ?: return setState(MicButton.State.IDLE)
        io.execute {
            val result = Inserter(method).insert(text)
            record(result.outcome, text.length, result.checkMs, result.retries)
            main.post {
                when (result.outcome) {
                    Outcome.EMPTY -> toast("Didn't catch that")
                    Outcome.NOT_VERIFIED -> toast("Couldn't confirm the text went in")
                    Outcome.NO_CONNECTION -> toast("The field closed before the text was ready")
                    else -> {}
                }
                setState(MicButton.State.IDLE)
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
        capture?.stop()
        capture = null
        if (attached) runCatching { windowManager.removeView(button) }
        attached = false
        asr.execute { unloadModel("destroy") }
        asr.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val TEST_PHRASE = "Flowtype insertion test."
        private const val IDLE_RELEASE_MS = 15 * 60 * 1000L
        private const val HOLD_TO_DRAG_MS = 300L

        /** Set while the system has the service bound; the main screen's health line reads it. */
        @Volatile
        var instance: DictationService? = null
            private set
    }
}
