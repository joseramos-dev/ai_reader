package dev.joseramos.aireader.spikes.playback

import android.Manifest
import android.content.ComponentName
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.joseramos.aireader.spikes.common.Catalog
import dev.joseramos.aireader.spikes.common.LogView
import dev.joseramos.aireader.spikes.common.ModelStore
import dev.joseramos.aireader.spikes.common.SpikeHeader
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

@Composable
fun PlaybackSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var wakeLock by remember { mutableStateOf(PlaybackSpikeState.useWakeLock) }
    var usePiper by remember { mutableStateOf(PlaybackSpikeState.usePiper) }
    val metrics by PlaybackSpikeState.metrics.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackSpikeService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        scope.launch { controller = future.await() }
        onDispose { MediaController.releaseFuture(future) }
    }

    SpikeHeader(
        "5 · Audio en segundo plano (Media3 SimpleBasePlayer + AudioTrack)",
        "30 min con la pantalla apagada sin cortes (incluidos Xiaomi y Samsung)."
    )
    Text(
        "Pulsa Iniciar, bloquea el móvil y déjalo 30 min. Al volver, revisa «huecos» y «desfase». " +
            "Se registra un resumen cada minuto. Prueba sin y con wakelock.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(wakeLock, {
            wakeLock = it
            PlaybackSpikeState.useWakeLock = it
        }, enabled = !metrics.running)
        Text("Mantener la CPU despierta (wakelock parcial)")
    }
    val piperReady = ModelStore.isReady(context, Catalog.piperDavefxInt8)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            usePiper && piperReady,
            {
                usePiper = it
                PlaybackSpikeState.usePiper = it
            },
            enabled =
            piperReady && !metrics.running
        )
        Text(
            if (piperReady) {
                "Sintetizar con Piper (davefx int8)"
            } else {
                "Piper no descargado: se usará un tono (descárgalo en el spike 1)"
            }
        )
    }
    if (Build.VERSION.SDK_INT >= 33) {
        OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Permitir notificaciones (necesario para los controles)")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = controller != null, onClick = {
            controller?.run {
                prepare()
                play()
            }
        }) { Text("Iniciar / reanudar") }
        OutlinedButton(enabled = controller != null, onClick = { controller?.pause() }) { Text("Pausar") }
        OutlinedButton(enabled = controller != null, onClick = { controller?.stop() }) { Text("Detener") }
    }
    Text(
        "Fuente: ${metrics.source} · ${if (metrics.running) "en marcha" else "parado"}\n" +
            "Tiempo: ${metrics.wallSeconds / 60} min ${metrics.wallSeconds % 60} s · " +
            "pantalla apagada ${metrics.screenOffSeconds} s\n" +
            "Audio reproducido: %.0f s · desfase %.1f s\n".format(
                metrics.audioSeconds,
                metrics.wallSeconds - metrics.audioSeconds
            ) +
            "Huecos (canal vacío): ${metrics.starvations} · underruns: ${metrics.underruns} · frases: ${metrics.chunks}",
        style = MaterialTheme.typography.bodyMedium
    )
    LogView(PLAYBACK_SPIKE)
}
