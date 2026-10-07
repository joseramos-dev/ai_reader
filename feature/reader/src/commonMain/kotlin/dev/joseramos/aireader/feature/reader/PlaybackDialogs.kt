package dev.joseramos.aireader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.joseramos.aireader.core.designsystem.component.AppBottomSheet
import dev.joseramos.aireader.core.designsystem.component.PlainButton
import dev.joseramos.aireader.core.designsystem.component.PrimaryButton
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import dev.joseramos.aireader.feature.reader.generated.resources.Res
import dev.joseramos.aireader.feature.reader.generated.resources.reader_cancel
import dev.joseramos.aireader.feature.reader.generated.resources.reader_language_english
import dev.joseramos.aireader.feature.reader.generated.resources.reader_language_spanish
import dev.joseramos.aireader.feature.reader.generated.resources.reader_no_engine_message
import dev.joseramos.aireader.feature.reader.generated.resources.reader_no_engine_title
import dev.joseramos.aireader.feature.reader.generated.resources.reader_text_not_ready_message
import dev.joseramos.aireader.feature.reader.generated.resources.reader_text_not_ready_title
import dev.joseramos.aireader.feature.reader.generated.resources.reader_voice_install
import dev.joseramos.aireader.feature.reader.generated.resources.reader_voice_message
import dev.joseramos.aireader.feature.reader.generated.resources.reader_voice_retry
import dev.joseramos.aireader.feature.reader.generated.resources.reader_voice_settings
import dev.joseramos.aireader.feature.reader.generated.resources.reader_voice_title
import dev.joseramos.aireader.text.Language
import dev.joseramos.aireader.tts.PlaybackError
import dev.joseramos.aireader.tts.VoiceSettings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

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
    val voiceSettings: VoiceSettings = koinInject()
    val languageName = stringResource(
        if (language == Language.ENGLISH) Res.string.reader_language_english else Res.string.reader_language_spanish
    )
    val (title, message) = when (error) {
        PlaybackError.VOICE_MISSING ->
            stringResource(Res.string.reader_voice_title) to
                stringResource(Res.string.reader_voice_message, languageName)
        PlaybackError.NO_ENGINE ->
            stringResource(Res.string.reader_no_engine_title) to stringResource(Res.string.reader_no_engine_message)
        PlaybackError.TEXT_NOT_READY ->
            stringResource(Res.string.reader_text_not_ready_title) to
                stringResource(Res.string.reader_text_not_ready_message)
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = title) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(message, style = AppTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel)
            when (error) {
                PlaybackError.VOICE_MISSING -> PrimaryButton(
                    stringResource(Res.string.reader_voice_install),
                    { voiceSettings.installVoice() },
                    Modifier.fillMaxWidth()
                )
                PlaybackError.NO_ENGINE -> PrimaryButton(
                    stringResource(Res.string.reader_voice_settings),
                    { voiceSettings.openSettings() },
                    Modifier.fillMaxWidth()
                )
                PlaybackError.TEXT_NOT_READY -> Unit
            }
            if (error != PlaybackError.TEXT_NOT_READY) {
                PlainButton(stringResource(Res.string.reader_voice_retry), onRetry, Modifier.fillMaxWidth())
            }
            PlainButton(stringResource(Res.string.reader_cancel), onDismiss, Modifier.fillMaxWidth())
        }
    }
}
