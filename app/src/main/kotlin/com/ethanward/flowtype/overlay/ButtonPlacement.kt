package com.ethanward.flowtype.overlay

/**
 * Where the overlay window goes. Positions are WindowManager offsets with
 * gravity TOP|END: x from the right edge, y from the top.
 */
object ButtonPlacement {
    /**
     * Keeps a [w]×[h] window fully on a [screenW]×[screenH] screen. The
     * listening panel is wider than the button, so near the left edge it
     * shifts right to fit.
     */
    fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, screenW - w)) to y.coerceIn(0, maxOf(0, screenH - h))

    /**
     * The new position after the finger moved by ([dx], [dy]) from where the
     * drag started. x counts from the right edge, so moving right shrinks it.
     */
    fun dragged(startX: Int, startY: Int, dx: Float, dy: Float): Pair<Int, Int> =
        (startX - dx).toInt() to (startY + dy).toInt()
}
