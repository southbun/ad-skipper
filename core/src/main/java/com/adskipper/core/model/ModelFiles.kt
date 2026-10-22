package com.adskipper.core.model

import java.io.File
import java.security.MessageDigest

object ModelFiles {
    /** Never publish a partial, corrupt, or substituted model as ready. */
    fun verify(file: File, model: ModelFile, checkActive: () -> Unit = {}): Boolean {
        if (file.length() != model.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == model.sha256
    }

    fun validRange(header: String?, offset: Long, size: Long): Boolean {
        val match = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(header ?: "") ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == offset && end == size - 1 && total == size
    }
}
