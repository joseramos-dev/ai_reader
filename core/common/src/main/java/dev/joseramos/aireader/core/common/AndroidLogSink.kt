package dev.joseramos.aireader.core.common

import android.util.Log as AndroidLog

/** Envía los mensajes de [Log] a logcat. */
object AndroidLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> AndroidLog.d(tag, message, throwable)
            LogLevel.INFO -> AndroidLog.i(tag, message, throwable)
            LogLevel.WARN -> AndroidLog.w(tag, message, throwable)
            LogLevel.ERROR -> AndroidLog.e(tag, message, throwable)
        }
    }
}
