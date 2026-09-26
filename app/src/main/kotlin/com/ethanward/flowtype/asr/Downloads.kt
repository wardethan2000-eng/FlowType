package com.ethanward.flowtype.asr

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Model downloads in progress, kept for the whole app process so leaving and
 * reopening the Models screen shows them (and can't start a second copy).
 * Everything here is read and changed on the main thread.
 */
object Downloads {
    /** null fraction = unpacking. */
    data class Progress(val fraction: Float?)

    private val main = Handler(Looper.getMainLooper())
    val running = HashMap<String, Progress>()
    val errors = HashMap<String, String>()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun changed() = listeners.toList().forEach { it() }

    fun start(key: String, work: ((Long, Long) -> Unit) -> Unit) {
        if (key in running) return
        running[key] = Progress(0f)
        errors.remove(key)
        changed()
        thread(name = "flowtype-download-$key") {
            val result = runCatching {
                work { done, total ->
                    main.post {
                        running[key] = Progress(if (done < 0) null else done.toFloat() / total)
                        changed()
                    }
                }
            }
            main.post {
                running.remove(key)
                result.exceptionOrNull()?.let { errors[key] = it.message ?: it.javaClass.simpleName }
                changed()
            }
        }
    }
}
