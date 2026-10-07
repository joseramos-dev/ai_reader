package dev.joseramos.aireader.desktop

import dev.joseramos.aireader.core.common.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Una sola copia de la app sobre los mismos datos (dos a la vez se pisarían la base de datos y los ajustes). La primera
 * bloquea un fichero en [dir] y escucha en un puerto local; las siguientes («Abrir con» con la app ya abierta) le pasan
 * los PDF que traían y terminan. El puerto se apunta junto a una clave aleatoria que solo puede leer el usuario.
 */
internal class SingleInstance(private val dir: File) {
    private val requests = Channel<List<String>>(Channel.UNLIMITED)

    @Suppress("unused") // Se guarda para que el bloqueo dure lo que dura la app.
    private var lock: FileLock? = null

    /** Lo que piden las copias que se abren después: los PDF que traían (vacío = solo mostrar la ventana). */
    val opened: Flow<List<String>> = requests.receiveAsFlow()

    /**
     * `true` si esta es la primera copia y debe seguir; `false` si ya había otra, que ha recibido [paths] (o no se
     * ha podido avisar, pero tampoco se debe abrir otra encima de los mismos datos).
     */
    fun acquire(paths: List<String>): Boolean {
        dir.mkdirs()
        val channel = FileChannel.open(
            File(dir, LOCK_FILE).toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        )
        val acquired = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (acquired == null) {
            channel.close()
            forward(paths)
            return false
        }
        lock = acquired
        listen()
        return true
    }

    private fun listen() {
        val token = UUID.randomUUID().toString()
        val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        File(dir, PORT_FILE).writeText("${server.localPort} $token")
        Thread({
            while (true) {
                try {
                    server.accept().use { socket ->
                        val lines = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
                        if (lines.firstOrNull() == token) requests.trySend(lines.drop(1).filter { it.isNotBlank() })
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "No se pudo leer lo que envió otra copia de la app", e)
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    private fun forward(paths: List<String>) {
        try {
            val (port, token) = File(dir, PORT_FILE).readText().trim().split(' ', limit = 2)
            Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
                socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { out ->
                    (listOf(token) + paths).forEach { out.write(it + "\n") }
                }
            }
            Log.i(TAG, "La app ya estaba abierta: se le han pasado ${paths.size} PDF")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "La app ya estaba abierta y no se le pudo avisar", e)
        }
    }

    private companion object {
        const val TAG = "SingleInstance"
        const val LOCK_FILE = "instance.lock"
        const val PORT_FILE = "instance.port"
        const val BACKLOG = 4
    }
}
