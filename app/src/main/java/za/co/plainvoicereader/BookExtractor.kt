package za.co.plainvoicereader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

data class Chapter(val title: String, val index: Int)
data class ReadableBook(val paragraphs: List<String>, val chapters: List<Chapter> = emptyList(), val title: String = "", val author: String = "", val cover: ByteArray? = null)

object BookExtractor {
    fun extract(context: Context, uri: Uri, displayName: String): ReadableBook {
        val result = when (displayName.substringAfterLast('.', "").lowercase()) {
            "txt", "text", "md" -> text(context, uri)
            "pdf" -> pdf(context, uri)
            "epub" -> epub(context, uri)
            "mobi", "prc" -> MobiTextExtractor.extract(read(context, uri)).let { ReadableBook(it, detectHeadings(it)) }
            else -> error("Unsupported file type. Use EPUB, MOBI, PRC, PDF, or TXT.")
        }
        require(result.paragraphs.isNotEmpty()) { "No readable text was found in this file." }
        return result
    }

    private fun read(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open the file.")

    private fun text(context: Context, uri: Uri): ReadableBook {
        val blocks = TextCleaner.plainText(read(context, uri).toString(Charsets.UTF_8))
        return ReadableBook(blocks, detectHeadings(blocks))
    }

    private fun pdf(context: Context, uri: Uri): ReadableBook {
        PDFBoxResourceLoader.init(context.applicationContext)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { doc ->
                val stripper = PDFTextStripper()
                val pages = (1..doc.numberOfPages).map { page ->
                    stripper.startPage = page; stripper.endPage = page
                    stripper.getText(doc).lines().map { it.trim() }.filter { it.isNotBlank() }
                }
                // Only suppress recurring margin text, seen on at least two pages.
                val margins = pages.flatMap { lines -> lines.take(2) + lines.takeLast(2) }
                    .filter { it.length in 4..90 && !it.matches(Regex("\\d+")) }
                    .groupingBy { it.lowercase() }.eachCount().filterValues { it >= 2 }.keys
                val cleaned = pages.map { lines ->
                    lines.filterIndexed { i, line ->
                        !((i < 2 || i >= lines.size - 2) && (line.lowercase() in margins || line.matches(Regex("(?:page\\s*)?\\d{1,4}", RegexOption.IGNORE_CASE))))
                    }.joinToString("\n")
                }.joinToString("\n\n")
                val blocks = TextCleaner.pdfText(cleaned)
                return ReadableBook(blocks, detectHeadings(blocks), doc.documentInformation.title.orEmpty(), doc.documentInformation.author.orEmpty())
            }
        } ?: error("Could not open the PDF.")
    }

    private fun epub(context: Context, uri: Uri): ReadableBook = parseEpub(read(context, uri))

    internal fun parseEpub(bytes: ByteArray): ReadableBook {
        val entries = linkedMapOf<String, ByteArray>()
        java.io.ByteArrayInputStream(bytes).let { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val out = ByteArrayOutputStream()
                        zip.copyTo(out)
                        entries[normalisePath(entry.name)] = out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        }
        val container = Jsoup.parse(entries["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: error("Invalid EPUB container."), "", Parser.xmlParser())
        val rootPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("Invalid EPUB package document.")
        val opf = Jsoup.parse(entries[normalisePath(rootPath)]?.toString(Charsets.UTF_8) ?: error("Missing EPUB package document."), "", Parser.xmlParser())
        val base = rootPath.substringBeforeLast('/', "")
        val manifest = opf.select("manifest item").associateBy { it.attr("id") }
        val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")]?.attr("href") }.map { resolve(base, it) }
        val paths = spine.ifEmpty { entries.keys.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) }.sorted() }
        val paragraphs = mutableListOf<String>()
        val chapters = mutableListOf<Chapter>()
        for (path in paths) {
            val html = entries[path]?.toString(Charsets.UTF_8) ?: continue
            val doc = Jsoup.parse(html)
            val selected = doc.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
            if (selected.isEmpty() && doc.text().isNotBlank()) paragraphs += TextCleaner.htmlBlocks(listOf(doc.text()))
            selected.forEach { element ->
                // Container blocks with their own paragraphs would speak the same text twice.
                if (element.tagName() in setOf("li", "blockquote") && element.selectFirst("p,li,blockquote") != null) return@forEach
                val value = TextCleaner.htmlBlocks(listOf(element.text()))
                if (value.isNotEmpty()) {
                    if (element.tagName().matches(Regex("h[1-6]"))) chapters += Chapter(value.first(), paragraphs.size)
                    paragraphs += value
                }
            }
        }
        val coverId = opf.selectFirst("metadata meta[name=cover]")?.attr("content")
        val coverItem = opf.selectFirst("manifest item[properties~=cover-image]")
            ?: coverId?.let { manifest[it] }
            ?: opf.selectFirst("manifest item[id=cover]")
            ?: opf.selectFirst("manifest item[id=cover-image]")
        val cover = coverItem?.attr("href")?.let { entries[resolve(base, it)] }?.takeIf { it.size <= 5_000_000 }
        val metadata = opf.selectFirst("metadata")
        fun metaValue(localName: String): String = metadata?.children()?.firstOrNull {
            it.tagName().substringAfter(':').equals(localName, ignoreCase = true)
        }?.text().orEmpty()
        return ReadableBook(paragraphs, chapters.distinctBy { it.index }, metaValue("title"), metaValue("creator"), cover)
    }

    private fun detectHeadings(blocks: List<String>): List<Chapter> = blocks.mapIndexedNotNull { index, block ->
        if (block.length <= 90 && (block.matches(Regex("(?i)(chapter|part|book|section)\\s+[\\wIVXLC]+.*")) || (block.length < 55 && block == block.uppercase() && block.any { it.isLetter() }))) Chapter(block, index) else null
    }

    private fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = try { URLDecoder.decode(clean, "UTF-8") } catch (_: Exception) { clean }
        return normalisePath(if (base.isBlank()) decoded else "$base/$decoded")
    }

    private fun normalisePath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += part }
        }
        return parts.joinToString("/")
    }
}
