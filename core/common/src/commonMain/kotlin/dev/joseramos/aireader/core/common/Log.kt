package dev.joseramos.aireader.core.common

import kotlin.concurrent.Volatile

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Destino de los mensajes de [Log]: cada plataforma pone el suyo al arrancar. */
fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/** Escribe en la salida estándar: el destino por defecto, el de las pruebas. */
object PrintLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        println("${level.name.first()}/$tag: $message")
        throwable?.printStackTrace()
    }
}

/**
 * Registro de mensajes independiente de la plataforma, con la misma forma que `android.util.Log`
 * (`Log.w(TAG, "mensaje", error)`). Hasta que la plataforma asigne su [sink], escribe en la salida estándar.
 */
object Log {
    @Volatile
    var sink: LogSink = PrintLogSink

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(LogLevel.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) = sink.log(LogLevel.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) = sink.log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        sink.log(LogLevel.ERROR, tag, message, throwable)
}
