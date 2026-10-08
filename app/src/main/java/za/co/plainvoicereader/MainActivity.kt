package za.co.plainvoicereader

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val readerColors = lightColorScheme(
    primary = Color(0xFF145A60), onPrimary = Color.White,
    primaryContainer = Color(0xFFCEE8E7), onPrimaryContainer = Color(0xFF123D42),
    secondary = Color(0xFF9A6546), background = Color(0xFFF6F3EB),
    surface = Color(0xFFFFFDF8), surfaceVariant = Color(0xFFEAE6DB)
)

private val nightColors = darkColorScheme(
    primary = Color(0xFF9DD1D0), onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF244C50), secondary = Color(0xFFE4B494),
    background = Color(0xFF101A1D), surface = Color(0xFF1B282A), surfaceVariant = Color(0xFF2D3A3A)
)

private data class VoiceOption(val voice: Voice, val name: String, val label: String, val quality: Int)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var store: LibraryStore
    private val library = mutableStateListOf<LibraryBook>()
    private val paragraphs = mutableStateListOf<String>()
    private val chapters = mutableStateListOf<Chapter>()
    private var activeBook by mutableStateOf<LibraryBook?>(null)
    private var currentIndex by mutableIntStateOf(0)
    private var speed by mutableFloatStateOf(1f)
    private var isPlaying by mutableStateOf(false)
    private var ttsReady by mutableStateOf(false)
    private var status by mutableStateOf("Add a book to begin.")
    private var screen by mutableStateOf("library")
    private var voiceReturnScreen = "library"
    private var selectedVoice by mutableStateOf("")
    private var availableVoices by mutableStateOf<List<VoiceOption>>(emptyList())
    private var activeUtterance = ""
    private var utteranceNumber = 0L
    private var speechChunks = emptyList<String>()
    private var chunkIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        library.addAll(store.books())
        speed = getPreferences(MODE_PRIVATE).getFloat("speed", 1f).coerceIn(1f, 3f)
        selectedVoice = getPreferences(MODE_PRIVATE).getString("voice", "").orEmpty()
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId == activeUtterance) status =
                        if (utteranceId?.startsWith("test_") == true) "Voice test playing" else "Reading"
                }
            }
            override fun onError(utteranceId: String?) {
                reportSpeechError(utteranceId, TextToSpeech.ERROR)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                reportSpeechError(utteranceId, errorCode)
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId != activeUtterance) return@runOnUiThread
                    if (utteranceId?.startsWith("test_") == true) {
                        status = "Voice test finished."
                        return@runOnUiThread
                    }
                    if (!isPlaying) return@runOnUiThread
                    if (chunkIndex + 1 < speechChunks.size) {
                        chunkIndex++
                        queueCurrentChunk()
                        return@runOnUiThread
                    }
                    val next = nextSpeakable(currentIndex + 1)
                    if (next < paragraphs.size) {
                        currentIndex = next
                        savePosition()
                        speakCurrent()
                    } else {
                        isPlaying = false
                        status = "Finished."
                        savePosition()
                    }
                }
            }
        })
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) nightColors else readerColors) { Surface(Modifier.fillMaxSize()) { AppScreen() } } }
    }

    override fun onInit(result: Int) {
        runOnUiThread {
            if (result != TextToSpeech.SUCCESS) {
                status = "Android Text-to-Speech could not initialise."
                return@runOnUiThread
            }
            availableVoices = runCatching { tts.voices }.getOrNull().orEmpty().mapNotNull { voice ->
                runCatching {
                    if (voice.isNetworkConnectionRequired) return@runCatching null
                    val name = voice.name?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    VoiceOption(voice, name, voice.locale?.displayName ?: "Unknown language", voice.quality)
                }.getOrNull()
            }.sortedWith(compareBy({ it.label }, { -it.quality }, { it.name }))
            if (selectedVoice.isNotBlank()) setVoice(selectedVoice)
            tts.setSpeechRate(speed)
            ttsReady = true
        }
    }

    private fun setVoice(name: String) {
        val option = availableVoices.find { it.name == name }
        if (option != null && tts.setVoice(option.voice) == TextToSpeech.SUCCESS) {
            selectedVoice = name
            getPreferences(MODE_PRIVATE).edit().putString("voice", name).apply()
            activeBook?.let { updateBook(it.copy(voiceName = name)) }
            if (isPlaying) speakCurrent()
        } else if (name.isNotBlank()) {
            selectedVoice = ""
            status = "That voice is unavailable on this device. Using the system default."
        }
    }

    private fun useDefaultVoice() {
        selectedVoice = ""
        getPreferences(MODE_PRIVATE).edit().remove("voice").apply()
        activeBook?.let { updateBook(it.copy(voiceName = "")) }
        val default = tts.defaultVoice
        if (default != null) tts.setVoice(default)
        if (isPlaying) speakCurrent()
    }

    private fun reportSpeechError(utteranceId: String?, code: Int) {
        runOnUiThread {
            if (utteranceId != activeUtterance) return@runOnUiThread
            isPlaying = false
            status = when (code) {
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Voice model not installed. Download it in your speech engine."
                TextToSpeech.ERROR_OUTPUT -> "Audio output failed. Check media volume and audio routing."
                TextToSpeech.ERROR_NETWORK -> "Speech engine requested a network connection."
                else -> "Speech engine error $code. Try a voice test in VoxSherpa."
            }
        }
    }

    private fun testVoice() {
        if (!ttsReady) return
        isPlaying = false
        activeUtterance = "test_${++utteranceNumber}"
        tts.setSpeechRate(1f)
        status = "Starting voice test…"
        if (tts.speak("Hello. This is a test of your selected voice.", TextToSpeech.QUEUE_FLUSH, null, activeUtterance) == TextToSpeech.ERROR) {
            status = "The speech engine rejected the voice test."
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBook(uri)
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(if (screen == "reader") activeBook?.title ?: "Reader" else if (screen == "voice") "Voice" else "Plain Voice Reader", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                if (screen != "library") TextButton(onClick = { if (screen == "reader") stopPlayback(); screen = if (screen == "voice") voiceReturnScreen else "library" }) { Text("Back") }
            }, actions = {
                if (screen == "reader") TextButton(onClick = { voiceReturnScreen = screen; screen = "voice" }) { Text("Voice") }
                if (screen == "library") TextButton(onClick = { voiceReturnScreen = screen; screen = "voice" }) { Text("Voice") }
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                when (screen) {
                    "reader" -> ReaderPage()
                    "voice" -> VoicePage()
                    else -> LibraryPage { picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "text/markdown", "application/octet-stream", "*/*")) }
                }
            }
        }
    }

    @Composable
    private fun LibraryPage(onImport: () -> Unit) {
        val recent = library.maxByOrNull { it.lastRead }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("Your reading room", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your books stay here, even when the originals move.", color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("+  Add book") }
                if (status != "Ready") Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (recent != null) item {
                Text("Continue reading", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                BookCard(recent, featured = true) { openBook(recent) }
            }
            item { Text("Library · ${library.size} ${if (library.size == 1) "book" else "books"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (library.isEmpty()) item { Text("Add an EPUB, PDF, MOBI, or text file to get started.") }
            items(library, key = { it.id }) { book -> BookCard(book) { openBook(book) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun BookCard(book: LibraryBook, featured: Boolean = false, onOpen: () -> Unit) {
        val cover = remember(book.coverFile) { store.cover(book)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (featured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cover != null) Image(cover, contentDescription = "Cover of ${book.title}", modifier = Modifier.size(width = 66.dp, height = 92.dp))
                else Box(Modifier.size(width = 66.dp, height = 92.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("READ", color = Color.White, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                    Text("${book.progress}% complete · Paragraph ${book.position + 1}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { book.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    if (book.voiceName.isNotBlank()) Text("Voice ${book.voiceName}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (book.lastRead > 0) Text("Last read ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.lastRead))}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    @Composable
    private fun ReaderPage() {
        val book = activeBook ?: return
        val listState = rememberLazyListState()
        var showSections by remember(book.id) { mutableStateOf(false) }
        var showBookmarks by remember(book.id) { mutableStateOf(false) }
        var showDelete by remember(book.id) { mutableStateOf(false) }
        LaunchedEffect(currentIndex, paragraphs.size) { if (currentIndex in paragraphs.indices) listState.animateScrollToItem(currentIndex) }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${book.progress}% complete", style = MaterialTheme.typography.labelLarge)
                Text("${currentIndex + 1} / ${paragraphs.size}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { book.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { previousParagraph() }, modifier = Modifier.weight(1f)) { Text("Previous") }
                Button(onClick = { togglePlayback() }, enabled = ttsReady && paragraphs.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(if (isPlaying) "Pause" else "Play") }
                OutlinedButton(onClick = { nextParagraph() }, modifier = Modifier.weight(1f)) { Text("Next") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { changeSpeed(speed - .05f) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text("${"%.2f".format(Locale.US, speed)}×")
                Slider(value = speed, onValueChange = { changeSpeed(it, restart = false) }, onValueChangeFinished = { if (isPlaying) speakCurrent() }, valueRange = 1f..3f, steps = 39, modifier = Modifier.weight(1f))
                IconButton(onClick = { changeSpeed(speed + .05f) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(selected = showSections, onClick = { showSections = !showSections; showBookmarks = false }, label = { Text("Sections") })
                FilterChip(selected = showBookmarks, onClick = { showBookmarks = !showBookmarks; showSections = false }, label = { Text("Bookmarks") })
                TextButton(onClick = { toggleBookmark() }) { Text(if (currentIndex in book.bookmarks) "★ Saved" else "☆ Save") }
                TextButton(onClick = { showDelete = !showDelete }) { Text("Remove") }
            }
            if (showDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remove this book?", modifier = Modifier.weight(1f))
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
                TextButton(onClick = { stopPlayback(); store.delete(book); refreshLibrary(); activeBook = null; screen = "library" }) { Text("Remove") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            if (showSections || showBookmarks) {
                val destinations = if (showSections) chapters.toList() else book.bookmarks.sorted().map { Chapter("Paragraph ${it + 1}", it) }
                if (destinations.isEmpty()) Text(if (showSections) "No sections were detected in this file." else "No bookmarks yet.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(destinations) { chapter ->
                        TextButton(onClick = { jumpTo(chapter.index); showSections = false; showBookmarks = false }) {
                            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    itemsIndexed(paragraphs) { index, paragraph ->
                        val isHeading = chapters.any { it.index == index }
                        Card(modifier = Modifier.fillMaxWidth().clickable { jumpTo(index) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Text(paragraph, style = if (isHeading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VoicePage() {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Installed voices", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Choose an offline voice already installed on this device. Voice quality depends on your Android speech engine.")
                Text("Kokoro can take a few seconds to prepare a sentence. Piper is usually faster.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { useDefaultVoice() }) { Text(if (selectedVoice.isEmpty()) "✓ System default" else "System default") }
                Button(onClick = { testVoice() }, enabled = ttsReady) { Text("Test voice") }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(availableVoices) { _, option ->
                Card(Modifier.fillMaxWidth().clickable { setVoice(option.name) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${if (selectedVoice == option.name) "✓ " else ""}${option.label} · ${option.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Offline · ${if (option.quality >= Voice.QUALITY_HIGH) "High quality" else "Standard quality"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("For a free offline neural voice on Android 11+, install VoxSherpa TTS, download a Kokoro model in that app, then select VoxSherpa as your default Android speech engine. Reopen Plain Voice Reader to see its voices here.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun importBook(uri: Uri) {
        stopPlayback()
        status = "Importing book…"
        lifecycleScope.launch {
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "Book"
                val pair = withContext(Dispatchers.IO) { store.import(uri, name) }
                refreshLibrary()
                displayBook(pair.first, pair.second)
            } catch (error: Exception) { status = error.message ?: "Could not import this book." }
        }
    }

    private fun openBook(book: LibraryBook) {
        stopPlayback()
        status = "Opening book…"
        lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { BookExtractor.extract(this@MainActivity, Uri.fromFile(store.file(book)), book.fileName) }
                displayBook(book, parsed)
            } catch (error: Exception) { status = error.message ?: "Could not open this book." }
        }
    }

    private fun displayBook(book: LibraryBook, parsed: ReadableBook) {
        paragraphs.clear(); paragraphs.addAll(parsed.paragraphs)
        chapters.clear(); chapters.addAll(parsed.chapters)
        currentIndex = book.position.coerceIn(0, paragraphs.lastIndex)
        activeBook = book
        if (book.voiceName.isNotBlank()) setVoice(book.voiceName)
        status = "Ready"
        screen = "reader"
        savePosition()
    }

    private fun updateBook(book: LibraryBook) { store.update(book); activeBook = book; refreshLibrary() }
    private fun refreshLibrary() { library.clear(); library.addAll(store.books()) }
    private fun savePosition() { activeBook?.let { updateBook(it.copy(position = currentIndex, paragraphCount = paragraphs.size, lastRead = System.currentTimeMillis())) } }
    private fun toggleBookmark() { activeBook?.let { book -> updateBook(book.copy(bookmarks = if (currentIndex in book.bookmarks) book.bookmarks - currentIndex else book.bookmarks + currentIndex)) } }
    private fun jumpTo(index: Int) { currentIndex = index.coerceIn(0, paragraphs.lastIndex); savePosition(); if (isPlaying) speakCurrent() }
    private fun previousParagraph() { if (paragraphs.isNotEmpty()) jumpTo(currentIndex - 1) }
    private fun nextParagraph() { if (paragraphs.isNotEmpty()) jumpTo(currentIndex + 1) }
    private fun changeSpeed(value: Float, restart: Boolean = true) {
        speed = (value.coerceIn(1f, 3f) * 20).roundToInt() / 20f
        getPreferences(MODE_PRIVATE).edit().putFloat("speed", speed).apply()
        if (ttsReady) tts.setSpeechRate(speed)
        if (restart && isPlaying) speakCurrent()
    }
    private fun nextSpeakable(start: Int): Int {
        val headings = chapters.map { it.index }.toSet()
        var index = start
        while (index < paragraphs.size && index in headings) index++
        return index
    }
    private fun togglePlayback() {
        if (isPlaying) stopPlayback() else { isPlaying = true; speakCurrent() }
    }
    private fun stopPlayback() {
        isPlaying = false
        activeUtterance = ""
        if (::tts.isInitialized) tts.stop()
        if (activeBook != null) status = "Paused at paragraph ${currentIndex + 1}."
    }
    private fun speakCurrent() {
        if (!ttsReady || paragraphs.isEmpty()) { isPlaying = false; return }
        val index = nextSpeakable(currentIndex)
        if (index >= paragraphs.size) { isPlaying = false; status = "Finished."; return }
        currentIndex = index
        savePosition()
        speechChunks = TextCleaner.speechChunks(paragraphs[index])
        chunkIndex = 0
        queueCurrentChunk()
    }
    private fun queueCurrentChunk() {
        if (chunkIndex !in speechChunks.indices) { isPlaying = false; return }
        activeUtterance = "p_${++utteranceNumber}"
        tts.setSpeechRate(speed)
        status = "Starting speech…"
        if (tts.speak(speechChunks[chunkIndex], TextToSpeech.QUEUE_FLUSH, null, activeUtterance) == TextToSpeech.ERROR) {
            isPlaying = false; status = "The speech engine rejected this text."
        }
    }
    override fun onDestroy() { if (::tts.isInitialized) { tts.stop(); tts.shutdown() }; super.onDestroy() }
}
