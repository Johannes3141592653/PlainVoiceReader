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

object BookExtractor {
    fun extract(context: Context, uri: Uri, displayName: String): List<String> {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "text", "md" -> extractText(context, uri)
            "pdf" -> extractPdf(context, uri)
            "epub" -> extractEpub(context, uri)
            "mobi", "prc" -> extractMobi(context, uri)
            else -> throw IllegalArgumentException("Unsupported file type .$ext. Use EPUB, MOBI, PRC, PDF, or TXT.")
        }.also {
            require(it.isNotEmpty()) { "No readable text was found in this file." }
        }
    }

    private fun extractText(context: Context, uri: Uri): List<String> {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not open the text file.")
        return TextCleaner.plainText(raw)
    }

    private fun extractPdf(context: Context, uri: Uri): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the PDF.")
        input.use { stream ->
            PDDocument.load(stream).use { document ->
                val raw = PDFTextStripper().getText(document)
                return TextCleaner.pdfText(raw)
            }
        }
    }

    private fun extractMobi(context: Context, uri: Uri): List<String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the MOBI file.")
        return MobiTextExtractor.extract(bytes)
    }

    private fun extractEpub(context: Context, uri: Uri): List<String> {
        val entries = linkedMapOf<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the EPUB.")
        ZipInputStream(input).use { zip ->
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

        val containerBytes = entries["META-INF/container.xml"]
            ?: error("Invalid EPUB: META-INF/container.xml is missing.")
        val container = Jsoup.parse(containerBytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
        val rootPath = container.selectFirst("rootfile")?.attr("full-path")
            ?: error("Invalid EPUB: package document not found.")
        val opfBytes = entries[normalisePath(rootPath)] ?: error("Invalid EPUB: $rootPath is missing.")
        val opf = Jsoup.parse(opfBytes.toString(Charsets.UTF_8), "", Parser.xmlParser())

        val manifest = mutableMapOf<String, String>()
        opf.select("manifest item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
        }

        val base = rootPath.substringBeforeLast('/', "")
        val orderedPaths = opf.select("spine itemref")
            .mapNotNull { manifest[it.attr("idref")] }
            .map { resolve(base, it) }

        val paths = if (orderedPaths.isNotEmpty()) orderedPaths else entries.keys
            .filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }
            .sorted()

        val blocks = mutableListOf<String>()
        for (path in paths) {
            val bytes = entries[path] ?: continue
            val html = bytes.toString(Charsets.UTF_8)
            val doc = Jsoup.parse(html)
            val selected = doc.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
            if (selected.isNotEmpty()) {
                selected.mapTo(blocks) { it.text() }
            } else if (doc.text().isNotBlank()) {
                blocks += doc.text()
            }
        }
        return TextCleaner.htmlBlocks(blocks)
    }

    private fun resolve(base: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val decoded = try { URLDecoder.decode(cleanHref, "UTF-8") } catch (_: Exception) { cleanHref }
        return normalisePath(if (base.isBlank()) decoded else "$base/$decoded")
    }

    private fun normalisePath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }
}
