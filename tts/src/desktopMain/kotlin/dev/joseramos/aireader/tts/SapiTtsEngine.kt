package dev.joseramos.aireader.tts

import dev.joseramos.aireader.core.common.AppDirs
import dev.joseramos.aireader.core.common.Log
import dev.joseramos.aireader.text.Language
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Una voz SAPI instalada en Windows y el idioma que declara (`es-ES`, `en-US`…). */
internal data class SapiVoice(val name: String, val culture: String)

/**
 * Voces de Windows (SAPI, `System.Speech`) a través de un único proceso de PowerShell que se mantiene abierto:
 * arrancarlo por frase costaría casi un segundo cada vez. Se le habla por líneas (`voices`, `select|voz`,
 * `speak|velocidad|fichero|texto en base64`) y responde con una línea (`OK…` o `ERR|motivo`). Cada frase se
 * sintetiza a un WAV temporal que se lee como PCM y se borra, igual que en Android.
 */
internal class SapiTtsEngine(dirs: AppDirs, private val io: CoroutineDispatcher) : TtsEngine {
    private val mutex = Mutex()
    private val dir = File(dirs.cache, "tts")
    private val ids = AtomicLong()
    private var session: PowerShellSession? = null
    private var voices: List<SapiVoice>? = null
    private var loaded: Language? = null
    private var lastSampleRate = DEFAULT_SAMPLE_RATE

    override suspend fun load(language: Language): VoiceAvailability = withContext(io) {
        mutex.withLock {
            val session = session() ?: return@withLock VoiceAvailability.NO_ENGINE
            if (loaded == language) return@withLock VoiceAvailability.READY
            val voice = choose(session, language) ?: return@withLock VoiceAvailability.MISSING
            if (session.request("select|${voice.name}") == null) return@withLock VoiceAvailability.MISSING
            loaded = language
            VoiceAvailability.READY
        }
    }

    override suspend fun availability(language: Language): VoiceAvailability = withContext(io) {
        mutex.withLock {
            val session = session() ?: return@withLock VoiceAvailability.NO_ENGINE
            if (choose(session, language) == null) VoiceAvailability.MISSING else VoiceAvailability.READY
        }
    }

    override suspend fun synthesize(text: String, speed: Float): Pcm {
        // Una frase dura poco: se deja terminar en vez de matar PowerShell para cortarla, que obligaría a
        // arrancarlo de nuevo en la siguiente.
        val pcm = withContext(io + NonCancellable) { mutex.withLock { synthesizeLocked(text, speed) } }
        withContext(io) { ensureActive() }
        return pcm
    }

    private fun synthesizeLocked(text: String, speed: Float): Pcm {
        val session = checkNotNull(session) { "La voz no está cargada" }
        dir.mkdirs()
        val file = File(dir, "phrase-${ids.incrementAndGet()}.wav")
        try {
            val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            val reply = session.request("speak|${rateOf(speed)}|${file.absolutePath}|$encoded", SPEAK_TIMEOUT_MS)
            check(reply != null) { "La voz de Windows no pudo sintetizar la frase" }
            // Una frase sin nada pronunciable («—», «…») puede dejar el WAV vacío.
            if (file.length() <= WAV_HEADER_BYTES) return Pcm(FloatArray(0), lastSampleRate)
            return WavReader.read(file).also { lastSampleRate = it.sampleRate }
        } finally {
            file.delete()
        }
    }

    override fun release() {
        // El proceso se queda abierto (arrancarlo es lo lento); solo se olvida la voz elegida.
        loaded = null
        dir.listFiles()?.forEach { it.delete() }
    }

    /** La voz instalada que mejor encaja con [language]: la del país preferido y, si no, cualquiera del idioma. */
    private fun choose(session: PowerShellSession, language: Language): SapiVoice? {
        val installed = voices ?: parseVoices(session.request("voices")).also { voices = it }
        val tag = language.locale.toLanguageTag()
        val code = language.locale.language
        return installed.firstOrNull { it.culture.equals(tag, ignoreCase = true) }
            ?: installed.firstOrNull { it.culture.substringBefore('-').equals(code, ignoreCase = true) }
    }

    private fun parseVoices(reply: String?): List<SapiVoice> = reply.orEmpty().split(';').mapNotNull { entry ->
        val (name, culture) = entry.split('/').takeIf { it.size == 2 } ?: return@mapNotNull null
        SapiVoice(name.trim(), culture.trim())
    }

