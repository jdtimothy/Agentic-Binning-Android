package com.agentic.binning.settings

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Reversible stand-in for the Keystore cipher; [decryptFailure] is thrown by [decrypt] while set. */
private class FakeXorCipher : KeyCipher {
    var decryptFailure: Exception? = null
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(blob: ByteArray): ByteArray {
        decryptFailure?.let { throw it }
        return blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

class ApiKeyStoreTest {
    private lateinit var dataStore: InMemoryPreferencesDataStore
    private lateinit var cipher: FakeXorCipher
    private lateinit var store: ApiKeyStore

    @Before fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        cipher = FakeXorCipher()
        store = ApiKeyStore(dataStore, cipher)
    }

    @Test fun apiKey_roundTripsThroughCipher() = runTest {
        store.save("  sk-ant-fake-1234  ")
        assertEquals("sk-ant-fake-1234", store.load())
    }

    @Test fun apiKey_isNotStoredInPlaintext() = runTest {
        store.save("sk-ant-fake-1234")
        val stored = dataStore.data.first().asMap().values.joinToString { it.toString() }
        assertFalse(stored.contains("sk-ant-fake-1234"))
    }

    @Test fun apiKey_blankRejected() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save("   ") } }
        assertNull(store.load())
    }

    @Test fun apiKey_badTag_readsNullAndClears() = runTest {
        store.save("sk-ant-fake-1234")
        cipher.decryptFailure = AEADBadTagException("Tag mismatch")
        assertNull(store.load())
        cipher.decryptFailure = null
        assertNull(store.load()) // cleared, not just unreadable
        assertNull(store.info.first())
    }

    /** The Keystore key is gone or permanently invalidated (AndroidKeyCipher reports both this way). */
    @Test fun apiKey_unusableKeystoreKey_readsNullAndClears() = runTest {
        store.save("sk-ant-fake-1234")
        cipher.decryptFailure = KeyUnusableException("No Keystore key")
        assertNull(store.load())
        cipher.decryptFailure = null
        assertNull(store.load())
    }

    /** M3: a one-off Keystore hiccup reads as "no key this time" and keeps the saved key. */
    @Test fun apiKey_transientDecryptFailure_readsNull_butKeepsTheKey() = runTest {
        store.save("sk-ant-fake-1234")
        val failures = listOf(
            KeyStoreException("busy"),
            GeneralSecurityException("keystore hiccup"),
            IllegalStateException("odd"),
            // N2: how AndroidKeyStore reports a busy or restarting keystore daemon from getKey.
            UnrecoverableKeyException("Failed to obtain information about key"),
        )
        for (failure in failures) {
            cipher.decryptFailure = failure
            assertNull(failure.toString(), store.load())
            assertNull(store.info.first())
            cipher.decryptFailure = null
            assertEquals(failure.toString(), "sk-ant-fake-1234", store.load())
        }
    }

    /** N2: the real cipher calls only a missing key unusable; a getKey failure propagates as itself. */
    @Test fun androidKeyCipher_missingKeyIsUnusable_butAGetKeyFailureIsNot() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val blob = AndroidKeyCipher("test", lookupKey = { key }).encrypt("sk-ant-fake".toByteArray())
        assertEquals("sk-ant-fake", String(AndroidKeyCipher("test", lookupKey = { key }).decrypt(blob)))

        assertThrows(KeyUnusableException::class.java) { AndroidKeyCipher("test", lookupKey = { null }).decrypt(blob) }
        val failing = AndroidKeyCipher("test", lookupKey = { throw UnrecoverableKeyException("Failed to obtain information about key") })
        val thrown = assertThrows(GeneralSecurityException::class.java) { failing.decrypt(blob) }
        assertFalse(thrown.toString(), thrown is KeyUnusableException)
        // The store keeps the key after that failure.
        runBlocking {
            val working = ApiKeyStore(dataStore, AndroidKeyCipher("test", lookupKey = { key }))
            working.save("sk-ant-fake")
            assertNull(ApiKeyStore(dataStore, failing).load())
            assertEquals("sk-ant-fake", working.load())
        }
    }

    @Test fun apiKey_info_showsLast4Only() = runTest {
        assertNull(store.info.first())
        store.save("sk-ant-fake-1234")
        val info = store.info.first()
        assertEquals(ApiKeyInfo("1234"), info)
        assertFalse(info.toString().contains("fake"))
    }

    @Test fun apiKey_info_hidesLast4_forAShortKey() = runTest {
        store.save("abc1234")
        assertEquals(ApiKeyInfo(null), store.info.first())
        store.save("abcd1234")
        assertEquals(ApiKeyInfo("1234"), store.info.first())
    }

    @Test fun apiKey_clear() = runTest {
        store.save("sk-ant-fake-1234")
        store.clear()
        assertNull(store.load())
        assertNull(store.info.first())
    }

    @Test fun apiKeys_save_switchesSortingToClaude_andBlankDoesNot() = runTest {
        val settings = SettingsStore(dataStore)
        val keys = ApiKeys(store, settings)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { keys.save(" ") } }
        assertEquals(SortingModelChoice.Lfm3_1_7B, settings.settings.first().sortingModel)
        keys.save("sk-ant-fake-1234")
        assertEquals(SortingModelChoice.Claude, settings.settings.first().sortingModel)
        assertNotNull(store.load())
    }
}
