package dev.joseramos.aireader.spikes.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SpikeHeader(title: String, criterion: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text("Criterio de éxito: $criterion", style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun LogView(spike: String) {
    val all by SpikeLog.lines.collectAsState()
    val lines = all[spike].orEmpty()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Registro", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { SpikeLog.clear(spike) }) { Text("Limpiar") }
    }
    lines.asReversed().forEach {
        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

/** Selector de modelo con su botón de descarga. Devuelve el índice elegido. */
@Composable
fun ModelSelector(specs: List<ModelSpec>, spike: String): ModelSpec {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        specs.forEachIndexed { i, spec ->
            val ready = remember(refresh, spec) { ModelStore.isReady(context, spec) }
            Row(
                Modifier.fillMaxWidth().selectable(selected = i == selected, onClick = { selected = i }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = i == selected, onClick = { selected = i })
                Text(
                    "${spec.label} · ${spec.sizeMb} MB" + if (ready) " ✓" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        val spec = specs[selected]
        val current = progress
        if (current != null) {
            LinearProgressIndicator(progress = { current.first }, modifier = Modifier.fillMaxWidth())
            Text("${current.second} · ${(current.first * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        } else if (!ModelStore.isReady(context, spec)) {
            Button(onClick = {
                scope.launch {
                    progress = 0f to "Iniciando"
                    runCatching { ModelStore.download(context, spec) { p, msg -> progress = p to msg } }
                        .onSuccess { SpikeLog.log(spike, "Descargado ${spec.label}") }
                        .onFailure { SpikeLog.log(spike, "Error descargando ${spec.label}: $it") }
                    progress = null
                    refresh++
                }
            }) { Text("Descargar (${spec.sizeMb} MB)") }
        }
    }
    return specs[selected]
}
