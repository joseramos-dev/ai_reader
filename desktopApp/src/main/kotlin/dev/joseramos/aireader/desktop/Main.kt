package dev.joseramos.aireader.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.AppInfo
import dev.joseramos.aireader.core.common.FilePickedFile
import dev.joseramos.aireader.shared.AiReaderRoot
import dev.joseramos.aireader.shared.AppStartup
import dev.joseramos.aireader.shared.MainViewModel
import dev.joseramos.aireader.shared.sharedModules
import java.io.File
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Carpetas del usuario en Windows: los libros, la base de datos y los modelos en `%APPDATA%\AIReader`, y lo que se
 * puede regenerar en `%LOCALAPPDATA%\AIReader\cache`.
 */
private fun windowsDirs(): AppDirs {
    val home = System.getProperty("user.home")
    val roaming = System.getenv("APPDATA") ?: "$home/AppData/Roaming"
    val local = System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
    return AppDirs(files = File(roaming, APP_FOLDER), cache = File(local, "$APP_FOLDER/cache"))
}

/** La versión la fija jpackage al empaquetar; al ejecutar desde Gradle es una compilación de desarrollo. */
private fun appInfo(): AppInfo {
    val version = System.getProperty("jpackage.app-version")
    return AppInfo(version = version ?: "desarrollo", debuggable = version == null)
}

fun main(args: Array<String>) {
    val dirs = windowsDirs().also {
        it.files.mkdirs()
        it.cache.mkdirs()
    }
    val desktopModule = module {
        single { dirs }
        single { appInfo() }
    }
    val koin = startKoin { modules(listOf(desktopModule) + sharedModules) }.koin
    koin.get<AppStartup>().start()

    application {
        val windowState = rememberWindowState(size = DpSize(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp))
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "AI Reader") {
            val viewModel: MainViewModel = koinViewModel()
            // Los PDF pasados por línea de comandos («Abrir con») se importan y se abren en el lector.
            LaunchedEffect(viewModel) {
                args.filter { it.endsWith(".pdf", ignoreCase = true) }.forEach { path ->
                    viewModel.importShared(FilePickedFile(File(path)))
                }
            }
            AiReaderRoot(viewModel, maxContentWidth = MAX_CONTENT_WIDTH.dp)
            ImportErrorDialog(viewModel)
        }
    }
}

/** Avisa cuando un PDF no se ha podido importar (en Android es un aviso breve; aquí, un diálogo). */
@Composable
private fun ImportErrorDialog(viewModel: MainViewModel) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.importErrors.collect { message = it } }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Entendido") } },
            text = { Text(text) }
        )
    }
}

private const val APP_FOLDER = "AIReader"
private const val WINDOW_WIDTH = 1000
private const val WINDOW_HEIGHT = 720

/** El diseño es de móvil: en una ventana ancha se centra en una columna de este ancho. */
private const val MAX_CONTENT_WIDTH = 560
