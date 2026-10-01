package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyStoreTest {
    @Test
    fun masksAllButTheEnds() {
        assertEquals("sk-…a1B2", ApiKeyStore.mask("sk-proj-abcdefghijklmnopa1B2"))
        assertEquals("…", ApiKeyStore.mask("sk-12"))
    }

    @Test
    fun pastedKeysAreTrimmed() {
        assertEquals("sk-abc123", ApiKeyStore.normalize("  sk-abc\n123 \n"))
    }

    @Test
    fun eachProviderChecksItsOwnKeyFormat() {
        assertNull(Providers.problemWithKey(Providers.OPENAI, "sk-proj-" + "x".repeat(40)))
        assertNotNull(Providers.problemWithKey(Providers.OPENAI, "pk-" + "x".repeat(40)))
        assertNotNull(Providers.problemWithKey(Providers.OPENAI, "sk-short"))
        // An OpenAI key pasted under Anthropic is caught.
        assertNotNull(Providers.problemWithKey(Providers.ANTHROPIC, "sk-proj-" + "x".repeat(40)))
        assertNull(Providers.problemWithKey(Providers.ANTHROPIC, "sk-ant-api03-" + "x".repeat(40)))
        assertNull(Providers.problemWithKey(Providers.GROQ, "gsk_" + "x".repeat(40)))
        // Your own server: no key at all is fine, anything else too.
        assertNull(Providers.problemWithKey(Providers.CUSTOM, ""))
        assertNull(Providers.problemWithKey(Providers.CUSTOM, "whatever-token-123"))
    }

    @Test
    fun yourOwnServersAddress() {
        assertEquals("http://192.168.1.5:11434/v1", Providers.baseUrl(Providers.CUSTOM, "192.168.1.5:11434"))
        assertEquals("http://localhost:1234/v1", Providers.baseUrl(Providers.CUSTOM, "http://localhost:1234/"))
        assertEquals("https://llm.example.com/api/v1", Providers.baseUrl(Providers.CUSTOM, "https://llm.example.com/api/v1"))
        assertNull(Providers.baseUrl(Providers.CUSTOM, "  "))
        assertEquals("https://api.groq.com/openai/v1", Providers.baseUrl(Providers.GROQ, "ignored"))
    }
}
