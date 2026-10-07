package dev.joseramos.aireader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.joseramos.aireader.core.common.PickedFile
import dev.joseramos.aireader.core.common.UriPickedFile
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.core.designsystem.theme.AiReaderTheme
import dev.joseramos.aireader.indexing.BookImporter
import dev.joseramos.aireader.indexing.ImportException
import dev.joseramos.aireader.navigation.AiReaderApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainViewModel(settings: SettingsRepository, private val importer: BookImporter) : ViewModel() {
    val themeMode = settings.settings.map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    private val openBook = Channel<String>(Channel.BUFFERED)
    private val errors = Channel<String>(Channel.BUFFERED)

    /** Libros recién importados desde otra app, para abrirlos en el lector. */
    val openBookRequests = openBook.receiveAsFlow()
    val importErrors = errors.receiveAsFlow()

    fun importShared(file: PickedFile) {
        viewModelScope.launch {
            try {
                openBook.send(importer.import(file))
            } catch (e: ImportException) {
                errors.send(e.message ?: "No se pudo importar el PDF")
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.importErrors.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Iconos de las barras del sistema acordes al tema elegido, no solo al del sistema.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AiReaderTheme(darkTheme = dark) {
                AiReaderApp(openBookRequests = viewModel.openBookRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** PDF recibido con «Abrir con» (VIEW) o «Compartir» (SEND). */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        viewModel.importShared(UriPickedFile(this, uri))
    }
}
