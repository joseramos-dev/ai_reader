package dev.joseramos.aireader.feature.reader.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.joseramos.aireader.tts.PlaybackController
import dev.joseramos.aireader.tts.PlaybackState
import dev.joseramos.aireader.tts.SleepTimer
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Mini reproductor y reproductor ampliado, visibles fuera del lector mientras hay un libro sonando. */
@HiltViewModel
class PlayerViewModel @Inject constructor(private val controller: PlaybackController) : ViewModel() {
    val state: StateFlow<PlaybackState> = controller.state

    fun togglePlay() {
        if (state.value.isPlaying) controller.pause() else viewModelScope.launch { controller.resume() }
    }

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun nextChapter() = controller.nextChapter()

    fun previousChapter() = controller.previousChapter()

    fun stop() = controller.stop()

    fun setSpeed(speed: Float) {
        viewModelScope.launch { controller.setSpeed(speed) }
    }

    fun setSleepTimer(timer: SleepTimer) = controller.setSleepTimer(timer)
}
