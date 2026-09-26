package com.ethanward.flowtype.service

/**
 * Where the overlay window goes. Positions are WindowManager offsets with
 * gravity TOP|END: x from the right edge, y from the top.
 */
object ButtonPlacement {
    /** Keeps a window of [size] fully on a [width]×[height] screen. */
    fun clamp(x: Int, y: Int, size: Int, width: Int, height: Int): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, width - size)) to y.coerceIn(0, maxOf(0, height - size))

    /**
     * The new position after the finger moved by ([dx], [dy]) from where the
     * drag started. x counts from the right edge, so moving right shrinks it.
     */
    fun dragged(startX: Int, startY: Int, dx: Float, dy: Float): Pair<Int, Int> =
        (startX - dx).toInt() to (startY + dy).toInt()
}
