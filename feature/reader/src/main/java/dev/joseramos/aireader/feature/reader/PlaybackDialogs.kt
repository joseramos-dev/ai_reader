package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.tts.PlaybackError
import dev.joseramos.aireader.tts.SystemVoiceSettings

/**
 * Hoja que aparece si no se puede empezar a leer: al móvil le falta la voz del idioma del libro (o
 * no tiene motor de voz), o el texto del libro aún no está listo. La voz se instala desde el
 * sistema; al volver, «Reintentar» empieza a leer.
 */
@Composable
internal fun PlaybackProblemSheet(
    error: PlaybackError,
    language: Language,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val languageName = stringResource(
        if (language == Language.ENGLISH) R.string.reader_language_english else R.string.reader_language_spanish
    )
    val (title, message) = when (error) {
        PlaybackError.VOICE_MISSING ->
            stringResource(R.string.reader_voice_title) to stringResource(R.string.reader_voice_message, languageName)
        PlaybackError.NO_ENGINE ->
            stringResource(R.string.reader_no_engine_title) to stringResource(R.string.reader_no_engine_message)
        PlaybackError.TEXT_NOT_READY ->
            stringResource(R.string.reader_text_not_ready_title) to
                stringResource(R.string.reader_text_not_ready_message)
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = title) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(message, style = AppTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel)
            when (error) {
                PlaybackError.VOICE_MISSING -> PrimaryButton(
                    stringResource(R.string.reader_voice_install),
                    { SystemVoiceSettings.installVoice(context) },
                    Modifier.fillMaxWidth()
                )
                PlaybackError.NO_ENGINE -> PrimaryButton(
                    stringResource(R.string.reader_voice_settings),
                    { SystemVoiceSettings.openSettings(context) },
                    Modifier.fillMaxWidth()
                )
                PlaybackError.TEXT_NOT_READY -> Unit
            }
            if (error != PlaybackError.TEXT_NOT_READY) {
                PlainButton(stringResource(R.string.reader_voice_retry), onRetry, Modifier.fillMaxWidth())
            }
            PlainButton(stringResource(R.string.reader_cancel), onDismiss, Modifier.fillMaxWidth())
        }
    }
}
