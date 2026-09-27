package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenCommandsTest {
    @Test
    fun punctuationWords() {
        assertEquals("Hello, how are you?", SpokenCommands.apply("Hello comma, how are you question mark."))
        assertEquals("Great! See you soon.", SpokenCommands.apply("Great exclamation point see you soon."))
        assertEquals("Items: eggs; milk", SpokenCommands.apply("Items colon eggs semicolon milk"))
    }

    @Test
    fun lineBreaksThatStandApart() {
        assertEquals("Shopping list.\nEggs.\nMilk.", SpokenCommands.apply("Shopping list. New line. Eggs. New line. Milk."))
        assertEquals("Dear Sarah,\n\nThanks for the update.", SpokenCommands.apply("Dear Sarah, new paragraph. Thanks for the update."))
        assertEquals("Dear Sarah\n\nThanks", SpokenCommands.apply("Dear Sarah new paragraph Thanks"))
    }

    @Test
    fun ordinaryWordsAreLeftAlone() {
        assertEquals("We need a new line of products.", SpokenCommands.apply("We need a new line of products."))
        assertEquals("The trial period ends Friday.", SpokenCommands.apply("The trial period ends Friday."))
        assertEquals("Commas are hard.", SpokenCommands.apply("Commas are hard."))
    }

    @Test
    fun oClockTimesAreWrittenInDigits() {
        assertEquals("Meet at 10:00 tomorrow.", SpokenCommands.apply("Meet at ten o'clock tomorrow."))
        assertEquals("See you at 4:00.", SpokenCommands.apply("See you at 4 o'clock."))
        assertEquals("12:00 is 12:00.", SpokenCommands.apply("Twelve o clock is 12 o’clock."))
        assertEquals("Twenty o'clock stays.", SpokenCommands.apply("Twenty o'clock stays."))
    }
}
