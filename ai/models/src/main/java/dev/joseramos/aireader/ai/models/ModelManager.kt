package dev.joseramos.aireader.ai.models

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.first
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
    private val assets = context.assets
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
        var previous = 0L
        for (file in model.files) {
            downloader.download(file.url, File(installed, file.name), file.sha256) { done, _ ->
                val progress = (previous + done).toFloat() / model.sizeBytes
                setState(model.id, ModelState.Downloading(progress.coerceIn(0f, 1f)))
            }
            previous += file.sizeBytes
        }
        finishInstall(model, installed)
    }

    /**
     * Modelos que vienen ya en el APK (ver `app/src/main/assets/bundled_models/`): se "instalan"
     * copiándolos la primera vez, sin red ni barra de progreso. Se llama una vez al arrancar la app;
     * si alguno ya está instalado o no viene empaquetado, no se toca.
     */
    suspend fun installBundledModels() = withContext(io) {
        removeRetiredModels()
        for (model in ModelCatalog.all) {
            if (dao.get(model.id) != null || !hasBundledAssets(model)) continue
            runCatching { installFromAssets(model) }
                .onFailure { Log.w(TAG, "No se pudo instalar el modelo empaquetado ${model.id}", it) }
            setState(model.id, null)
        }
    }

    /**
     * Borra los modelos instalados que ya no están en el catálogo (por ejemplo, las voces de Piper
     * de versiones anteriores, que se sustituyeron por la voz del sistema): ficheros y registro.
     */
    private suspend fun removeRetiredModels() {
        val known = ModelCatalog.all.map { it.id }.toSet()
        for (record in dao.observeAll().first()) {
            if (record.id in known) continue
            Log.i(TAG, "Borrando el modelo retirado ${record.id}")
            File(record.path).deleteRecursively()
            dao.delete(record.id)
        }
        modelsDir.listFiles()?.filter { dir ->
            known.none { dir.name.startsWith(it) }
        }?.forEach { it.deleteRecursively() }
    }

    private suspend fun installFromAssets(model: ModelInfo) {
        val installed = File(modelsDir, model.id)
        for (file in model.files) {
            val dest = File(installed, file.name)
            copyAsset(bundledAssetPath(model.id, file.name), dest)
            val actual = ModelDownloader.sha256Of(dest)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                throw ChecksumMismatchException(file.sha256, actual)
            }
        }
        finishInstall(model, installed)
    }

    private fun hasBundledAssets(model: ModelInfo): Boolean = model.files.all { file ->
        runCatching { assets.open(bundledAssetPath(model.id, file.name)).close() }.isSuccess
    }

    private fun bundledAssetPath(modelId: String, fileName: String) = "bundled_models/$modelId/$fileName"

    private fun copyAsset(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        assets.open(assetPath).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    }

    private suspend fun finishInstall(model: ModelInfo, installed: File) {
        setState(model.id, ModelState.Installing)
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

    private companion object {
        const val TAG = "ModelManager"
    }
}
