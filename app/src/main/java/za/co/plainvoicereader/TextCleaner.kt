package za.co.plainvoicereader

object TextCleaner {
    private const val MAX_TTS_CHARS = 2600

    fun plainText(raw: String): List<String> {
        val normal = normalise(raw)
        val blocks = normal.split(Regex("\\n\\s*\\n+"))
            .map { cleanBlock(it) }
            .filter { it.isNotBlank() }
        return segment(blocks)
    }

    fun pdfText(raw: String): List<String> {
        var text = raw.replace("\\r\\n", "\\n").replace('\r', '\n')
        text = text.replace(Regex("(?m)^\\s*\\d{1,4}\\s*$"), "")
        text = text.replace(Regex("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{Ll})"), "")

        val blocks = if (Regex("\\n\\s*\\n").containsMatchIn(text)) {
            text.split(Regex("\\n\\s*\\n+"))
                .map { cleanBlock(it) }
                .filter { it.isNotBlank() }
        } else {
            pdfLineHeuristic(text)
        }
        return segment(blocks)
    }

    fun htmlBlocks(blocks: List<String>): List<String> =
        segment(blocks.map { cleanBlock(it) }.filter { it.isNotBlank() })

    private fun normalise(raw: String): String =
        raw.replace("\\r\\n", "\\n")
            .replace('\r', '\n')
            .replace(Regex("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{Ll})"), "")

    private fun cleanBlock(block: String): String =
        block.lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun pdfLineHeuristic(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val value = current.toString().replace(Regex("\\s+"), " ").trim()
            if (value.isNotBlank()) result += value
            current.clear()
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                flush()
                continue
            }
            if (line.matches(Regex("\\d{1,4}"))) continue

            if (current.isNotEmpty()) current.append(' ')
            current.append(line)

            val looksLikeHeading = line.length < 90 && !line.endsWith(".") &&
                    line.count { it == ' ' } < 12 && line == line.uppercase()
            val naturalStop = line.endsWith('.') || line.endsWith('?') || line.endsWith('!')
            if (looksLikeHeading || (naturalStop && current.length > 180) || current.length > 900) {
                flush()
            }
        }
        flush()
        return result
    }

    private fun segment(blocks: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (block in blocks) {
            if (block.length <= MAX_TTS_CHARS) {
                out += block
                continue
            }
            val sentences = block.split(Regex("(?<=[.!?])\\s+"))
            val current = StringBuilder()
            for (sentence in sentences) {
                if (current.isNotEmpty() && current.length + sentence.length + 1 > MAX_TTS_CHARS) {
                    out += current.toString().trim()
                    current.clear()
                }
                if (sentence.length > MAX_TTS_CHARS) {
                    if (current.isNotEmpty()) {
                        out += current.toString().trim()
                        current.clear()
                    }
                    sentence.chunked(MAX_TTS_CHARS).forEach { out += it.trim() }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
            }
            if (current.isNotEmpty()) out += current.toString().trim()
        }
        return out.filter { it.isNotBlank() }
    }
}
