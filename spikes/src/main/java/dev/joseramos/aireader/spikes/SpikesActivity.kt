package dev.joseramos.aireader.spikes

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.spikes.common.SpikeLog
import dev.joseramos.aireader.spikes.embeddings.EmbeddingSpikeScreen
import dev.joseramos.aireader.spikes.gemini.GeminiSpikeScreen
import dev.joseramos.aireader.spikes.pdf.PdfSpikeScreen
import dev.joseramos.aireader.spikes.playback.PlaybackSpikeScreen
import dev.joseramos.aireader.spikes.tokenizer.TokenizerSpikeScreen
import dev.joseramos.aireader.spikes.tts.TtsSpikeScreen

private val spikes = listOf(
    "1 · TTS en el dispositivo (Piper)",
    "2 · Embeddings ONNX (e5-small / EmbeddingGemma)",
    "3 · Tokenizador en Kotlin",
    "4 · Extracción de texto con PdfBox",
    "5 · Audio en segundo plano (Media3)",
    "6 · Streaming de la API de Gemini"
)

class SpikesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var current by rememberSaveable { mutableStateOf(-1) }
                    BackHandler(enabled = current >= 0) { current = -1 }
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (current) {
                            0 -> TtsSpikeScreen()
                            1 -> EmbeddingSpikeScreen()
                            2 -> TokenizerSpikeScreen()
                            3 -> PdfSpikeScreen()
                            4 -> PlaybackSpikeScreen()
                            5 -> GeminiSpikeScreen()
                            else -> Home(onOpen = { current = it })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    Text("AI Reader · Spikes F1", style = MaterialTheme.typography.headlineSmall)
    Text(SpikeLog.deviceInfo, style = MaterialTheme.typography.bodySmall)
    spikes.forEachIndexed { i, title ->
        Card(onClick = { onOpen(i) }, modifier = Modifier.fillMaxWidth()) {
            Text(title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
    Text("Resultados: ${SpikeLog.file.absolutePath}", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Resultados spikes", SpikeLog.file.readText()))
        Toast.makeText(context, "Resultados copiados", Toast.LENGTH_SHORT).show()
    }) { Text("Copiar todos los resultados") }
}
