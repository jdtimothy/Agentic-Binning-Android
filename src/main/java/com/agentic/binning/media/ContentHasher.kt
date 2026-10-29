package com.agentic.binning.media

import java.io.File
import java.security.MessageDigest

object ContentHasher {
    private const val BUFFER_SIZE = 1024 * 1024

    /** SHA-256 of the file's bytes as lowercase hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
