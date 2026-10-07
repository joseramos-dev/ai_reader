package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.feature.reader.generated.resources.Res
import dev.joseramos.aireader.feature.reader.generated.resources.listen_resume
import dev.joseramos.aireader.feature.reader.generated.resources.listen_stop
import dev.joseramos.aireader.feature.reader.generated.resources.player_next
import dev.joseramos.aireader.feature.reader.generated.resources.player_pause
import dev.joseramos.aireader.feature.reader.generated.resources.player_previous
import org.jetbrains.compose.resources.stringResource

/** Lo que se puede hacer mientras se escucha el libro (sonando o en pausa). */
class ListeningActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onPreviousPhrase: () -> Unit,
    val onNextPhrase: () -> Unit,
    val onStop: () -> Unit
)

/** Alto de la barra de controles de la voz, para reservarle sitio al final del contenido. */
internal val ListeningBarHeight = 56.dp

/**
 * Barra de transporte de la lectura en voz alta, como la de un reproductor de iOS:
 * ⏪ frase anterior · ⏯ pausar/reanudar · ⏩ frase siguiente · ⏹ detener. Solo se muestra
 * mientras se está escuchando.
 */
@Composable
internal fun ListeningBar(isPlaying: Boolean, actions: ListeningActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(ListeningBarHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarIconButton(Icons.Rounded.FastRewind, stringResource(Res.string.player_previous), actions.onPreviousPhrase)
        PlayPauseButton(isPlaying, onClick = if (isPlaying) actions.onPause else actions.onResume)
        BarIconButton(Icons.Rounded.FastForward, stringResource(Res.string.player_next), actions.onNextPhrase)
        BarIconButton(Icons.Rounded.Stop, stringResource(Res.string.listen_stop), actions.onStop)
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(PLAY_BUTTON_SIZE)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (isPlaying) Res.string.player_pause else Res.string.listen_resume),
            tint = colors.onAccent,
            modifier = Modifier.size(PLAY_ICON_SIZE)
        )
    }
}

private val PLAY_BUTTON_SIZE = 48.dp
private val PLAY_ICON_SIZE = 30.dp