    /** Arranca PowerShell la primera vez; `null` si no se puede (no hay Windows PowerShell o no responde). */
    private fun session(): PowerShellSession? {
        session?.takeIf { it.isAlive }?.let { return it }
        voices = null
        loaded = null
        return try {
            dir.mkdirs()
            val script = File(dir, "sapi.ps1").apply { writeText(SCRIPT, Charsets.US_ASCII) }
            PowerShellSession.start(script)
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo arrancar PowerShell para la voz de Windows", e)
            null
        }.also { session = it }
    }

    /** Proceso de PowerShell con el que se habla por líneas. */
    private class PowerShellSession(private val process: Process) {
        private val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val replies = LinkedBlockingQueue<String>()

        val isAlive: Boolean get() = process.isAlive

        init {
            thread(isDaemon = true, name = "sapi-out") {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { replies.put(it) }
            }
            thread(isDaemon = true, name = "sapi-err") {
                process.errorStream.bufferedReader().forEachLine { Log.w(TAG, "PowerShell: $it") }
            }
        }

        private fun waitReady(): Boolean = replies.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) == "READY"

        /**
         * Envía [command] y devuelve lo que sigue a `OK` en la respuesta, o `null` si falló o no respondió a
         * tiempo (en ese caso se mata el proceso: la próxima petición arrancará otro).
         */
        @Synchronized
        fun request(command: String, timeoutMs: Long = REQUEST_TIMEOUT_MS): String? = try {
            writer.write(command)
            writer.newLine()
            writer.flush()
            val reply = replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (reply == null) {
                Log.w(TAG, "PowerShell no respondió a «${command.substringBefore('|')}»")
                process.destroyForcibly()
                null
            } else if (reply == "OK" || reply.startsWith("OK|")) {
                reply.removePrefix("OK").removePrefix("|")
            } else {
                Log.w(TAG, "PowerShell respondió con un error: $reply")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "Se perdió la conexión con PowerShell", e)
            process.destroyForcibly()
            null
        }

        companion object {
            fun start(script: File): PowerShellSession? {
                val process = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script.absolutePath
                ).start()
                Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
                val session = PowerShellSession(process)
                if (!session.waitReady()) {
                    Log.w(TAG, "PowerShell no llegó a arrancar la voz de Windows")
                    process.destroyForcibly()
                    return null
                }
                return session
            }
        }
    }

    private companion object {
        const val TAG = "SapiTtsEngine"
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val WAV_HEADER_BYTES = 44L
        const val START_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val SPEAK_TIMEOUT_MS = 60_000L
        const val MAX_RATE = 10

        /**
         * La velocidad de SAPI va de −10 a 10 en pasos enteros; se toma cada 10 pasos como un factor de 3
         * (0 es la velocidad normal).
         */
        fun rateOf(speed: Float): Int =
            (MAX_RATE * ln(speed.coerceAtLeast(MIN_SPEED).toDouble()) / ln(RATE_BASE)).roundToInt()
                .coerceIn(-MAX_RATE, MAX_RATE)

        const val MIN_SPEED = 0.1f
        const val RATE_BASE = 3.0

        /** Bucle del proceso: una línea de entrada, una de salida. Solo ASCII; el texto va en base64. */
        val SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Console]::InputEncoding = [System.Text.Encoding]::UTF8
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            function Reply(${'$'}text) { [Console]::Out.WriteLine(${'$'}text); [Console]::Out.Flush() }
            Reply 'READY'
            while (${'$'}true) {
              ${'$'}line = [Console]::In.ReadLine()
              if (${'$'}null -eq ${'$'}line) { break }
              try {
                ${'$'}p = ${'$'}line.Split('|')
                switch (${'$'}p[0]) {
                  'voices' {
                    ${'$'}v = ${'$'}synth.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled } |
                      ForEach-Object { ${'$'}_.VoiceInfo.Name + '/' + ${'$'}_.VoiceInfo.Culture.Name }
                    Reply ('OK|' + (${'$'}v -join ';'))
                  }
                  'select' { ${'$'}synth.SelectVoice(${'$'}p[1]); Reply 'OK' }
                  'speak' {
                    ${'$'}synth.Rate = [int]${'$'}p[1]
                    ${'$'}synth.SetOutputToWaveFile(${'$'}p[2])
                    ${'$'}text = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String(${'$'}p[3]))
                    ${'$'}synth.Speak(${'$'}text)
                    ${'$'}synth.SetOutputToNull()
                    Reply 'OK'
                  }
                  default { Reply 'ERR|comando desconocido' }
                }
              } catch {
                Reply ('ERR|' + ${'$'}_.Exception.Message.Replace("`r", ' ').Replace("`n", ' '))
              }
            }
        """.trimIndent()
    }
}
