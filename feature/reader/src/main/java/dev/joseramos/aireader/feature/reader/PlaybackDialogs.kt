package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.joseramos.aireader.ai.models.ModelCatalog
import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.tts.PlaybackError

/**
 * Hoja que aparece si no se puede empezar a leer: falta descargar la voz o el texto del libro
 * aún no está listo. Al terminar de descargarse la voz, empieza a leer sola.
 */
@Composable
internal fun PlaybackProblemSheet(
    error: PlaybackError,
    voice: ModelState,
    onDownloadVoice: () -> Unit,
    onVoiceReady: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(voice) {
        if (error == PlaybackError.VOICE_MISSING && voice is ModelState.Installed) onVoiceReady()
    }
    val title = stringResource(
        if (error ==
            PlaybackError.VOICE_MISSING
        ) {
            R.string.reader_voice_title
        } else {
            R.string.reader_text_not_ready_title
        }
    )
    AppBottomSheet(onDismissRequest = onDismiss, title = title) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val message = if (error == PlaybackError.VOICE_MISSING) {
                stringResource(
                    R.string.reader_voice_message,
                    (ModelCatalog.piperVoice.sizeBytes / BYTES_PER_MB).toInt()
                )
            } else {
                stringResource(R.string.reader_text_not_ready_message)
            }
            Text(message, style = AppTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel)
            if (error == PlaybackError.VOICE_MISSING) {
                when (voice) {
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { voice.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = AppTheme.colors.accent,
                            trackColor = AppTheme.colors.fill
                        )
                    }
                    ModelState.Installing -> Text(
                        stringResource(R.string.reader_voice_installing),
                        style = AppTheme.typography.footnote
                    )
                    is ModelState.Failed -> {
                        Text(voice.message, style = AppTheme.typography.footnote, color = AppTheme.colors.destructive)
                        PrimaryButton(
                            stringResource(R.string.reader_voice_retry),
                            onDownloadVoice,
                            Modifier.fillMaxWidth()
                        )
                    }
                    else -> PrimaryButton(
                        stringResource(R.string.reader_voice_download),
                        onDownloadVoice,
                        Modifier.fillMaxWidth()
                    )
                }
            }
            PlainButton(stringResource(R.string.reader_cancel), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

private const val BYTES_PER_MB = 1_000_000
