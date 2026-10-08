package za.co.plainvoicereader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The library index is private app data. Book files remain usable after their source disappears. */
data class LibraryBook(
    val id: String,
    val fileName: String,
    val title: String,
    val author: String = "",
    val coverFile: String = "",
    val position: Int = 0,
    val paragraphCount: Int = 0,
    val lastRead: Long = 0L,
    val voiceName: String = "",
    val bookmarks: List<Int> = emptyList()
) {
    val progress: Int get() = if (paragraphCount <= 1) 0 else (position * 100 / (paragraphCount - 1)).coerceIn(0, 100)
}

class LibraryStore(private val context: Context) {
    private val directory = File(context.filesDir, "books").apply { mkdirs() }
    private val index = File(context.filesDir, "library.json")

    fun books(): List<LibraryBook> = try {
        val array = JSONArray(index.readText())
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val marks = obj.optJSONArray("bookmarks") ?: JSONArray()
            LibraryBook(
                obj.getString("id"), obj.getString("fileName"), obj.optString("title"),
                obj.optString("author"), obj.optString("coverFile"), obj.optInt("position"),
                obj.optInt("paragraphCount"), obj.optLong("lastRead"), obj.optString("voiceName"),
                (0 until marks.length()).map { marks.getInt(it) }
            )
        }.filter { file(it).isFile }.sortedByDescending { it.lastRead }
    } catch (_: Exception) { emptyList() }

    fun file(book: LibraryBook): File = File(directory, book.fileName)
    fun cover(book: LibraryBook): File? = book.coverFile.takeIf { it.isNotBlank() }?.let { File(directory, it) }?.takeIf { it.isFile }

    fun import(uri: Uri, displayName: String): Pair<LibraryBook, ReadableBook> {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in setOf("epub", "pdf", "mobi", "prc", "txt", "text", "md")) { "Unsupported file type .$extension." }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(temporary.length() + count <= 200_000_000L) { "This book exceeds the 200 MB import limit." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not open the selected file.")
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            books().find { it.id == id }?.let { existing ->
                return existing to BookExtractor.extract(context, Uri.fromFile(file(existing)), existing.fileName)
            }
            val fileName = "$id.$extension"
            val target = File(directory, fileName)
            require(temporary.renameTo(target)) { "Could not store the book." }
            try {
                val parsed = BookExtractor.extract(context, Uri.fromFile(target), fileName)
                val coverName = parsed.cover?.let { bytes ->
                    "$id.cover".also { File(directory, it).writeBytes(bytes) }
                } ?: ""
                val book = LibraryBook(id, fileName, parsed.title.ifBlank { displayName.substringBeforeLast('.') }, parsed.author, coverName, paragraphCount = parsed.paragraphs.size, lastRead = System.currentTimeMillis())
                save(books() + book)
                return book to parsed
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        } finally {
            temporary.delete()
        }
    }

    fun update(book: LibraryBook) = save(books().map { if (it.id == book.id) book else it })

    fun delete(book: LibraryBook) {
        save(books().filterNot { it.id == book.id })
        file(book).delete()
        cover(book)?.delete()
    }

    private fun save(books: List<LibraryBook>) {
        val array = JSONArray()
        books.forEach { book ->
            array.put(JSONObject().apply {
                put("id", book.id); put("fileName", book.fileName); put("title", book.title)
                put("author", book.author); put("coverFile", book.coverFile)
                put("position", book.position); put("paragraphCount", book.paragraphCount)
                put("lastRead", book.lastRead); put("voiceName", book.voiceName)
                put("bookmarks", JSONArray(book.bookmarks))
            })
        }
        val temporary = File(context.filesDir, "library.json.tmp")
        temporary.writeText(array.toString())
        require(temporary.renameTo(index)) { "Could not save the library." }
    }
}
