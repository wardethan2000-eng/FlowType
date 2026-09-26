package com.ethanward.flowtype.notes

/**
 * Tells "hold volume up to take a note" apart from an ordinary tap
 * (PLAN §4.11). A double press couldn't be: quick taps to raise the volume
 * looked the same, so every second tap was eaten.
 *
 * Every volume-up press is held back on key-down. Released before [holdMs]
 * it was a tap, and the caller raises the volume then (a few ms late, same
 * result). Still down at [holdMs], it's a hold: a note starts, and the
 * release is swallowed. Pure logic, fed times.
 */
class VolumeHold(val holdMs: Long = 600) {
    private var downAt = -1L
    private var fired = false

    /** Key-down: always held back. */
    fun onDown(time: Long) {
        downAt = time
        fired = false
    }

    /** The hold timer ran out while still down: start a note (true once per press). */
    fun onTimer(time: Long): Boolean {
        if (downAt < 0 || fired || time - downAt < holdMs) return false
        fired = true
        return true
    }

    /** Key-up: true if it was a tap, so the volume should go up one step. */
    fun onUp(): Boolean {
        val tap = downAt >= 0 && !fired
        downAt = -1
        fired = false
        return tap
    }
}
