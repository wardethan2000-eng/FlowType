package com.ethanward.flowtype.cleanup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One provider's key (a [ProviderPreset.id]), AES-GCM encrypted under a key
 * that never leaves the Android Keystore, in the app's private storage
 * (PLAN §4.9). The file is useless off this phone, and the app has backups
 * turned off. OpenAI's key keeps the file and alias it had before there were
 * other providers.
 *
 * Never log what [load] returns.
 */
class ApiKeyStore(context: Context, provider: String = Providers.OPENAI.id) {
    private val file = File(context.noBackupFilesDir, if (provider == Providers.OPENAI.id) "openai-key.bin" else "key-$provider.bin")
    private val alias = if (provider == Providers.OPENAI.id) "flowtype-openai-key" else "flowtype-key-$provider"

    fun has(): Boolean = file.exists()

    fun save(key: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val sealed = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        file.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
    }

    fun load(): String? {
        if (!file.exists()) return null
        return runCatching {
            val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove() {
        file.delete()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        /** `sk-…a1B2`: all the UI ever shows of a saved key. */
        fun mask(key: String): String = if (key.length <= 8) "…" else "${key.take(3)}…${key.takeLast(4)}"

        /** Pasted keys often carry spaces or a trailing newline. */
        fun normalize(pasted: String): String = pasted.filterNot { it.isWhitespace() }

    }
}
