package com.example.imageedit.core.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Inserts PNG tEXt chunks before IEND. Pure JVM code so it is unit-testable.
 *
 * Both a byte-array convenience API and a streaming API are provided. The
 * export pipeline uses the streaming variant so a 12MP PNG never has to exist
 * as one or more full-size byte arrays in memory.
 */
object PngMetadataWriter {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun addTextChunks(png: ByteArray, entries: Map<String, String>): ByteArray {
        require(png.size >= signature.size) { "not a PNG: too short" }
        if (entries.isEmpty()) return png
        val output = ByteArrayOutputStream(png.size + entries.size * 64)
        copyWithTextChunks(ByteArrayInputStream(png), output, entries)
        return output.toByteArray()
    }

    /**
     * Copies a PNG from [input] to [output], inserting the text chunks just
     * before IEND. Chunk payloads are streamed through a reusable buffer.
     */
    fun copyWithTextChunks(
        input: InputStream,
        output: OutputStream,
        entries: Map<String, String>,
    ) {
        if (entries.isEmpty()) {
            input.copyTo(output)
            return
        }
        val data = DataInputStream(input)
        val header = ByteArray(signature.size)
        try {
            data.readFully(header)
        } catch (truncated: IOException) {
            throw IllegalArgumentException("not a PNG: too short", truncated)
        }
        require(header.contentEquals(signature)) { "not a PNG: bad signature" }
        output.write(header)

        val buffer = ByteArray(64 * 1024)
        var injected = false
        while (true) {
            val lengthBytes = ByteArray(4)
            val read = data.read(lengthBytes)
            if (read < 0) break
            if (read != 4) throw IOException("truncated PNG chunk header")

            val length = readInt(lengthBytes, 0)
            require(length >= 0) { "invalid PNG chunk length" }
            val typeBytes = ByteArray(4)
            data.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.US_ASCII)

            if (type == "IEND" && !injected) {
                for ((keyword, value) in entries) {
                    require(keyword.isNotEmpty()) { "empty PNG text keyword" }
                    val payload = keyword.toByteArray(StandardCharsets.ISO_8859_1) +
                        byteArrayOf(0) +
                        value.toByteArray(StandardCharsets.ISO_8859_1)
                    writeChunk(output, "tEXt", payload)
                }
                injected = true
            }

            output.write(lengthBytes)
            output.write(typeBytes)

            var remaining = length.toLong() + 4 // payload + CRC
            while (remaining > 0) {
                val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                val count = data.read(buffer, 0, chunk)
                if (count < 0) throw IOException("truncated PNG chunk payload")
                output.write(buffer, 0, count)
                remaining -= count
            }

            if (type == "IEND") {
                // Copy any trailing bytes after IEND for byte-for-byte parity.
                while (true) {
                    val count = data.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                break
            }
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeChunk(output: OutputStream, type: String, bytes: ByteArray) {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        writeInt(output, bytes.size)
        output.write(typeBytes)
        output.write(bytes)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(bytes)
        writeInt(output, crc.value.toInt())
    }
}
