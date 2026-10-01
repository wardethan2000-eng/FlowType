package com.ethanward.flowtype.audio

/**
 * Keeps the mic's audio blank until the music or video that was playing has
 * really gone quiet. Pausing it isn't instant: the player fades out, and over
 * Bluetooth or Android Auto the car's speakers play what was buffered for a
 * while after the phone stops. Until then the mic hears the song and Parakeet
 * types its first words (2026-09-30: a video's opening words came out typed).
 *
 * It opens on whichever comes first:
 * - [Why.PLAYER]: no other app's player has been started for [marginMs]
 *   (the margin covers speakers that run on after the phone stops);
 * - [Why.MIC]: the mic itself has heard near-silence for [MIC_QUIET_MS], at
 *   least [MIC_MIN_MS] after the tap: the room or car really is quiet. This is
 *   what catches a player that pauses but keeps its output running (Chrome's
 *   stays "started" ~6 s after a pause, 2026-10-01);
 * - [Why.CAP]: [capMs] after the tap whatever happens, for a player that
 *   ignores the pause.
 *
 * Only used when something was playing at the tap, so plain dictation listens
 * from the first frame as before.
 */
class QuietGate(val startMs: Long, private val marginMs: Long, private val capMs: Long = CAP_MS) {
    enum class Why { PLAYER, MIC, CAP }

    private var playerQuietSince = -1L
    private var micQuietSince = -1L

    /** When the mic opened, ms after the tap; -1 while it's still shut. */
    var openedAfterMs = -1L
        private set

    /** What opened it; null while it's still shut. */
    var why: Why? = null
        private set

    /** When other players were last seen stopping, ms after the tap; -1 if they never did. */
    var stoppedAfterMs = -1L
        private set

    /** Loudest frame the mic heard while shut (RMS, 0..1): what blanking threw away. */
    var loudestBlankedRms = 0f
        private set

    val isOpen: Boolean get() = why != null

    /**
     * One mic frame at [nowMs], with whether another player is still started
     * and the frame's loudness. True once the mic may listen; it stays open.
     */
    fun frame(nowMs: Long, otherPlaying: Boolean, micRms: Float): Boolean {
        if (isOpen) return true
        if (otherPlaying) {
            playerQuietSince = -1L
        } else if (playerQuietSince < 0) {
            playerQuietSince = nowMs
            stoppedAfterMs = nowMs - startMs
        }
        if (micRms < QUIET_RMS) {
            if (micQuietSince < 0) micQuietSince = nowMs
        } else {
            micQuietSince = -1L
        }
        val since = nowMs - startMs
        why = when {
            playerQuietSince >= 0 && nowMs - playerQuietSince >= marginMs -> Why.PLAYER
            micQuietSince >= 0 && nowMs - micQuietSince >= MIC_QUIET_MS && since >= MIC_MIN_MS -> Why.MIC
            since >= capMs -> Why.CAP
            else -> null
        }
        if (isOpen) openedAfterMs = since else loudestBlankedRms = maxOf(loudestBlankedRms, micRms)
        return isOpen
    }

    companion object {
        /** The longest the mic stays shut. */
        const val CAP_MS = 1_500L

        /** After the phone's own speaker stops (or headphones the mic can't hear). */
        const val NEAR_MARGIN_MS = 150L

        /** After the phone stops sending to Bluetooth or the car, whose speakers run on. */
        const val FAR_MARGIN_MS = 600L

        /** Near-silence at the mic: about -44 dBFS, under speech and music, over a quiet room. */
        const val QUIET_RMS = 0.006f

        /** How long the mic must hear near-silence to call the other audio gone. */
        const val MIC_QUIET_MS = 240L

        /** The mic's first frames can be silent before the player has even reacted. */
        const val MIC_MIN_MS = 300L
    }
}
