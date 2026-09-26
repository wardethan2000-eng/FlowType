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

    // Screen px at 3x: keyboard top at 1500, button window 204, gap 360, max box 660.
    private fun above(top: Int?, bottom: Int?) = ButtonPlacement.aboveKeyboard(1500, 204, top, bottom, 360, 660)

    @Test
    fun composeBarOnTheKeyboardGetsTheButtonAboveIt() {
        // A chat box from 1350 to 1480, right on the keyboard.
        assertEquals(1350 - 204, above(1350, 1480))
    }

    @Test
    fun otherFieldsKeepItAboveTheKeyboard() {
        assertEquals(1500 - 204, above(200, 330)) // a search bar at the top
        assertEquals(1500 - 204, above(300, 1480)) // a notes page down to the keyboard
        assertEquals(1500 - 204, above(null, null)) // unknown
    }
}
