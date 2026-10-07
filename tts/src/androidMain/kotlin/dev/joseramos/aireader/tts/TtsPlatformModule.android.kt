package dev.joseramos.aireader.tts

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.joseramos.aireader.core.common.IoDispatcher
import kotlinx.coroutines.guava.await
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Pide y suelta el foco de audio del sistema. */
internal class AndroidAudioFocus(context: Context) : AudioFocusController {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    override var listener: AudioFocusListener? = null

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(speechAudioAttributes)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    override fun request() {
        audioManager.requestAudioFocus(focusRequest)
    }

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> listener?.onLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                listener?.onTransientLoss()
            AudioManager.AUDIOFOCUS_GAIN -> listener?.onGain()
        }
    }
}

/** Wake lock parcial: la lectura sigue con la pantalla apagada. */
internal class AndroidWakeLock(context: Context) : WakeLock {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aireader:read-aloud")
        .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) = wakeLock.acquire(timeoutMs)

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}

/**
 * Conecta un `MediaController` con el [PlaybackService] (lo que lo arranca y lo pasa a primer plano al sonar):
 * las órdenes de reproducir pasan por la sesión multimedia.
 */
internal class MediaSessionBridge(private val context: Context) : PlaybackBridge {
    private var controller: MediaController? = null

    override suspend fun started() {
        connect().play()
    }

    override suspend fun resume() {
        connect().play()
    }

    private suspend fun connect(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().await().also { controller = it }
    }
}

/** Pantallas del sistema para instalar voces o elegir el motor de texto a voz. */
internal class AndroidVoiceSettings(private val context: Context) : VoiceSettings {
    override fun installVoice() {
        if (!open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) openSettings()
    }

    override fun openSettings() {
        if (!open(Intent(TTS_SETTINGS))) open(Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    private fun open(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private companion object {
        const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    }
}

actual val ttsPlatformModule: Module = module {
    single { SystemTtsEngine(get(), get(IoDispatcher)) } bind TtsEngine::class
    single<AudioSinkFactory> { AudioSinkFactory { sampleRate -> AudioTrackSink(sampleRate) } }
    single<AudioFocusController> { AndroidAudioFocus(get()) }
    single<WakeLock> { AndroidWakeLock(get()) }
    single<PlaybackBridge> { MediaSessionBridge(get()) }
    single<VoiceSettings> { AndroidVoiceSettings(get()) }
}
