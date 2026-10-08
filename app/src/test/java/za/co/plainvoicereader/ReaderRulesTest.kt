package za.co.plainvoicereader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderRulesTest {
    @Test fun progressTracksSavedParagraph() {
        val book = LibraryBook("id", "book.epub", "Book", position = 3, paragraphCount = 7)
        assertEquals(50, book.progress)
        assertEquals(100, book.copy(position = 6).progress)
    }

    @Test fun pdfTextDropsStandalonePageNumbersAndJoinsHyphenatedWords() {
        val blocks = TextCleaner.pdfText("The long para-\ngraph begins here.\n\n12\n\nMore text follows.")
        assertEquals(listOf("The long paragraph begins here.", "More text follows."), blocks)
    }
    @Test fun epubKeepsSpineOrderAndMetadata() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
            entry("OPS/book.opf", """<package><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Sample Title</dc:title><dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">A Writer</dc:creator></metadata><manifest><item id="a" href="chapter.xhtml"/><item id="cover" href="cover.jpg" properties="cover-image"/></manifest><spine><itemref idref="a"/></spine></package>""")
            entry("OPS/chapter.xhtml", "<html><body><h1>Chapter One</h1><p>First paragraph.</p><p>Second paragraph.</p></body></html>")
            entry("OPS/cover.jpg", "image bytes")
        }
        val result = BookExtractor.parseEpub(out.toByteArray())
        assertEquals("Sample Title", result.title)
        assertEquals("A Writer", result.author)
        assertEquals(listOf("Chapter One", "First paragraph.", "Second paragraph."), result.paragraphs)
        assertEquals(listOf(Chapter("Chapter One", 0)), result.chapters)
        assertEquals("image bytes", result.cover?.toString(Charsets.UTF_8))
    }
}
