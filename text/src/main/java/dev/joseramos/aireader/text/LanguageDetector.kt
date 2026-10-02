package dev.joseramos.aireader.text

/** Idiomas que la app sabe leer en voz alta. */
enum class Language { SPANISH, ENGLISH }

/**
 * Distingue español de inglés contando palabras muy frecuentes de cada idioma en una muestra del
 * libro. Es barato, no necesita modelos y acierta de sobra con unas pocas páginas de texto. Ante la
 * duda (poco texto o empate), español.
 */
object LanguageDetector {
    private val spanish = setOf(
        "de", "la", "que", "el", "en", "y", "los", "se", "del", "las", "un", "por", "con", "no", "una", "su",
        "para", "es", "al", "lo", "como", "más", "pero", "sus", "le", "ya", "o", "fue", "este", "ha", "sí", "porque"
    )
    private val english = setOf(
        "the", "of", "and", "to", "in", "is", "that", "it", "was", "for", "on", "with", "as", "his", "he", "be",
        "at", "by", "i", "you", "had", "her", "she", "which", "not", "but", "are", "this", "have", "from", "they"
    )
    private const val MIN_HITS = 20
    private val word = Regex("\\p{L}+")

    fun detect(texts: List<String>): Language {
        var es = 0
        var en = 0
        for (text in texts) {
            for (match in word.findAll(text.lowercase())) {
                val w = match.value
                if (w in spanish) es++
                if (w in english) en++
            }
        }
        return if (en >= MIN_HITS && en > es) Language.ENGLISH else Language.SPANISH
    }
}
