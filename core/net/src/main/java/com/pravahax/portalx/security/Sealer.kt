package com.pravahax.portalx.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals values before they reach the Room database (cached responses, outbox payloads), so nothing the
 * server returned or the user typed is ever on disk in clear. AES-256-GCM, random IV per value.
 */
interface Sealer {
    fun seal(plain: String): String
    /** Throws when the value can't be opened (key invalidated, tampered row). */
    fun open(sealed: String): String
}

/** AES-GCM under a caller-supplied key. Production uses a non-exportable Android Keystore key. */
open class AesGcmSealer(private val key: SecretKey) : Sealer {
    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String {
        val i = sealed.indexOf(':')
        require(i > 0) { "bad format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(sealed.substring(0, i), Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(sealed.substring(i + 1), Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

object KeystoreSealer {
    private const val ALIAS = "portalx_db_v1"

    /** Null when the Android Keystore is unavailable (some devices, Robolectric): callers then keep data in memory only. */
    fun createOrNull(): Sealer? = runCatching { AesGcmSealer(loadOrCreateKey()).also { it.open(it.seal("probe")) } }.getOrNull()

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
