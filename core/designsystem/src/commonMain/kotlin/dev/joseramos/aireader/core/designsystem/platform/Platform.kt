package dev.joseramos.aireader.core.designsystem.platform

import androidx.compose.runtime.Composable
import dev.joseramos.aireader.core.common.PickedFile

/** Abre el selector de archivos del sistema (el diálogo del sistema en Android y el de Windows en escritorio). */
class FilePicker internal constructor(private val onLaunch: () -> Unit) {
    fun launch() = onLaunch()
}

/** Recuerda un [FilePicker] que ofrece archivos de [mimeTypes] (`application/pdf`…) y entrega el elegido. */
@Composable
expect fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): FilePicker

/** Intercepta el gesto o botón de atrás mientras [enabled]. En Windows no hay gesto de atrás: no hace nada. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * Devuelve una acción que antes de ejecutar [action] pide el permiso de notificaciones si hace falta (Android 13+,
 * para los controles de la lectura en voz alta). En Windows ejecuta [action] directamente.
 */
@Composable
expect fun <T> rememberWithNotificationPermission(action: (T) -> Unit): (T) -> Unit
