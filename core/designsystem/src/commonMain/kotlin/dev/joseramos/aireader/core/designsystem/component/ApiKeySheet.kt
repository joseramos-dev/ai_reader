package dev.joseramos.aireader.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.joseramos.aireader.core.designsystem.generated.resources.Res
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_help
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_hint
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_paste
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_remove
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_save
import dev.joseramos.aireader.core.designsystem.generated.resources.api_key_title
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * Hoja para introducir la clave de API de Gemini. Se usa en Ajustes y también donde una función de
 * IA la necesita (repaso, chat, personajes), para no tener que salir del libro: al guardar, quien
 * la abre puede reintentar lo que estaba haciendo.
 *
 * @param onRemove si no es `null` (ya hay clave), muestra la opción de eliminarla.
 */
@Suppress("DEPRECATION") // LocalClipboard (nuevo) es suspendible; para leer texto plano basta el clásico.
@Composable
fun ApiKeySheet(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    var key by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.api_key_title)) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text(stringResource(Res.string.api_key_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    PlainButton(stringResource(Res.string.api_key_paste), {
                        clipboard.getText()?.text?.trim()?.let { key = it }
                    })
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(Res.string.api_key_help),
                style = AppTheme.typography.footnote,
                color = AppTheme.colors.secondaryLabel
            )
            PrimaryButton(
                text = stringResource(Res.string.api_key_save),
                enabled = key.isNotBlank(),
                onClick = {
                    onSave(key.trim())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (onRemove != null) {
                PlainButton(
                    text = stringResource(Res.string.api_key_remove),
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
