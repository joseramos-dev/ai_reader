package dev.joseramos.aireader.spikes.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.os.SystemClock
import dev.joseramos.aireader.spikes.common.AudioOut
import dev.joseramos.aireader.spikes.common.Catalog
import dev.joseramos.aireader.spikes.common.ModelStore
import dev.joseramos.aireader.spikes.common.SampleTexts
import dev.joseramos.aireader.spikes.common.SpikeLog
import dev.joseramos.aireader.spikes.tts.SherpaTts
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val PLAYBACK_SPIKE = "5-playback"

data class PlaybackMetrics(
    val running: Boolean = false,
    val source: String = "-",
    val wallSeconds: Long = 0,
    val audioSeconds: Double = 0.0,
    val chunks: Int = 0,
    val starvations: Int = 0,
    val underruns: Int = 0,
    val screenOffSeconds: Long = 0
)

object PlaybackSpikeState {
    @Volatile var useWakeLock = false

    @Volatile var usePiper = true
    val metrics = MutableStateFlow(PlaybackMetrics())
}

/**
 * Pipeline de la v1 en miniatura: un productor sintetiza frases (Piper si está
 * descargado, si no un tono) en un canal de capacidad 2 y un consumidor las escribe
 * en un AudioTrack. Mide huecos (canal vacío), underruns y tiempo con pantalla apagada.
 */
class AudioEngine(private val context: Context) {
    private var scope: CoroutineScope? = null
    private val paused = MutableStateFlow(false)
    private var track: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        .setOnAudioFocusChangeListener { SpikeLog.log(PLAYBACK_SPIKE, "Cambio de foco de audio: $it") }
        .build()

    fun play() {
        audioManager.requestAudioFocus(focusRequest)
        acquireWakeLock()
        if (scope == null) start() else resume()
    }

    fun pause() {
        paused.value = true
        track?.pause()
        releaseWakeLock()
        SpikeLog.log(PLAYBACK_SPIKE, "Pausa")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
        releaseWakeLock()
        audioManager.abandonAudioFocusRequest(focusRequest)
        PlaybackSpikeState.metrics.update { it.copy(running = false) }
        SpikeLog.log(PLAYBACK_SPIKE, "Detenido · ${summary()}")
    }

    private fun resume() {
        paused.value = false
        track?.play()
        SpikeLog.log(PLAYBACK_SPIKE, "Reanudado")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start() {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        paused.value = false
        val channel = Channel<FloatArray>(capacity = 2)

        // Piper davefx medium genera a 22 050 Hz, igual que el tono de prueba.
        val sampleRate = 22_050
        val piperSpec = Catalog.piperDavefxInt8
        val withPiper = PlaybackSpikeState.usePiper && ModelStore.isReady(context, piperSpec)
        val sourceName = if (withPiper) "Piper davefx int8" else "Tono de prueba"
        PlaybackSpikeState.metrics.value = PlaybackMetrics(running = true, source = sourceName)
        SpikeLog.log(PLAYBACK_SPIKE, "Inicio · fuente: $sourceName · wakelock=${PlaybackSpikeState.useWakeLock}")

        // Productor: síntesis de frases en bucle.
        val sentences = SampleTexts.paragraphs.flatMap { it.split(Regex("(?<=[.?!])\\s+")) }
        newScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            // El modelo se carga aquí y no en el hilo principal (donde llama Media3).
            val piper = if (withPiper) SherpaTts.create(ModelStore.dir(context, piperSpec), numThreads = 2) else null
            check(piper == null || piper.sampleRate() == sampleRate) { "Frecuencia inesperada: ${piper?.sampleRate()}" }
            try {
                var i = 0
                while (isActive) {
                    paused.first { !it }
                    val pcm = piper?.generate(sentences[i % sentences.size], 0, 1.0f)?.samples ?: tone(sampleRate)
                    channel.send(pcm)
                    i++
                }
            } finally {
                piper?.release()
            }
        }

        // Consumidor: escritura bloqueante en el AudioTrack.
        val audioTrack = AudioOut.newTrack(sampleRate).also { track = it }
        audioTrack.play()
        newScope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = channel.tryReceive().getOrNull() ?: run {
                    if (PlaybackSpikeState.metrics.value.chunks > 0) {
                        PlaybackSpikeState.metrics.update { it.copy(starvations = it.starvations + 1) }
                    }
                    channel.receive()
                }
                paused.first { !it }
                audioTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                PlaybackSpikeState.metrics.update {
                    it.copy(
                        chunks = it.chunks + 1,
                        audioSeconds = it.audioSeconds + chunk.size.toDouble() / sampleRate,
                        underruns = audioTrack.underrunCount
                    )
                }
            }
        }

        // Reloj: tiempo real reproduciendo, tiempo con pantalla apagada y registro cada minuto.
        newScope.launch {
            var last = SystemClock.elapsedRealtime()
            var tick = 0
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - last) / 1000
                last = now
                if (!paused.value) {
                    PlaybackSpikeState.metrics.update {
                        it.copy(
                            wallSeconds = it.wallSeconds + elapsed,
                            screenOffSeconds = it.screenOffSeconds + if (power.isInteractive) 0 else elapsed
                        )
                    }
                }
                if (++tick % 60 == 0) SpikeLog.log(PLAYBACK_SPIKE, summary())
            }
        }
    }

    private fun summary(): String {
        val m = PlaybackSpikeState.metrics.value
        val clock = "t=${m.wallSeconds / 60}:%02d · audio %.0f s · desfase %.1f s"
            .format(m.wallSeconds % 60, m.audioSeconds, m.wallSeconds - m.audioSeconds)
        return "$clock · pantalla apagada ${m.screenOffSeconds} s · huecos ${m.starvations} · underruns ${m.underruns}"
    }

    private fun acquireWakeLock() {
        if (!PlaybackSpikeState.useWakeLock || wakeLock?.isHeld == true) return
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aireader:spike-playback").apply {
                acquire(
                    2 * 60 * 60 * 1000L
                )
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** 2,5 s de pitido suave y 0,5 s de silencio. */
    private fun tone(sampleRate: Int): FloatArray {
        val beep = (sampleRate * 2.5).toInt()
        return FloatArray(sampleRate * 3) { i ->
            if (i >=
                beep
            ) {
                0f
            } else {
                (0.15 * sin(2 * PI * 440 * i / sampleRate) * minOf(1.0, (beep - i) / 2000.0)).toFloat()
            }
        }
    }
}
