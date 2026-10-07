package dev.joseramos.aireader.tts

/**
 * Salida de una sesión de lectura sobre una [AudioSink]. Lleva la cuenta de lo escrito y de dónde empieza cada
 * frase, para saber qué frase está sonando de verdad (el búfer de la salida va por delante de lo que se oye).
 * La velocidad se aplica al reproducir, sin cambiar el tono, si la salida lo admite.
 */
internal class SpeechTrack(private val sink: AudioSink) {
    val sampleRate: Int get() = sink.sampleRate

    /** Frames escritos desde que se creó. */
    @Volatile var framesWritten = 0L
        private set

    /** Frase y frame en el que empieza, en el orden en que se escribieron. */
    private val marks = ArrayDeque<Pair<Long, Phrase>>()

    val framesPlayed: Long get() = sink.framesPlayed

    /** Anota que [phrase] empieza en lo siguiente que se escriba. */
    fun mark(phrase: Phrase) = synchronized(marks) { marks.addLast(framesWritten to phrase) }

    fun write(pcm: FloatArray, offset: Int, count: Int) {
        sink.write(pcm, offset, count)
        framesWritten += count
    }

    /** Frases que han empezado a sonar desde la última consulta (o todas las anotadas si [all]). */
    fun started(all: Boolean = false): List<Phrase> {
        val played = framesPlayed
        return synchronized(marks) {
            buildList {
                while (marks.isNotEmpty() && (all || marks.first().first <= played)) add(marks.removeFirst().second)
            }
        }
    }

    /** `false` si no se puede cambiar la velocidad al reproducir. */
    fun setSpeed(speed: Float): Boolean = sink.setSpeed(speed)

    fun play() = sink.play()

    fun pause() = sink.pause()

    fun stop() = sink.stop()

    fun release() = sink.release()
}
