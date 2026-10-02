package dev.joseramos.aireader.spikes.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.Memory
import dev.joseramos.aireader.spikes.common.SpikeHeader
import dev.joseramos.aireader.spikes.common.SpikeLog
import dev.joseramos.aireader.spikes.common.withPeakPss
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SPIKE = "4-pdf"

@Composable
fun PdfSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var sortByPosition by remember { mutableStateOf(false) }
    var previewPage by remember { mutableStateOf("1") }
    val pages = remember { mutableStateListOf<String>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        running = true
        scope.launch {
            runCatching { extract(context, uri, sortByPosition) }
                .onSuccess {
                    pages.clear()
                    pages.addAll(it)
                }
                .onFailure { SpikeLog.log(SPIKE, "ERROR: $it") }
            running = false
        }
    }

    SpikeHeader(
        "4 · Extracción de texto con PdfBox-Android",
        "orden de lectura correcto en 3 PDFs reales (novela, ensayo y técnico a dos columnas)."
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(sortByPosition, { sortByPosition = it })
        Text("Ordenar por posición (sortByPosition)")
    }
    Button(enabled = !running, onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Elegir PDF y extraer") }
    if (running) Text("Extrayendo…")

    if (pages.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ver página")
            OutlinedTextField(previewPage, { previewPage = it.filter(Char::isDigit) }, singleLine = true)
        }
        val index = (previewPage.toIntOrNull() ?: 1).coerceIn(1, pages.size) - 1
        Text(pages[index].ifBlank { "(página sin texto)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    LogView(SPIKE)
}

private suspend fun extract(context: Context, uri: Uri, sortByPosition: Boolean): List<String> =
    withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: "documento.pdf"
        val copy = File(context.cacheDir, "spike.pdf")
        context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        val pssBefore = Memory.pssMb()
        SpikeLog.log(
            SPIKE,
            "== $name · ${copy.length() / 1024} KB · sortByPosition=$sortByPosition · PSS $pssBefore MB"
        )

        val texts = mutableListOf<String>()
        val (_, peak) = withPeakPss(this) {
            lateinit var document: PDDocument
            val loadMs = measureTimeMillis { document = PDDocument.load(copy) }
            document.use { doc ->
                SpikeLog.log(SPIKE, "Carga: $loadMs ms · ${doc.numberOfPages} páginas")
                logOutline(doc)
                val stripper = PDFTextStripper().apply { this.sortByPosition = sortByPosition }
                val times = mutableListOf<Long>()
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    var text = ""
                    times += measureTimeMillis { text = stripper.getText(doc) }
                    texts += text
                }
                val empty = texts.indices.filter { texts[it].trim().length < 20 }.map { it + 1 }
                val slowest = times.indices.maxBy { times[it] }
                SpikeLog.log(
                    SPIKE,
                    "Extracción: ${times.sum()} ms en total · %.1f ms/página · más lenta p.${slowest + 1} (${times[slowest]} ms)"
                        .format(times.average())
                )
                SpikeLog.log(
                    SPIKE,
                    "Caracteres: ${texts.sumOf {
                        it.length
                    }} · páginas casi vacías: ${empty.size} ${empty.take(15)}"
                )
            }
        }
        SpikeLog.log(SPIKE, "PSS pico $peak MB (+${peak - pssBefore} MB)")

        val out = File(SpikeLog.file.parentFile, "pdf-${name.substringBeforeLast('.')}-sort$sortByPosition.txt")
        out.writeText(texts.mapIndexed { i, t -> "=== Página ${i + 1} ===\n$t" }.joinToString("\n"))
        SpikeLog.log(SPIKE, "Texto completo guardado en ${out.absolutePath}")
        texts
    }

private fun logOutline(doc: PDDocument) {
    val outline = doc.documentCatalog.documentOutline
    if (outline == null) {
        SpikeLog.log(SPIKE, "Índice (outline): no tiene")
        return
    }
    val entries = mutableListOf<String>()
    fun walk(node: PDOutlineNode, depth: Int) {
        for (item in node.children()) {
            val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) + 1 } }.getOrNull()
            entries += "${"  ".repeat(depth)}${item.title} → p.${page ?: "?"}"
            walk(item, depth + 1)
        }
    }
    walk(outline, 0)
    SpikeLog.log(SPIKE, "Índice (outline): ${entries.size} entradas")
    entries.take(20).forEach { SpikeLog.log(SPIKE, "  $it") }
}
