package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.text.Language
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Prueba la voz de Windows de verdad (arranca PowerShell y sintetiza): solo en Windows, y las pruebas de una voz
 * concreta se omiten si no está instalada.
 */
class SapiTtsEngineTest {
    private lateinit var dir: File
    private lateinit var engine: SapiTtsEngine

    @Before
    fun setUp() {
        assumeTrue("Solo en Windows", System.getProperty("os.name").startsWith("Windows"))
        dir = createTempDirectory("sapi-test").toFile()
        engine = SapiTtsEngine(AppDirs(files = dir, cache = dir), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.release()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun synthesizesASpanishPhraseWhenAVoiceIsInstalled() = runBlocking {
        val availability = engine.load(Language.SPANISH)
        assumeTrue("No hay voz en español instalada: $availability", availability == VoiceAvailability.READY)

        val pcm = engine.synthesize("Hola, esto es una prueba de lectura en voz alta.", 1f)

        assertTrue("Frecuencia ${pcm.sampleRate}", pcm.sampleRate in 8_000..48_000)
        val seconds = pcm.samples.size.toFloat() / pcm.sampleRate
        assertTrue("Duración $seconds s", seconds in 1f..10f)
        assertTrue("Sin sonido", pcm.samples.any { kotlin.math.abs(it) > 0.01f })
    }

    @Test
    fun fasterSpeedGivesShorterAudio() = runBlocking {
        assumeTrue(engine.load(Language.SPANISH) == VoiceAvailability.READY)
        val text = "Esta es una frase algo más larga para poder comparar la duración del audio."

        val normal = engine.synthesize(text, 1f)
        val fast = engine.synthesize(text, 2f)

        val normalSeconds = normal.samples.size.toFloat() / normal.sampleRate
        val fastSeconds = fast.samples.size.toFloat() / fast.sampleRate
        assertTrue("normal $normalSeconds s, rápida $fastSeconds s", fastSeconds < normalSeconds * 0.85f)
    }

    @Test
    fun anUnpronounceablePhraseGivesEmptyAudio() = runBlocking {
        assumeTrue(engine.load(Language.SPANISH) == VoiceAvailability.READY)
        val pcm = engine.synthesize("—", 1f)
        assertEquals(true, pcm.samples.size < 2_000)
    }
}
