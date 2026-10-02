package dev.joseramos.aireader.ai.models

import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

object ArchiveExtractor {
    private const val READ_BUFFER = 1 shl 20

    /**
     * Extrae un `.tar.bz2` en [destination]. Si el archivo tiene un único directorio raíz
     * (como los modelos de sherpa-onnx), su contenido queda directamente en [destination].
     * Rechaza entradas que intenten escribir fuera del destino.
     */
    fun extractTarBz2(archive: File, destination: File) {
        val staging = File(destination.parentFile, destination.name + ".extracting")
        staging.deleteRecursively()
        staging.mkdirs()
        val base = staging.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(READ_BUFFER))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(staging, entry.name)
                if (!out.canonicalPath.startsWith(base) && out.canonicalPath != staging.canonicalPath) {
                    staging.deleteRecursively()
                    throw IOException("Entrada fuera del destino: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        val root = staging.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: staging
        destination.deleteRecursively()
        if (!root.renameTo(destination)) throw IOException("No se pudo instalar ${destination.name}")
        staging.deleteRecursively()
    }
}
