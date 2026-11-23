package com.ethanward.flowtype.dictionary

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryTest {
    @Test
    fun wordsAreTrimmedDedupedAndSorted() {
        val d = Dictionary().withWord("  PETG ").withWord("bambu").withWord("petg").withWord("DecalForge")
        assertEquals(listOf("bambu", "DecalForge", "petg"), d.words)
    }

    @Test
    fun editingAWordReplacesIt() {
        val d = Dictionary().withWord("Bamboo").withWord("Bambu", replacing = "Bamboo")
        assertEquals(listOf("Bambu"), d.words)
    }

    @Test
    fun replacementsKeyOnWhatYouSay() {
        val d = Dictionary()
            .withReplacement(Replacement("Pet  G", "PETG"))
            .withReplacement(Replacement("pet g", "PET-G"))
        assertEquals(listOf(Replacement("pet g", "PET-G")), d.replacements)
    }

    @Test
    fun roundTripsThroughJson() {
        val d = Dictionary().withWord("DecalForge").withReplacement(Replacement("bamboo", "Bambu"))
        assertEquals(d, Dictionary.fromJson(JSONObject(d.toJson().toString())))
    }

    @Test
    fun importMergesAndDropsBadEntries() {
        val json = JSONObject("""{"format":"flowtype-dictionary","version":1,
            "words":["Ethan",""],"replacements":[{"from":"x","to":"y"},{"from":"pet g","to":"PETG"}]}""")
        val merged = Dictionary().withWord("Bambu").merge(Dictionary.fromJson(json))
        assertEquals(listOf("Bambu", "Ethan"), merged.words)
        assertEquals(listOf(Replacement("pet g", "PETG")), merged.replacements)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        Dictionary.fromJson(JSONObject("""{"format":"something-else"}"""))
    }

    @Test
    fun validation() {
        assertNotNull(Dictionary.problemWithWord("   "))
        assertNull(Dictionary.problemWithWord("DecalForge"))
        assertNotNull(Dictionary.problemWithReplacement("a", "b"))
        assertNotNull(Dictionary.problemWithReplacement("pet g", " "))
        assertNull(Dictionary.problemWithReplacement("pet g", "PETG"))
        assertNull(Dictionary.problemWithReplacement("decalforge", "DecalForge"))
    }
}
