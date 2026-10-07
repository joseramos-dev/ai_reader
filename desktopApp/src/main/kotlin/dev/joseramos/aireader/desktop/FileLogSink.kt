package dev.joseramos.aireader.desktop

import dev.joseramos.aireader.core.common.LogLevel
import dev.joseramos.aireader.core.common.LogSink
import dev.joseramos.aireader.core.common.PrintLogSink
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Guarda el registro en [dir]: la app empaquetada no tiene consola y lo que va a la salida estándar se pierde.
 * Cuando el fichero pasa de [maxBytes] se aparta como `aireader.1.log` (se guardan dos). También lo escribe en la
 * salida estándar, que sí se ve al ejecutar desde Gradle. Si no se puede escribir el fichero, no falla.
 */
internal class FileLogSink(private val dir: File, private val maxBytes: Long = MAX_BYTES) : LogSink {
    private val file = File(dir, "aireader.log")
    private val previous = File(dir, "aireader.1.log")
    private var writer: Writer? = null
    private var size = 0L

    @Synchronized
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        PrintLogSink.log(level, tag, message, throwable)
        val line = buildString {
            append(LocalDateTime.now().format(TIME)).append(' ')
            append(level.name.first()).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(tag).append(": ").append(message).append('\n')
            throwable?.let { append(it.stackTraceToString()).append('\n') }
        }
        runCatching {
            if (size + line.length > maxBytes) rotate()
            val out = writer ?: open()
            out.write(line)
            out.flush()
            size += line.length
        }
    }

    private fun open(): Writer {
        dir.mkdirs()
        size = file.length()
        return FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { writer = it }
    }

    /** En Windows un fichero abierto no se puede renombrar: se cierra antes de apartarlo. */
    private fun rotate() {
        writer?.close()
        writer = null
        previous.delete()
        file.renameTo(previous)
        size = 0
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
