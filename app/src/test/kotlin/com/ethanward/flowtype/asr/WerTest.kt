package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class WerTest {
    @Test
    fun normalizesCaseAndPunctuation() {
        assertEquals(listOf("don't", "stop", "it's", "fine"), Wer.words("Don't stop, it's... FINE!"))
        assertEquals(listOf("well", "known"), Wer.words("well-known"))
    }

    @Test
    fun countsSubstitutionsInsertionsDeletions() {
        assertEquals(0, Wer.errors(listOf("a", "b"), listOf("a", "b")))
        assertEquals(1, Wer.errors(listOf("a", "b", "c"), listOf("a", "x", "c")))
        assertEquals(1, Wer.errors(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(2, Wer.errors(listOf("a", "b", "c"), listOf("c")))
    }

    @Test
    fun splitNameCostsTwoErrors() {
        // "DecalForge" heard as "decal forge": one substitution plus one insertion.
        assertEquals(2.0 / 3, Wer.rate("print DecalForge today", "print decal forge today"), 1e-9)
    }

    @Test
    fun emptyReference() {
        assertEquals(0.0, Wer.rate("", ""), 0.0)
        assertEquals(1.0, Wer.rate("", "noise"), 0.0)
    }

    @Test
    fun termHitsNeedExactCase() {
        val terms = listOf("DecalForge", "PETG")
        assertEquals(2 to 3, Wer.termHits("DecalForge in PETG, more PETG", "DecalForge in PETG, more pet g", terms))
        assertEquals(0 to 1, Wer.termHits("the DecalForge app", "the Decalforge app", terms))
        // Whole words only.
        assertEquals(0 to 0, Wer.termHits("PETGs are fine", "PETGs are fine", listOf("PETG")))
    }
}
