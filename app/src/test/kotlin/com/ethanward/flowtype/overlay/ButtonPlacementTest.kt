package com.ethanward.flowtype.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonPlacementTest {
    @Test
    fun keepsTheWholeButtonOnScreen() {
        assertEquals(0 to 0, ButtonPlacement.clamp(-50, -10, 200, 200, 1080, 2340))
        assertEquals(880 to 2140, ButtonPlacement.clamp(5000, 9000, 200, 200, 1080, 2340))
        assertEquals(300 to 1200, ButtonPlacement.clamp(300, 1200, 200, 200, 1080, 2340))
    }

    @Test
    fun widePanelNearTheLeftEdgeShiftsRight() {
        // Button parked at the far left (x = 880 from the right): the 740 px
        // panel opening leftward would run off screen, so it moves to x = 340.
        assertEquals(340 to 1200, ButtonPlacement.clamp(880, 1200, 740, 200, 1080, 2340))
    }

    @Test
    fun movingRightShrinksTheOffsetFromTheRightEdge() {
        assertEquals(60 to 1250, ButtonPlacement.dragged(100, 1200, dx = 40f, dy = 50f))
        assertEquals(140 to 1150, ButtonPlacement.dragged(100, 1200, dx = -40f, dy = -50f))
    }
}
