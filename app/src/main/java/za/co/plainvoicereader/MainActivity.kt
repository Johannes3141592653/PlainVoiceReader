package za.co.plainvoicereader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private val paragraphs = mutableStateListOf<String>()
    private var currentIndex by mutableIntStateOf(0)
    private var speed by mutableFloatStateOf(1.0f)
    private var isPlaying by mutableStateOf(false)
    private var ttsReady by mutableStateOf(false)
    private var status by mutableStateOf("Open an EPUB, MOBI, PDF, or TXT file.")
    private var bookName by mutableStateOf("Plain Voice Reader")
    private var bookKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speed = getPreferences(MODE_PRIVATE).getFloat("speed", 1.0f)
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isPlaying = false
                    status = "The speech engine reported an error."
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (!isPlaying) return@runOnUiThread
                    if (currentIndex < paragraphs.lastIndex) {
                        currentIndex++
                        savePosition()
                        speakCurrent()
                    } else {
                        isPlaying = false
                        status = "Finished."
                    }
                }
            }
        })

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen()
                }
            }
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setSpeechRate(speed)
            ttsReady = true
            if (paragraphs.isNotEmpty()) status = "Ready"
        } else {
            status = "Android Text-to-Speech could not initialise."
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReaderScreen() {
        val listState = rememberLazyListState()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openBook(uri)
        }

        LaunchedEffect(currentIndex, paragraphs.size) {
            if (paragraphs.isNotEmpty() && currentIndex in paragraphs.indices) {
                listState.animateScrollToItem(currentIndex)
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text(bookName) }) }
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open") }
                    Button(
                        enabled = paragraphs.isNotEmpty(),
                        onClick = { previousParagraph() }
                    ) { Text("Previous") }
                    Button(
                        enabled = paragraphs.isNotEmpty() && ttsReady,
                        onClick = { togglePlayback() }
                    ) { Text(if (isPlaying) "Pause" else "Play") }
                    Button(
                        enabled = paragraphs.isNotEmpty(),
                        onClick = { nextParagraph() }
                    ) { Text("Next") }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speed ${"%.2f".format(speed)}×", modifier = Modifier.padding(end = 12.dp))
                    Slider(
                        value = speed,
                        onValueChange = {
                            speed = it
                            if (ttsReady) tts.setSpeechRate(speed)
                        },
                        onValueChangeFinished = {
                            getPreferences(MODE_PRIVATE).edit().putFloat("speed", speed).apply()
                            if (isPlaying) speakCurrent()
                        },
                        valueRange = 0.5f..2.5f,
                        steps = 7,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = if (paragraphs.isEmpty()) status else "$status  •  Paragraph ${currentIndex + 1} of ${paragraphs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                if (paragraphs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Open a book or document to begin.", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(paragraphs) { index, paragraph ->
                            val active = index == currentIndex
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentIndex = index
                                        savePosition()
                                        if (isPlaying) speakCurrent()
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openBook(uri: Uri) {
        isPlaying = false
        if (::tts.isInitialized) tts.stop()
        status = "Reading file…"
        val name = displayName(uri) ?: "Book"
        bookName = name
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some providers grant temporary access only; that is still enough for the current session.
        }

        lifecycleScope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) { BookExtractor.extract(this@MainActivity, uri, name) }
                paragraphs.clear()
                paragraphs.addAll(extracted)
                bookKey = sha256(uri.toString() + "|" + name)
                val saved = getPreferences(MODE_PRIVATE).getInt("pos_${bookKey}", 0)
                currentIndex = saved.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                status = "Ready"
            } catch (e: Exception) {
                paragraphs.clear()
                currentIndex = 0
                status = e.message ?: "Could not read this file."
            }
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            tts.stop()
            isPlaying = false
            status = "Paused at paragraph ${currentIndex + 1}."
        } else {
            isPlaying = true
            status = "Reading"
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        if (!ttsReady || paragraphs.isEmpty() || currentIndex !in paragraphs.indices) return
        tts.stop()
        tts.setSpeechRate(speed)
        val id = "paragraph_$currentIndex"
        val result = tts.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            isPlaying = false
            status = "Could not start speech."
        }
    }

    private fun previousParagraph() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        savePosition()
        if (isPlaying) speakCurrent()
    }

    private fun nextParagraph() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(paragraphs.lastIndex)
        savePosition()
        if (isPlaying) speakCurrent()
    }

    private fun savePosition() {
        val key = bookKey ?: return
        getPreferences(MODE_PRIVATE).edit().putInt("pos_$key", currentIndex).apply()
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
