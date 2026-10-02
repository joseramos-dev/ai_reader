package dev.joseramos.aireader.tts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Servicio en primer plano (tipo `mediaPlayback`) que mantiene la lectura con la pantalla
 * apagada y publica la sesión multimedia. Media3 gestiona la notificación.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var engine: PlaybackEngine

    private var session: MediaSession? = null

    /** Pausa al desconectar los auriculares, para no ponerse a hablar por el altavoz. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) engine.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val activity = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, ReaderPlayer(engine)).setSessionActivity(activity).build()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si se cierra la app sin estar leyendo, no hay motivo para seguir vivo.
        if (!engine.state.value.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
