package com.ethanward.flowtype.notes

/**
 * Tells a double press of volume up apart from ordinary volume presses
 * (PLAN §4.11). The first press always goes through to the system as usual;
 * a second press within [windowMs] is kept by Flowtype, which then undoes the
 * first press's volume step. Pure logic, fed key-down times.
 */
class VolumeDoublePress(private val windowMs: Long = 400) {
    private var lastDown = Long.MIN_VALUE / 2

    /** True when this key-down completes a double press (and should be kept). */
    fun onDown(time: Long): Boolean {
        val double = time - lastDown in 0..windowMs
        lastDown = if (double) Long.MIN_VALUE / 2 else time
        return double
    }
}
