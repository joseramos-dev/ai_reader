package dev.joseramos.aireader.spikes.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackSpikeService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, SpikePlayer(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
