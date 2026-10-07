package dev.joseramos.aireader.ai.embeddings

import ai.onnxruntime.OrtEnvironment

internal actual object OnnxPlatform {
    /**
     * En la JVM no se puede cambiar el entorno del proceso, así que se usa el interruptor de la propia
     * librería. En Windows la telemetría de ONNX Runtime solo emite eventos ETW locales, sin red.
     */
    actual fun disableTelemetry() {
        OrtEnvironment.getEnvironment().setTelemetry(false)
    }

    actual fun lowerCurrentThreadPriority() {
        Thread.currentThread().priority = Thread.MIN_PRIORITY
    }
}
