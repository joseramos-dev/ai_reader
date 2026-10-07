package dev.joseramos.aireader.ai.characters

import dev.joseramos.aireader.core.common.ApplicationScope
import dev.joseramos.aireader.core.common.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Analiza los personajes en la propia app. Un trabajo por libro: pedirlo de nuevo con uno en marcha no hace nada.
 * Los reintentos esperan el doble cada vez (hasta unos 32 minutos), como WorkManager en Android.
 */
internal class CoroutineCharacterScanScheduler(
    private val scope: CoroutineScope,
    private val runner: () -> CharacterScanRunner
) : CharacterScanScheduler {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val states = MutableStateFlow<Map<String, ScanJobState>>(emptyMap())

    override fun start(bookId: String) {
        synchronized(jobs) {
            if (jobs[bookId]?.isActive == true) return
            states.value += bookId to ScanJobState(active = true)
            val job = scope.launch { runWithRetries(bookId) }
            jobs[bookId] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[bookId] === job) jobs.remove(bookId) } }
        }
    }

    override fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        states.value -= bookId
    }

    override fun observe(bookId: String): Flow<ScanJobState> = states.map { it[bookId] ?: ScanJobState() }

    private suspend fun runWithRetries(bookId: String) {
        var attempt = 0
        while (true) {
            val state = when (scan(bookId, attempt)) {
                ScanResult.SUCCESS -> ScanJobState()
                ScanResult.FAILED -> ScanJobState(failed = true)
                ScanResult.NEEDS_API_KEY -> ScanJobState(failed = true, needsApiKey = true)
                ScanResult.RETRY -> null
            }
            if (state != null) {
                states.value += bookId to state
                return
            }
            delay(RETRY_DELAY_MS shl minOf(attempt, MAX_BACKOFF_STEPS))
            attempt++
        }
    }

    /**
     * Un fallo que no es de la IA (base de datos, la clave guardada que no se puede descifrar…) cuenta como fallido:
     * si no, el trabajo terminaría con el estado «en curso» para siempre y no se podría volver a lanzar.
     */
    private suspend fun scan(bookId: String, attempt: Int): ScanResult = try {
        runner().scan(bookId, attempt)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.e(TAG, "Fallo analizando los personajes de $bookId", e)
        ScanResult.FAILED
    }

    private companion object {
        const val TAG = "CharacterScanScheduler"
        const val RETRY_DELAY_MS = 60_000L
        const val MAX_BACKOFF_STEPS = 5
    }
}

actual val charactersPlatformModule: Module = module {
    single { CoroutineCharacterScanScheduler(get(ApplicationScope)) { get() } } bind CharacterScanScheduler::class
}
