package com.agentic.binning.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the Settings screen may show about a saved key: never the key itself. [last4] is null for a
 * key shorter than [ApiKeyInfo.MIN_LENGTH_FOR_LAST4], whose last 4 would give away too much of it.
 */
data class ApiKeyInfo(val last4: String?) {
    companion object {
        const val MIN_LENGTH_FOR_LAST4 = 8

        fun of(key: String): ApiKeyInfo = ApiKeyInfo(key.takeLast(4).takeIf { key.length >= MIN_LENGTH_FOR_LAST4 })
    }
}

/**
 * The cipher's key is gone or permanently unusable (deleted, or invalidated by a lock-screen
 * change), so nothing encrypted with it can ever be decrypted again. A key that merely can't be read
 * right now is not this.
 */
class KeyUnusableException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Encrypts and decrypts the API key. [decrypt] throws if [blob] cannot be decrypted: an
 * [AEADBadTagException] or [KeyUnusableException] when it never will be, anything else when it may
 * work next time.
 */
interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES/GCM/NoPadding with a non-exportable Android Keystore key. The blob is `iv(12) + ciphertext`
 * (the ciphertext includes the GCM tag).
 */
class AndroidKeyCipher internal constructor(
    private val alias: String,
    /** Reads the key for an alias, or null when there is none; the Keystore unless a test swaps it. */
    private val lookupKey: (String) -> SecretKey?,
) : KeyCipher {

    constructor(alias: String = DEFAULT_ALIAS) : this(alias, ::keystoreKey)

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, lookupKey(alias) ?: newKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    /**
     * A missing alias or a permanently invalidated key is a [KeyUnusableException]; a key is never
     * created here, since it could not open an older blob. Any other failure to read the key (for
     * example `UnrecoverableKeyException`, which is how the Keystore reports a busy or restarting
     * daemon) propagates as itself, so the saved key is kept.
     */
    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Encrypted blob is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = lookupKey(alias) ?: throw KeyUnusableException("No Keystore key")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyUnusableException("The Keystore key was invalidated", e)
        }
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun newKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "claude_api_key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        private fun keystoreKey(alias: String): SecretKey? {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            return keyStore.getKey(alias, null) as? SecretKey
        }
    }
}

/**
 * The Claude API key, encrypted with [cipher] and kept in [dataStore] under its own preference
 * (never in [AppSettings]). The plaintext key never reaches a log, a `toString()` or an exception message.
 */
class ApiKeyStore(private val dataStore: DataStore<Preferences>, private val cipher: KeyCipher) {

    /** Saves the trimmed [key]. @throws IllegalArgumentException if it is blank. */
    suspend fun save(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "The API key is blank" }
        val blob = Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[ENCRYPTED_KEY] = blob }
    }

    /**
     * The saved key, or null if none is saved or it can't be decrypted now. Only a key that can never
     * be decrypted again ([AEADBadTagException], [KeyUnusableException]) is cleared; any other
     * failure (a busy Keystore) reads as no key this time and keeps it for the next call.
     */
    suspend fun load(): String? {
        val stored = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()[ENCRYPTED_KEY] ?: return null
        return when (val result = decrypt(stored)) {
            is Decoded.Key -> result.key
            Decoded.Gone -> null.also { clear() }
            Decoded.NotNow -> null
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(ENCRYPTED_KEY) }
    }

    /** The saved key's [ApiKeyInfo] (its last 4 characters), or null if none (or an unreadable one). */
    val info: Flow<ApiKeyInfo?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ENCRYPTED_KEY]?.let { (decrypt(it) as? Decoded.Key)?.key }?.let(ApiKeyInfo::of) }

    private sealed interface Decoded {
        class Key(val key: String) : Decoded
        /** Never decryptable again: a reset Keystore or a blob from another key. */
        data object Gone : Decoded
        /** Failed this time for another reason; the blob is kept. */
        data object NotNow : Decoded
    }

    /** The cause is dropped on purpose: only its type decides what happens. */
    private fun decrypt(stored: String): Decoded = try {
        Decoded.Key(String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (_: AEADBadTagException) {
        Decoded.Gone
    } catch (_: KeyUnusableException) {
        Decoded.Gone
    } catch (_: Exception) {
        Decoded.NotNow
    }

    private companion object {
        val ENCRYPTED_KEY = stringPreferencesKey("claude_api_key_enc")
    }
}

/**
 * The path the Settings UI saves a key through: stores it, then lets [SettingsStore] switch the
 * sorting model to Claude if the user has not chosen one. A blank key throws and changes nothing.
 */
class ApiKeys(private val store: ApiKeyStore, private val settings: SettingsStore) {
    val info: Flow<ApiKeyInfo?> get() = store.info

    suspend fun save(key: String) {
        store.save(key)
        settings.onApiKeySaved()
    }

    suspend fun load(): String? = store.load()

    suspend fun clear() = store.clear()
}
