package com.ethanward.flowtype.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.asr.AsrModel
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.ModelStore
import com.ethanward.flowtype.asr.Segmenter
import com.ethanward.flowtype.asr.Transcriber
import com.ethanward.flowtype.asr.Wer
import com.ethanward.flowtype.asr.LiveChunker
import com.ethanward.flowtype.asr.Piece
import com.ethanward.flowtype.asr.joinPieces
import com.ethanward.flowtype.audio.AudioCapture
import com.ethanward.flowtype.audio.Wav
import com.ethanward.flowtype.dictionary.DictionaryPass
import com.ethanward.flowtype.dictionary.DictionaryStore
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The ASR bench (PLAN §7 Phase 0 step 2), in its own process (`:bench`) so the
 * RAM it reads is the model's.
 *
 * Recordings live in this app's external files folder, `recordings/NAME.wav`
 * with the words actually said in `NAME.txt`. They never leave the phone except
 * by `adb pull`, and never go in the repo. Record them here, or `adb push` 16-bit
 * PCM WAVs there.
 *
 * Per model: load time, RSS, decode time for 5/15/60 s of audio (median of 3,
 * after one warm-up decode), then whole-utterance vs VAD-chunked decoding of
 * each recording: WER and dictionary-term hits. Results append to
 * `bench/results.jsonl` (numbers only); the decoded text goes to
 * `bench/transcripts.tsv` beside it, on the phone only.
 *
 * Unattended: `adb shell am start -n com.ethanward.flowtype/.ui.BenchActivity
 * --es models v2 --ei threads 4 --ez autorun true`.
 */
class BenchActivity : AppCompatActivity() {
    private lateinit var store: ModelStore
    private lateinit var prefs: Prefs
    private lateinit var recordingsDir: File
    private lateinit var benchDir: File
    private lateinit var recordingsList: TextView
    private lateinit var name: EditText
    private lateinit var reference: EditText
    private lateinit var recordButton: MaterialButton
    private lateinit var terms: EditText
    private lateinit var threads: EditText
    private lateinit var output: TextView
    private val modelChecks = HashMap<String, CheckBox>()
    private var capture: AudioCapture? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(this)
        prefs = Prefs(this)
        recordingsDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        benchDir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        page("Speech bench") {
            heading("Recordings")
            text("Say a sentence or two with names from your dictionary. Type what you said, so the bench can score it.")
            name = field("Name (e.g. decalforge-1)")
            reference = field("What you said", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            recordButton = button("Record") { toggleRecording() }
            recordingsList = mono()
            heading("Run")
            terms = field("Dictionary terms to score", "DecalForge, PETG, Bambu")
            threads = field("Threads", prefs.asrThreads.toString(), InputType.TYPE_CLASS_NUMBER)
            for (m in AsrModels.ALL) {
                modelChecks[m.id] = check(m.label + if (store.isInstalled(m)) "" else " (not downloaded)", store.isInstalled(m)).also {
                    it.isEnabled = store.isInstalled(m)
                }
            }
            button("Run bench") { run() }
            output = mono()
        }
        listRecordings()
        if (intent.getBooleanExtra("autorun", false)) {
            intent.getStringExtra("models")?.let { ids ->
                val wanted = ids.split(',').map { it.trim() }
                modelChecks.forEach { (id, box) -> box.isChecked = id in wanted && box.isEnabled }
            }
            if (intent.hasExtra("threads")) threads.setText(intent.getIntExtra("threads", 4).toString())
            run()
        }
    }

