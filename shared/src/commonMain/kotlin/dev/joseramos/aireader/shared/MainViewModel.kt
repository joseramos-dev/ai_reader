package dev.joseramos.aireader.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.joseramos.aireader.core.common.PickedFile
import dev.joseramos.aireader.core.data.settings.SettingsRepository
import dev.joseramos.aireader.core.data.settings.ThemeMode
import dev.joseramos.aireader.indexing.BookImporter
import dev.joseramos.aireader.indexing.ImportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
