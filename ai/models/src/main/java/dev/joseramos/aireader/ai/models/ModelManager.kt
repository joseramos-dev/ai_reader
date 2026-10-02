package dev.joseramos.aireader.ai.models

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.IoDispatcher
import dev.joseramos.aireader.core.data.db.DownloadedModelDao
import dev.joseramos.aireader.core.data.db.DownloadedModelEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ModelState {
    data object NotInstalled : ModelState

    data class Downloading(val progress: Float) : ModelState

    data object Installing : ModelState

    data class Installed(val sizeBytes: Long) : ModelState

    data class Failed(val message: String) : ModelState
}

/**
 * Descarga, instala y borra los modelos del [ModelCatalog]. Los modelos instalados se
 * registran en Room; las descargas en curso solo viven en memoria y, si se interrumpen,
 * se reanudan donde se quedaron la próxima vez.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext context: Context,
    private val downloader: ModelDownloader,
    private val dao: DownloadedModelDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val modelsDir = File(context.filesDir, "models")
    private val transient = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Estado de cada modelo del catálogo, por id. */
    val states: Flow<Map<String, ModelState>> = combine(dao.observeAll(), transient) { installed, inProgress ->
        ModelCatalog.all.associate { model ->
            val record = installed.firstOrNull { it.id == model.id }
            model.id to
                (inProgress[model.id] ?: record?.let { ModelState.Installed(it.sizeBytes) } ?: ModelState.NotInstalled)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun download(id: String) {
        if (jobs[id]?.isActive == true) return
        val model = ModelCatalog.byId(id)
        jobs[id] = scope.launch(io) {
            setState(id, ModelState.Downloading(0f))
            try {
                install(model)
                setState(id, null)
            } catch (e: CancellationException) {
                setState(id, null)
                throw e
            } catch (e: Exception) {
                // Cualquier fallo (red, disco, extracción, base de datos) se muestra con opción de reintentar.
                setState(id, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Cancela la descarga; lo descargado se conserva para reanudar. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    suspend fun delete(id: String) = withContext(io) {
        cancel(id)
        File(modelsDir, id).deleteRecursively()
        modelsDir.listFiles { f -> f.name.startsWith(id) }?.forEach { it.deleteRecursively() }
        dao.delete(id)
        setState(id, null)
    }

    /** Directorio del modelo instalado, o `null` si no lo está. */
    suspend fun installedDir(id: String): File? = dao.get(id)?.let { File(it.path) }?.takeIf { it.exists() }

    private suspend fun install(model: ModelInfo) {
        val installed = File(modelsDir, model.id)
        // Los ficheros sueltos se descargan directamente a su sitio; un archivo, a una carpeta temporal.
        val target = if (model.archive == ArchiveType.TAR_BZ2) File(modelsDir, "${model.id}.download") else installed
        var previous = 0L
        for (file in model.files) {
            downloader.download(file.url, File(target, file.name), file.sha256) { done, _ ->
                val progress = (previous + done).toFloat() / model.sizeBytes
                setState(model.id, ModelState.Downloading(progress.coerceIn(0f, 1f)))
            }
            previous += file.sizeBytes
        }
        setState(model.id, ModelState.Installing)
        if (model.archive == ArchiveType.TAR_BZ2) {
            val archive = File(target, model.files.single().name)
            ArchiveExtractor.extractTarBz2(archive, installed)
            target.deleteRecursively()
        }
        dao.upsert(
            DownloadedModelEntity(
                id = model.id,
                kind = model.kind,
                version = model.version,
                path = installed.absolutePath,
                sizeBytes = installed.walkBottomUp().filter { it.isFile }.sumOf { it.length() },
                sha256 = model.files.joinToString(",") { it.sha256 },
                downloadedAt = System.currentTimeMillis()
            )
        )
    }

    private fun setState(id: String, state: ModelState?) {
        transient.update { if (state == null) it - id else it + (id to state) }
    }
}
