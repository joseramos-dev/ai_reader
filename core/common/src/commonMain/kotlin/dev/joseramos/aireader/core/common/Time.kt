package dev.joseramos.aireader.core.common

import kotlin.time.Clock

/** Milisegundos desde el 1-1-1970 UTC (equivale a `System.currentTimeMillis()`, sin depender de Java). */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
