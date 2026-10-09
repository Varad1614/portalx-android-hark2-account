package com.pravahax.portalx.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small key/value store whose values are encrypted with AES-256-GCM under a non-exportable key held
 * in the Android Keystore. Keys (names) are stored in clear; values never are.
 *
 * Fail-safe behaviour:
 *  - If the Keystore is unavailable (some devices, Robolectric) the store runs memory-only: nothing is
 *    written to disk in clear. The user simply has to sign in again after a restart.
 *  - If a value can't be decrypted (key invalidated, data restored from elsewhere) the whole store is
 *    wiped rather than crashing; the app then treats the user as signed out.
 */
class SecureStore(context: Context, name: String) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sec_$name", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, String>()
    private val alias = "portalx_store_v1"
    private val key: SecretKey? = runCatching { loadOrCreateKey() }.getOrNull()

    /** True when values are persisted encrypted on disk; false when running memory-only. */
    val persistent: Boolean get() = key != null

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val i = stored.indexOf(':')
        require(i > 0) { "bad format" }
        val iv = Base64.decode(stored.substring(0, i), Base64.NO_WRAP)
        val ct = Base64.decode(stored.substring(i + 1), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    @Synchronized fun get(k: String): String? {
        if (key == null) return memory[k]
        val raw = prefs.getString(k, null) ?: return null
        // One unreadable entry (e.g. a transient Keystore error after boot/OS update) loses only that entry, never the
        // whole store: wiping it used to sign the user out and mint a new device id.
        return try { decrypt(raw) } catch (e: Exception) { prefs.edit().remove(k).apply(); null }
    }

    @Synchronized fun put(k: String, v: String?) {
        if (v == null) { remove(k); return }
        if (key == null) { memory[k] = v; return }
        try { prefs.edit().putString(k, encrypt(v)).apply() } catch (e: Exception) { memory[k] = v }
    }

    @Synchronized fun remove(k: String) { memory.remove(k); prefs.edit().remove(k).apply() }

    @Synchronized fun keys(): Set<String> = if (key == null) memory.keys.toSet() else prefs.all.keys.toSet()

    @Synchronized fun all(): Map<String, String> = keys().mapNotNull { k -> get(k)?.let { k to it } }.toMap()

    @Synchronized fun clear() { memory.clear(); prefs.edit().clear().apply() }
}
