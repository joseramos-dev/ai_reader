package dev.joseramos.aireader.ai.embeddings

/** Lo que cambia de una plataforma a otra al usar ONNX Runtime. */
internal expect object OnnxPlatform {
    /** Desactiva la telemetría de ONNX Runtime. Va antes de tocar la librería nativa. */
    fun disableTelemetry()

    /** Baja la prioridad del hilo actual, para que la lectura no dé tirones mientras se indexa. */
    fun lowerCurrentThreadPriority()
}
