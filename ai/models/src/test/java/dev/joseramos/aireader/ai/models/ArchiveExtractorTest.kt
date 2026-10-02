package dev.joseramos.aireader.ai.models

import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveExtractorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tarBz2(vararg entries: Pair<String, String>): File {
        val file = folder.newFile("model.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun singleRootDirectoryIsFlattenedIntoDestination() {
        val archive = tarBz2("vits-piper/tokens.txt" to "a", "vits-piper/espeak-ng-data/phontab" to "b")
        val destination = File(folder.root, "models/voice")

        ArchiveExtractor.extractTarBz2(archive, destination)

        assertEquals("a", File(destination, "tokens.txt").readText())
        assertEquals("b", File(destination, "espeak-ng-data/phontab").readText())
        assertFalse(File(folder.root, "models/voice.extracting").exists())
    }

    @Test
    fun entriesEscapingTheDestinationAreRejected() {
        val archive = tarBz2("../../evil.txt" to "x")
        val destination = File(folder.root, "models/voice")

        val error = runCatching { ArchiveExtractor.extractTarBz2(archive, destination) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(File(folder.root, "evil.txt").exists())
        assertFalse(destination.exists())
    }
}
