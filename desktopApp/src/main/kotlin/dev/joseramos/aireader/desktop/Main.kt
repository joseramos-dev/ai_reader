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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.AppInfo
import dev.joseramos.aireader.core.common.FilePickedFile
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.core.common.ShutdownTasks
import dev.joseramos.aireader.shared.AiReaderRoot
import dev.joseramos.aireader.shared.AppStartup
import dev.joseramos.aireader.shared.MainViewModel
import dev.joseramos.aireader.shared.sharedModules
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * El registro va a `%LOCALAPPDATA%\AIReader\logs` (sin consola, la salida estándar se pierde), con los errores que
 * no captura nadie, y empieza con los datos del entorno que suelen hacer falta para diagnosticar.
 */
private fun startLogging(dir: File) {
    Log.sink = FileLogSink(dir)
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        Log.e(TAG, "Error sin capturar en el hilo ${thread.name}", error)
    }
    Log.i(
        TAG,
        "Arranque: AI Reader ${appInfo().version}, Java ${System.getProperty("java.version")}, " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
            "${Runtime.getRuntime().availableProcessors()} núcleos, " +
            "memoria máx. ${Runtime.getRuntime().maxMemory() / BYTES_PER_MB} MB"
    )
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
    val local = dirs.cache.parentFile
    startLogging(File(local, "logs"))
    val pdfs = args.filter { it.endsWith(".pdf", ignoreCase = true) }.map { File(it).absolutePath }
    val instance = SingleInstance(local)
    if (!instance.acquire(pdfs)) return
    val desktopModule = module {
        single { dirs }
        single { appInfo() }
    }
    val koin = startKoin { modules(listOf(desktopModule) + sharedModules) }.koin
    koin.get<AppStartup>().start()

    application {
        val windowState = rememberWindowState(size = DpSize(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp))
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        val close = {
            // Lo que se guarda con retraso (la página que se lee, la posición de la voz) se guarda ya, sin esperar
            // más de un par de segundos: si no, se perdería al terminar el proceso.
            runBlocking { withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { koin.get<ShutdownTasks>().runAll() } }
            exitApplication()
        }
        Window(onCloseRequest = close, state = windowState, title = "AI Reader", icon = icon) {
            val viewModel: MainViewModel = koinViewModel()
            // Los PDF pasados por línea de comandos («Abrir con») se importan y se abren en el lector, también los
            // que llegan de otra copia que se abre con la app ya abierta (que además la trae al frente).
            LaunchedEffect(viewModel) {
                pdfs.forEach { viewModel.importShared(FilePickedFile(File(it))) }
                instance.opened.collect { paths ->
                    window.isMinimized = false
                    window.toFront()
                    paths.forEach { viewModel.importShared(FilePickedFile(File(it))) }
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
private const val TAG = "AIReader"
private const val BYTES_PER_MB = 1024 * 1024
private const val SHUTDOWN_TIMEOUT_MS = 2_000L
private const val WINDOW_WIDTH = 1000
private const val WINDOW_HEIGHT = 720

/** El diseño es de móvil: en una ventana ancha se centra en una columna de este ancho. */
private const val MAX_CONTENT_WIDTH = 560
