package dev.joseramos.aireader.indexing

import dev.joseramos.aireader.core.common.ApplicationScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Indexa los libros en la propia app, de uno en uno (la indexación es pesada). Un trabajo por libro: encolar de
 * nuevo uno en marcha no hace nada, salvo con `replace`. Los reintentos esperan un poco más cada vez. Si se cierra
 * la app a medias, al arrancar los observadores vuelven a encolar los libros sin terminar.
 */
internal class CoroutineIndexScheduler(private val scope: CoroutineScope, private val runner: () -> IndexRunner) :
    IndexScheduler {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val oneAtATime = Semaphore(1)

    override fun enqueue(bookId: String, replace: Boolean) {
        synchronized(jobs) {
            val current = jobs[bookId]
            if (current?.isActive == true) {
                if (!replace) return
                current.cancel()
            }
            val job = scope.launch { runWithRetries(bookId) }
            jobs[bookId] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[bookId] === job) jobs.remove(bookId) } }
        }
    }

    override fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
    }

    private suspend fun runWithRetries(bookId: String) {
        var attempt = 0
        while (true) {
            // El turno solo se ocupa mientras se indexa: un libro que espera para reintentar no frena a los demás.
            when (oneAtATime.withPermit { runner().index(bookId, attempt) }) {
                IndexResult.SUCCESS, IndexResult.FAILED -> return
                IndexResult.RETRY -> {
                    attempt++
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 30_000L
    }
}

actual val indexingPlatformModule: Module = module {
    // El ejecutor se pide en cada intento, no al crear el planificador, para no depender de otros módulos al arrancar.
    single { CoroutineIndexScheduler(get(ApplicationScope)) { get() } } bind IndexScheduler::class
}
