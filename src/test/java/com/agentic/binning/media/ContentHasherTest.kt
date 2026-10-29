package com.agentic.binning.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentHasherTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fileWith(content: ByteArray) = tmp.newFile().also { it.writeBytes(content) }

    @Test fun sha256_ofAbc_isLowercaseHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHasher.sha256(fileWith("abc".toByteArray())),
        )
    }

    @Test fun sha256_ofEmptyFile() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHasher.sha256(fileWith(ByteArray(0))),
        )
    }

    @Test fun sha256_spanningSeveralBuffers_matchesReference() {
        val bytes = ByteArray(2 * 1024 * 1024 + 123) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ContentHasher.sha256(fileWith(bytes)))
    }
}
