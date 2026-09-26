package com.ethanward.flowtype.asr

import android.content.Context
import com.ethanward.flowtype.Trace
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Models live in the app's private storage: files/models/<id>/. Downloads resume
 * from a `.part` file, are checked against the pinned SHA-256, and only the
 * files the recognizer needs are unpacked.
 */
class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models")
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun dir(model: AsrModel) = File(root, model.id)

    fun isInstalled(model: AsrModel) = AsrModel.FILES.all { File(dir(model), it).isFile }

    fun vadFile() = File(root, "silero_vad.onnx")

    fun isVadInstalled() = vadFile().length() == AsrModels.VAD_BYTES

    fun delete(model: AsrModel) = dir(model).deleteRecursively()

    /** Blocking. [progress] gets (done, total) bytes, then (-1, -1) while unpacking. */
    fun install(model: AsrModel, progress: (Long, Long) -> Unit) {
        root.mkdirs()
        val archive = File(root, "${model.archive}.tar.bz2")
        download(model.url, archive, model.bytes, model.sha256, progress)
        progress(-1, -1)
        val dest = dir(model)
        val tmp = File(root, "${model.id}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val wanted = AsrModel.FILES + AsrModel.SAMPLE_WAV
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                // "./<archive>/encoder.int8.onnx" or "<archive>/test_wavs/0.wav"
                val rel = entry.name.removePrefix("./").substringAfter('/', "")
                if (rel !in wanted) continue
                val out = File(tmp, rel)
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
            }
        }
        if (!AsrModel.FILES.all { File(tmp, it).isFile }) {
            tmp.deleteRecursively()
            throw IOException("${model.archive} is missing model files")
        }
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("could not move ${model.id} into place")
        archive.delete()
        Trace.event("model_installed", "model" to model.id)
    }

    fun installVad(progress: (Long, Long) -> Unit) {
        root.mkdirs()
        download(AsrModels.VAD_URL, vadFile(), AsrModels.VAD_BYTES, AsrModels.VAD_SHA256, progress)
    }

    private fun download(url: String, dest: File, size: Long, sha256: String, progress: (Long, Long) -> Unit) {
        if (dest.length() == size && sha256Of(dest) == sha256) return
        val part = File(dest.path + ".part")
        if (part.length() > size) part.delete()
        var have = part.length()
        if (have < size) {
            val req = Request.Builder().url(url).apply {
                if (have > 0) header("Range", "bytes=$have-")
            }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("download failed: HTTP ${resp.code}")
                // A server that ignores Range sends the whole file again (200).
                val append = have > 0 && resp.code == 206
                if (!append) have = 0
                val body = resp.body ?: throw IOException("empty response")
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport > 4_000_000) {
                                progress(have, size)
                                lastReport = have
                            }
                        }
                    }
                }
            }
        }
        progress(have, size)
        val sum = sha256Of(part)
        if (sum != sha256) {
            part.delete()
            throw IOException("checksum mismatch for ${dest.name}")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("could not move ${dest.name} into place")
    }

    companion object {
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
