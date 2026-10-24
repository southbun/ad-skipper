package com.adskipper.core.model

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun verifiedBytesAreAcceptedButSameSizeCorruptionIsRejected() {
        val bytes = "GGUF test model fixture".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val model = ModelFile("test.gguf", bytes.size.toLong(), hash)
        val file = temp.newFile().apply { writeBytes(bytes) }
        assertTrue(ModelFiles.verify(file, model))
        bytes[5] = (bytes[5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFalse(ModelFiles.verify(file, model))
        file.writeBytes(bytes.copyOf(5))
        assertFalse(ModelFiles.verify(file, model))
    }

    @Test fun resumedDownloadMustCoverTheExpectedRemainingBytes() {
        assertTrue(ModelFiles.validRange("bytes 5-19/20", 5, 20))
        assertFalse(ModelFiles.validRange("bytes 0-19/20", 5, 20))
        assertFalse(ModelFiles.validRange("bytes 5-18/20", 5, 20))
        assertFalse(ModelFiles.validRange("bytes 5-19/21", 5, 20))
        assertFalse(ModelFiles.validRange(null, 5, 20))
    }
}
