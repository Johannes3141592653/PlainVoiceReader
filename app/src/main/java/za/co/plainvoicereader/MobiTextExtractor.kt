package za.co.plainvoicereader

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal MOBI/PalmDOC text extractor for unencrypted, text-focused books.
 * Supports PalmDOC compression 1 (none) and 2 (PalmDOC).
 * HUFF/CDIC-compressed and DRM-protected files are intentionally rejected.
 */
object MobiTextExtractor {
    fun extract(bytes: ByteArray): List<String> {
        require(bytes.size > 100) { "This MOBI file is too small or invalid." }
        val recordCount = u16(bytes, 76)
        require(recordCount > 1) { "No MOBI text records were found." }

        val offsets = IntArray(recordCount)
        var pos = 78
        for (i in 0 until recordCount) {
            require(pos + 8 <= bytes.size) { "Invalid MOBI record table." }
            offsets[i] = u32(bytes, pos)
            pos += 8
        }

        val record0Start = offsets[0]
        val record0End = if (recordCount > 1) offsets[1] else bytes.size
        require(record0Start >= 0 && record0End <= bytes.size && record0End > record0Start + 16) {
            "Invalid MOBI header."
        }

        val compression = u16(bytes, record0Start)
        val textLength = u32(bytes, record0Start + 4)
        val textRecordCount = u16(bytes, record0Start + 8)
        val encryption = u16(bytes, record0Start + 12)

        require(encryption == 0) {
            "This MOBI appears to be DRM-protected. Plain Voice Reader does not bypass DRM."
        }
        require(compression == 1 || compression == 2) {
            if (compression == 17480) {
                "This MOBI uses HUFF/CDIC compression, which is not supported in the first Android build."
            } else {
                "Unsupported MOBI compression type: $compression"
            }
        }

        var encoding = 1252
        if (record0Start + 32 <= record0End && ascii(bytes, record0Start + 16, 4) == "MOBI") {
            encoding = u32(bytes, record0Start + 28)
        }

        val output = ByteArrayOutputStream()
        val count = minOf(textRecordCount, recordCount - 1)
        for (i in 1..count) {
            val start = offsets[i]
            val end = if (i + 1 < recordCount) offsets[i + 1] else bytes.size
            if (start < 0 || end > bytes.size || end <= start) continue
            val record = bytes.copyOfRange(start, end)
            val decoded = if (compression == 2) palmDocDecompress(record) else record
            output.write(decoded)
            if (output.size() >= textLength) break
        }

        var textBytes = output.toByteArray()
        if (textLength > 0 && textLength < textBytes.size) {
            textBytes = textBytes.copyOf(textLength)
        }

        val charset = when (encoding) {
            65001 -> Charsets.UTF_8
            1252 -> Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }
        val raw = textBytes.toString(charset)
        val doc = Jsoup.parse(raw)
        val selected = doc.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
            .map { it.text() }
            .filter { it.isNotBlank() }

        return if (selected.isNotEmpty()) {
            TextCleaner.htmlBlocks(selected)
        } else {
            TextCleaner.plainText(doc.text())
        }
    }

    private fun palmDocDecompress(input: ByteArray): ByteArray {
        val out = ArrayList<Byte>(input.size * 2)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            when {
                c == 0 -> {
                    out.add(0.toByte())
                    i++
                }
                c in 1..8 -> {
                    i++
                    repeat(c) {
                        if (i < input.size) out.add(input[i++])
                    }
                }
                c in 9..0x7F -> {
                    out.add(c.toByte())
                    i++
                }
                c in 0x80..0xBF -> {
                    if (i + 1 >= input.size) break
                    val pair = (c shl 8) or (input[i + 1].toInt() and 0xFF)
                    val distance = (pair and 0x3FFF) shr 3
                    val length = (pair and 0x7) + 3
                    if (distance <= 0 || distance > out.size) {
                        throw IllegalArgumentException("Invalid PalmDOC back-reference in MOBI file.")
                    }
                    repeat(length) {
                        val sourceIndex = out.size - distance
                        out.add(out[sourceIndex])
                    }
                    i += 2
                }
                else -> {
                    out.add(' '.code.toByte())
                    out.add((c xor 0x80).toByte())
                    i++
                }
            }
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun u16(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or
                ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or
                (b[p + 3].toInt() and 0xFF)

    private fun ascii(b: ByteArray, p: Int, length: Int): String =
        b.copyOfRange(p, p + length).toString(Charsets.US_ASCII)
}
