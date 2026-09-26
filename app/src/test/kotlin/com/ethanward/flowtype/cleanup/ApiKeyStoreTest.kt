package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyStoreTest {
    @Test
    fun masksAllButTheEnds() {
        assertEquals("sk-…a1B2", ApiKeyStore.mask("sk-proj-abcdefghijklmnopa1B2"))
        assertEquals("sk-…", ApiKeyStore.mask("sk-12"))
    }

    @Test
    fun pastedKeysAreTrimmed() {
        assertEquals("sk-abc123", ApiKeyStore.normalize("  sk-abc\n123 \n"))
    }

    @Test
    fun formatCheck() {
        assertTrue(ApiKeyStore.looksValid("sk-proj-" + "x".repeat(40)))
        assertFalse(ApiKeyStore.looksValid("pk-" + "x".repeat(40)))
        assertFalse(ApiKeyStore.looksValid("sk-short"))
    }
}
