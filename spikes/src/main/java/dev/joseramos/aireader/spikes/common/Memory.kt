package dev.joseramos.aireader.spikes.common

import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Memory {
    /** PSS total del proceso en MB (Java + nativo + gráficos). */
    fun pssMb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024
    }
}

/** Muestrea la PSS cada [intervalMs] mientras dura [block] y devuelve el pico. */
suspend fun <T> withPeakPss(scope: CoroutineScope, intervalMs: Long = 300, block: suspend () -> T): Pair<T, Int> {
    var peak = Memory.pssMb()
    val sampler = scope.launch(Dispatchers.Default) {
        while (isActive) {
            peak = maxOf(peak, Memory.pssMb())
            delay(intervalMs)
        }
    }
    try {
        val result = block()
        peak = maxOf(peak, Memory.pssMb())
        return result to peak
    } finally {
        sampler.cancel()
    }
}
