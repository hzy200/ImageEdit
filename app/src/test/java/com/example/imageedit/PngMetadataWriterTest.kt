package com.example.imageedit

import com.example.imageedit.core.export.PngMetadataWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class PngMetadataWriterTest {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    @Test
    fun insertsTextChunkBeforeIend() {
        val png = signature + chunk("IEND", ByteArray(0))
        val output = PngMetadataWriter.addTextChunks(
            png,
            linkedMapOf("Software" to "ImageEdit", "AI-Edited" to "true"),
        )
        val text = output.toString(StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("tEXt"))
        assertTrue(text.contains("Software"))
        assertTrue(text.contains("AI-Edited"))
        assertTrue(output.size > png.size)
    }

    @Test
    fun rejectsNonPng() {
        var failed = false
        try {
            PngMetadataWriter.addTextChunks("not png".toByteArray(), mapOf("a" to "b"))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun streamsChunksAndPreservesTrailingBytes() {
        val trailing = byteArrayOf(1, 2, 3, 4)
        val png = signature + chunk("IHDR", ByteArray(4)) + chunk("IEND", ByteArray(0)) + trailing
        val output = ByteArrayOutputStream()
        PngMetadataWriter.copyWithTextChunks(
            ByteArrayInputStream(png),
            output,
            linkedMapOf("Software" to "ImageEdit"),
        )
        val bytes = output.toByteArray()
        val text = bytes.toString(StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("tEXt"))
        assertTrue(text.contains("Software"))
        assertArrayEquals(trailing, bytes.takeLast(trailing.size).toByteArray())
    }

    @Test
    fun returnsSameBytesWhenNoEntries() {
        val png = signature + chunk("IEND", ByteArray(0))
        assertArrayEquals(png, PngMetadataWriter.addTextChunks(png, emptyMap()))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        val output = ByteArrayOutputStream()
        output.write(intBytes(data.size))
        output.write(typeBytes)
        output.write(data)
        output.write(intBytes(crc.value.toInt()))
        return output.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
