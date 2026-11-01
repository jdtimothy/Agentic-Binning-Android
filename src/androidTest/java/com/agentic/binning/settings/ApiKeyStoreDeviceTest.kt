package com.agentic.binning.settings

import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Runs against the real AndroidKeyStore, under a test alias so no real key is touched. */
class ApiKeyStoreDeviceTest {
    private val alias = "claude_api_key_test"

    @After fun tearDown() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    @Test fun androidKeyCipher_roundTrips() {
        val cipher = AndroidKeyCipher(alias)
        val plain = "sk-ant-fake-device-1234".toByteArray()
        val blob = cipher.encrypt(plain)
        assertEquals(12 + plain.size + 16, blob.size) // iv + ciphertext + GCM tag
        assertFalse(blob.copyOfRange(12, 12 + plain.size).contentEquals(plain))
        assertArrayEquals(plain, cipher.decrypt(blob))
        assertFalse(blob.contentEquals(cipher.encrypt(plain))) // fresh IV each time
    }

    @Test fun androidKeyCipher_tamperedBlobFails() {
        val cipher = AndroidKeyCipher(alias)
        val blob = cipher.encrypt("sk-ant-fake".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(blob) }
    }

    /** M3: a deleted Keystore key is reported as unusable (the store then clears the blob). */
    @Test fun androidKeyCipher_missingKey_isKeyUnusable() {
        val cipher = AndroidKeyCipher(alias)
        val blob = cipher.encrypt("sk-ant-fake".toByteArray())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertThrows(KeyUnusableException::class.java) { cipher.decrypt(blob) }
    }
}
