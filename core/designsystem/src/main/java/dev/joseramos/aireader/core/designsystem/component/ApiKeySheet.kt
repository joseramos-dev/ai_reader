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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.joseramos.aireader.core.designsystem.R
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.Spacing

/**
 * Hoja para introducir la clave de API. Se usa en Ajustes y también donde una función de IA la
 * necesita (resumen, chat, personajes), para no tener que salir del libro: al guardar, quien la
 * abre puede reintentar lo que estaba haciendo.
 *
 * @param onRemove si no es `null` (ya hay clave), muestra la opción de eliminarla.
 */
@Suppress("DEPRECATION") // LocalClipboard (nuevo) es suspendible; para leer texto plano basta el clásico.
@Composable
fun ApiKeySheet(onSave: (String) -> Unit, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null) {
    var key by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.api_key_title)) {
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text(stringResource(R.string.api_key_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    PlainButton(stringResource(R.string.api_key_paste), {
                        clipboard.getText()?.text?.trim()?.let { key = it }
                    })
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.api_key_help),
                style = AppTheme.typography.footnote,
                color = AppTheme.colors.secondaryLabel
            )
            PrimaryButton(
                text = stringResource(R.string.api_key_save),
                enabled = key.isNotBlank(),
                onClick = {
                    onSave(key.trim())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (onRemove != null) {
                PlainButton(
                    text = stringResource(R.string.api_key_remove),
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
