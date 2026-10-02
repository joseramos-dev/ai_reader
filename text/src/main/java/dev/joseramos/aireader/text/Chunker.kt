package dev.joseramos.aireader.text

/** Fragmento de texto para el RAG, con las páginas (base 1) que abarca. */
data class TextChunk(
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val charStart: Int,
    val charEnd: Int,
    val tokenCount: Int
)

/** Párrafo con la página en la que está. */
data class PageParagraph(val page: Int, val text: String)

/**
 * Trocea el texto de un capítulo en fragmentos de unos [Chunker.TARGET_TOKENS] tokens agrupando
 * párrafos enteros. Cada fragmento empieza repitiendo la última frase del anterior (solapamiento),
 * para que una idea partida entre dos fragmentos se pueda encontrar en cualquiera de ellos. Se
 * llama por capítulo, así que un fragmento nunca mezcla dos capítulos.
 */
object Chunker {
    const val TARGET_TOKENS = 350

    /** Aproximación para español con tokenizadores tipo XLM-R: ~1,3 tokens por palabra. */
    private const val TOKENS_PER_WORD = 1.3

    @Suppress("CyclomaticComplexMethod")
    fun chunk(paragraphs: List<PageParagraph>, targetTokens: Int = TARGET_TOKENS): List<TextChunk> {
        // Los párrafos más largos que el objetivo se parten por frases.
        val units = paragraphs.flatMap { p ->
            if (estimateTokens(p.text) <= targetTokens) {
                listOf(p)
            } else {
                PhraseSplitter.split(p.text, Int.MAX_VALUE).map { PageParagraph(p.page, it) }
            }
        }
        val chunks = mutableListOf<TextChunk>()
        val current = mutableListOf<PageParagraph>()
        var currentTokens = 0
        var offset = 0

        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString("\n\n") { it.text }
            chunks +=
                TextChunk(text, current.first().page, current.last().page, offset, offset + text.length, currentTokens)
            offset += text.length
            // Solapamiento: la última frase pasa a abrir el siguiente fragmento.
            val last = current.last()
            val overlap = PhraseSplitter.split(last.text).lastOrNull()
            current.clear()
            currentTokens = 0
            if (overlap != null && estimateTokens(overlap) < targetTokens / 2) {
                current += PageParagraph(last.page, overlap)
                currentTokens = estimateTokens(overlap)
            }
        }

        for (unit in units) {
            val tokens = estimateTokens(unit.text)
            val onlyOverlap = current.size == 1 && chunks.isNotEmpty()
            if (current.isNotEmpty() && currentTokens + tokens > targetTokens && !onlyOverlap) flush()
            current += unit
            currentTokens += tokens
        }
        if (current.isNotEmpty() &&
            !(current.size == 1 && chunks.isNotEmpty() && currentTokens < targetTokens / 2)
        ) {
            flush()
        }
        return chunks
    }

    fun estimateTokens(text: String): Int {
        val words = text.split(' ', '\n').count { it.isNotBlank() }
        return (words * TOKENS_PER_WORD).toInt().coerceAtLeast(1)
    }
}