    private fun toggleRecording() {
        capture?.let { cap ->
            val pcm = cap.stop()
            capture = null
            recordButton.text = "Record"
            val base = name.text.toString().trim().ifEmpty { "rec-${System.currentTimeMillis() / 1000}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
            Wav.write(File(recordingsDir, "$base.wav"), pcm)
            val said = reference.text.toString().trim()
            if (said.isNotEmpty()) File(recordingsDir, "$base.txt").writeText(said)
            name.setText("")
            reference.setText("")
            listRecordings()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val cap = AudioCapture()
        if (cap.start()) {
            capture = cap
            recordButton.text = "Stop and save"
        }
    }

    private fun recordings(): List<File> =
        recordingsDir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()

    private fun listRecordings() {
        val files = recordings()
        recordingsList.text = if (files.isEmpty()) "None yet. Folder: ${recordingsDir.path}"
        else files.joinToString("\n") { f ->
            val seconds = (f.length() - 44) / 32_000.0
            val ref = if (File(f.path.removeSuffix(".wav") + ".txt").exists()) "" else "  (no reference)"
            "%-28s %5.1f s%s".format(f.name, seconds, ref)
        }
    }

    private fun log(line: String) = runOnUiThread { output.append(line + "\n") }

    private fun run() {
        if (running) return
        val models = AsrModels.ALL.filter { modelChecks[it.id]?.isChecked == true && store.isInstalled(it) }
        if (models.isEmpty()) return log("No downloaded model selected.")
        val threadCount = threads.text.toString().toIntOrNull()?.coerceIn(1, 8) ?: 4
        val termList = terms.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output.text = ""
        thread(name = "flowtype-bench") {
            try {
                for (m in models) bench(m, threadCount, termList)
                log("Done. Results: ${File(benchDir, "results.jsonl").path}")
            } catch (e: Throwable) {
                log("Failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun bench(model: AsrModel, threads: Int, terms: List<String>) {
        log("== ${model.label}, $threads threads")
        val rssBefore = memKb("VmRSS")
        val t = Transcriber.load(model, store.dir(model), threads)
        val rssLoaded = memKb("VmRSS")
        log("load ${t.loadMs} ms, RSS +${(rssLoaded - rssBefore) / 1024} MB")
        val result = JSONObject()
            .put("at", System.currentTimeMillis()).put("model", model.id).put("threads", threads)
            .put("loadMs", t.loadMs).put("rssBeforeMb", rssBefore / 1024).put("rssLoadedMb", rssLoaded / 1024)
        try {
            val files = recordings()
            val audio = benchAudio(model, files)
            fun timed(samples: FloatArray): Long {
                val s = SystemClock.elapsedRealtime()
                t.decode(samples)
                return SystemClock.elapsedRealtime() - s
            }
            val firstMs = timed(audio.copyOf(5 * Wav.RATE))
            result.put("firstDecode5sMs", firstMs)
            log("first decode (5 s) $firstMs ms")
            for (seconds in listOf(5, 15, 60)) {
                val clip = audio.copyOf(seconds * Wav.RATE)
                val ms = List(3) { timed(clip) }.sorted()[1]
                result.put("decode${seconds}sMs", ms)
                log("decode ${seconds}s: $ms ms (RTF %.3f)".format(ms / 1000.0 / seconds))
            }
            result.put("rssAfterDecodeMb", memKb("VmRSS") / 1024).put("hwmMb", memKb("VmHWM") / 1024)
            log("RSS after decoding ${memKb("VmRSS") / 1024} MB, peak ${memKb("VmHWM") / 1024} MB")
            accuracy(t, files, terms, result)
        } finally {
            t.release()
        }
        File(benchDir, "results.jsonl").appendText(result.toString() + "\n")
    }

    /**
     * 60 s of speech for the timing runs: the recordings end to end with 0.3 s
     * gaps, repeated; before any exist, the model's own sample file.
     */
    private fun benchAudio(model: AsrModel, files: List<File>): FloatArray {
        val sources = files.map { Wav.read(it) }.ifEmpty { listOf(Wav.read(File(store.dir(model), AsrModel.SAMPLE_WAV))) }
        val out = FloatArray(60 * Wav.RATE)
        var at = 0
        while (at < out.size) {
            for (s in sources) {
                val n = minOf(s.size, out.size - at)
                s.copyInto(out, at, 0, n)
                at += n + (Wav.RATE * 3 / 10)
                if (at >= out.size) break
            }
        }
        return out
    }

    private fun accuracy(t: Transcriber, files: List<File>, terms: List<String>, result: JSONObject) {
        val scored = files.filter { File(it.path.removeSuffix(".wav") + ".txt").exists() }
        if (scored.isEmpty()) {
            log("No recordings with a reference yet: accuracy skipped.")
            return
        }
        if (!store.isVadInstalled()) log("VAD not downloaded: chunked decoding skipped.")
        val segmenter = if (store.isVadInstalled()) Segmenter(store.vadFile()) else null
        val transcripts = File(benchDir, "transcripts.tsv")
        var refWords = 0
        var errWhole = 0
        var errChunked = 0
        var hitsWhole = 0
        var hitsChunked = 0
        var hitsDict = 0
        val dictPass = DictionaryPass(DictionaryStore(this).load())
        val keepCase = DictionaryStore(this).load().words.toSet()
        var termTotal = 0
        var segmentsTotal = 0
        var msWhole = 0L
        var msChunked = 0L
        var audioMs = 0L
        val per = JSONArray()
        try {
            for (f in scored) {
                val ref = File(f.path.removeSuffix(".wav") + ".txt").readText().trim()
                val samples = Wav.read(f)
                audioMs += samples.size / 16
                var s = SystemClock.elapsedRealtime()
                val whole = t.decode(samples)
                val wholeMs = SystemClock.elapsedRealtime() - s
                msWhole += wholeMs
                val refW = Wer.words(ref)
                refWords += refW.size
                val eW = Wer.errors(refW, Wer.words(whole))
                errWhole += eW
                val (hW, total) = Wer.termHits(ref, whole, terms)
                hitsWhole += hW
                termTotal += total
                val row = JSONObject().put("file", f.name).put("audioMs", samples.size / 16)
                    .put("refWords", refW.size).put("errWhole", eW).put("wholeMs", wholeMs)
                    .put("termsWhole", hW).put("terms", total)
                transcripts.appendText("${f.name}\t${t.model.id}\twhole\t$whole\n")
                val (hD, _) = Wer.termHits(ref, dictPass.apply(whole).text, terms)
                hitsDict += hD
                row.put("termsDict", hD)
                if (segmenter != null) {
                    s = SystemClock.elapsedRealtime()
                    val spans = segmenter.split(samples)
                    val chunked = joinPieces(spans.map { span ->
                        val p = span.padded(LiveChunker.PAD_BEFORE, LiveChunker.PAD_AFTER, samples.size)
                        Piece(span, t.decode(samples.copyOfRange(p.start, p.end)))
                    }, keepCase = keepCase)
                    val chunkMs = SystemClock.elapsedRealtime() - s
                    msChunked += chunkMs
                    segmentsTotal += spans.size
                    val eC = Wer.errors(refW, Wer.words(chunked))
                    errChunked += eC
                    val (hC, _) = Wer.termHits(ref, chunked, terms)
                    hitsChunked += hC
                    row.put("segments", spans.size).put("errChunked", eC).put("chunkedMs", chunkMs).put("termsChunked", hC)
                    transcripts.appendText("${f.name}\t${t.model.id}\tchunked\t$chunked\n")

                    // The exact live path: 30 ms frames through LiveChunker, as the mic delivers them.
                    val pcm = ShortArray(samples.size) { (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                    val live = LiveChunker(store.vadFile()) { t.decode(it) }
                    val liveResult = try {
                        var at = 0
                        while (at < pcm.size) {
                            val n = minOf(480, pcm.size - at)
                            live.accept(pcm.copyOfRange(at, at + n))
                            at += n
                        }
                        live.finish()
                    } finally {
                        live.release()
                    }
                    val liveText = joinPieces(liveResult.pieces, keepCase = keepCase)
                    val eL = Wer.errors(refW, Wer.words(liveText))
                    row.put("errLive", eL).put("livePieces", liveResult.pieces.size)
                    transcripts.appendText("${f.name}\t${t.model.id}\tlive\t$liveText\n")
                    for (piece in liveResult.pieces) {
                        transcripts.appendText("${f.name}\t${t.model.id}\tpiece ${piece.span.start / 16}-${piece.span.end / 16} ms\t${piece.text}\n")
                    }
                }
                per.put(row)
            }
        } finally {
            segmenter?.release()
        }
        result.put("recordings", scored.size).put("audioMs", audioMs).put("refWords", refWords)
            .put("werWhole", errWhole.toDouble() / refWords).put("termHitsWhole", hitsWhole)
            .put("termTotal", termTotal).put("termHitsDict", hitsDict).put("decodeWholeMs", msWhole).put("perRecording", per)
        log("whole: WER %.1f%%, terms %d/%d (%d/%d after the dictionary), %d ms for %.1f s".format(
            100.0 * errWhole / refWords, hitsWhole, termTotal, hitsDict, termTotal, msWhole, audioMs / 1000.0))
        if (segmenter != null) {
            result.put("werChunked", errChunked.toDouble() / refWords).put("termHitsChunked", hitsChunked)
                .put("segments", segmentsTotal).put("decodeChunkedMs", msChunked)
            log("chunked: WER %.1f%%, terms %d/%d, %d segments, %d ms".format(100.0 * errChunked / refWords, hitsChunked, termTotal, segmentsTotal, msChunked))
        }
    }

    override fun onDestroy() {
        capture?.stop()
        super.onDestroy()
    }

    companion object {
        /** A /proc/self/status field in kB (VmRSS now, VmHWM the peak). */
        fun memKb(field: String): Long =
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1
    }
}
