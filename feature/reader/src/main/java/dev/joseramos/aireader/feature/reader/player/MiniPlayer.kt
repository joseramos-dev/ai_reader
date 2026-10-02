package dev.joseramos.aireader.feature.reader.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.joseramos.aireader.core.data.settings.AppSettings
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.BarIconButton
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Radius
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.R
import dev.joseramos.aireader.tts.PlaybackState
import dev.joseramos.aireader.tts.SleepTimer
import java.io.File

/** Alto del mini reproductor, para que las pantallas reserven el espacio. */
val MiniPlayerHeight = 64.dp

/**
 * Mini reproductor flotante (como en Apple Music) para las pantallas de pestañas. Tocarlo abre el
 * libro; el botón de la portada abre el reproductor ampliado.
 */
@Composable
fun MiniPlayer(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val bookId = state.bookId ?: return
    if (!state.isActive) return
    val colors = AppTheme.colors

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs)
            .height(MiniPlayerHeight - Spacing.xs)
            .shadow(8.dp, RoundedCornerShape(Radius.card))
            .clip(RoundedCornerShape(Radius.card))
            .background(colors.surfaceElevated)
            .clickable(role = Role.Button) { onOpenBook(bookId) }
            .padding(horizontal = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Cover(state, Modifier.size(40.dp).clickable(role = Role.Button) { expanded = true })
        Column(Modifier.weight(1f)) {
            Text(
                state.bookTitle,
                style = AppTheme.typography.subheadline,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                state.phrase ?: state.chapterTitle.orEmpty(),
                style = AppTheme.typography.caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BarIconButton(
            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
            viewModel::togglePlay
        )
    }

    if (expanded) PlayerSheet(state, viewModel, onDismiss = { expanded = false })
}

@Composable
private fun Cover(state: PlaybackState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(AppTheme.colors.accentFill)) {
        state.coverPath?.let { AsyncImage(File(it), contentDescription = null, contentScale = ContentScale.Crop) }
    }
}

/** Reproductor ampliado: frase actual, saltos, velocidad y temporizador de apagado. */
@Composable
fun PlayerSheet(state: PlaybackState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var speed by remember { mutableFloatStateOf(state.speed) }
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                Cover(state, Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.bookTitle, style = AppTheme.typography.headline, color = colors.label, maxLines = 2)
                    state.chapterTitle?.let {
                        Text(it, style = AppTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 1)
                    }
                }
            }
            Text(
                state.phrase.orEmpty(),
                style = AppTheme.typography.title,
                color = colors.label,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarIconButton(
                    Icons.Rounded.SkipPrevious,
                    stringResource(R.string.player_previous_chapter),
                    viewModel::previousChapter
                )
                BarIconButton(Icons.Rounded.Replay10, stringResource(R.string.player_previous), viewModel::previous)
                Box(
                    Modifier.size(
                        64.dp
                    ).clip(
                        RoundedCornerShape(50)
                    ).background(colors.accent).clickable(role = Role.Button, onClick = viewModel::togglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play
                        ),
                        tint = colors.onAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }
                BarIconButton(Icons.Rounded.Forward10, stringResource(R.string.player_next), viewModel::next)
                BarIconButton(
                    Icons.Rounded.SkipNext,
                    stringResource(R.string.player_next_chapter),
                    viewModel::nextChapter
                )
            }
            Text(
                stringResource(R.string.player_speed, "%.2f".format(speed).trimEnd('0').trimEnd('.', ',')),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel
            )
            Slider(
                value = speed,
                onValueChange = { speed = (it * 4).let(Math::round) / 4f },
                onValueChangeFinished = { viewModel.setSpeed(speed) },
                valueRange = AppSettings.MIN_SPEED..AppSettings.MAX_SPEED,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.fill
                )
            )
            Text(
                stringResource(R.string.player_sleep),
                style = AppTheme.typography.footnote,
                color = colors.secondaryLabel
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val timer = state.sleepTimer
                SleepChip(stringResource(R.string.player_sleep_off), timer == SleepTimer.Off) {
                    viewModel.setSleepTimer(SleepTimer.Off)
                }
                listOf(15, 30, 60).forEach { minutes ->
                    SleepChip(
                        stringResource(R.string.player_sleep_minutes, minutes),
                        (timer as? SleepTimer.Minutes)?.minutes == minutes
                    ) {
                        viewModel.setSleepTimer(SleepTimer.Minutes(minutes, 0))
                    }
                }
                SleepChip(stringResource(R.string.player_sleep_chapter), timer == SleepTimer.EndOfChapter) {
                    viewModel.setSleepTimer(SleepTimer.EndOfChapter)
                }
            }
            PlainButton(
                stringResource(R.string.player_stop),
                {
                    viewModel.stop()
                    onDismiss()
                },
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Close
            )
        }
    }
}

@Composable
private fun SleepChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTheme.typography.footnote) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppTheme.colors.accentFill,
            selectedLabelColor = AppTheme.colors.accentText
        )
    )
}
