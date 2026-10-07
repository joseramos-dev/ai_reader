package dev.joseramos.aireader.core.designsystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.joseramos.aireader.core.common.FilePickedFile
import dev.joseramos.aireader.core.common.PickedFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Diálogo de archivos de Windows (AWT). Se abre desde un hilo aparte: `FileDialog.isVisible = true` bloquea hasta
 * que se cierra, y no debe parar la interfaz. Con varios tipos, el filtro se hace por extensión (`application/pdf`
 * → `.pdf`; otros tipos, como el JSON de evaluación, también por su extensión).
 */
@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): FilePicker {
    val currentOnPicked = rememberUpdatedState(onPicked)
    val extensions = remember(mimeTypes) { mimeTypes.mapNotNull(::extensionOf) }
    return remember(extensions) {
        FilePicker {
            Thread {
                val dialog = FileDialog(null as Frame?, "Elegir archivo", FileDialog.LOAD)
                if (extensions.isNotEmpty()) {
                    dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(it, ignoreCase = true) } }
                }
                dialog.isVisible = true
                val file = dialog.file?.let { File(dialog.directory, it) }
                dialog.dispose()
                if (file != null) currentOnPicked.value(FilePickedFile(file))
            }.start()
        }
    }
}

private fun extensionOf(mimeType: String): String? = when (mimeType) {
    "application/pdf" -> ".pdf"
    "application/json" -> ".json"
    "text/plain" -> ".txt"
    else -> null
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun <T> rememberWithNotificationPermission(action: (T) -> Unit): (T) -> Unit = action
