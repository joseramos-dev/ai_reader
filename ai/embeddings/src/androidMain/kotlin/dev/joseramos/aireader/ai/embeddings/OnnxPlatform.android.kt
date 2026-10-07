package dev.joseramos.aireader.ai.embeddings

import android.os.Process
import android.system.Os

internal actual object OnnxPlatform {
    /** La librería nativa lee la variable al iniciarse: `setTelemetry(false)` llegaría tarde. */
    actual fun disableTelemetry() {
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
    }

    actual fun lowerCurrentThreadPriority() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }
}
