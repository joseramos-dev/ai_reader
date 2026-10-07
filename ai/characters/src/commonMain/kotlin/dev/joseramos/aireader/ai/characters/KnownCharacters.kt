package dev.joseramos.aireader.ai.characters

import java.text.Normalizer

/**
 * Qué personajes ya conocidos se le enseñan al modelo en cada bloque de texto. Mandar los de todo
 * el libro (hasta [MAX_KNOWN], con todos sus nombres) en cada petición gasta tokens en personajes que
 * no aparecen. Van los que se nombran en el bloque (alguna palabra de alguno de sus nombres) y los
 * principales (los que tienen más nombres y apodos), por si aparecen con una forma nueva.
 */
internal object KnownCharacters {
    const val MAX_KNOWN = 150
    private const val MAIN_CHARACTERS = 20
    private const val MIN_WORD = 3
    private val diacritics = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Ids de [namesById] (en su orden) que van con [block]. */
    fun select(namesById: Map<Long, List<String>>, block: String): List<Long> {
        val words = wordsOf(block)
        val main = namesById.entries.sortedByDescending { it.value.size }.take(MAIN_CHARACTERS).map { it.key }.toSet()
        return namesById.filter { (id, names) ->
            id in main || names.any { name -> wordsOf(name).any { it.length >= MIN_WORD && it in words } }
        }.keys.toList().takeLast(MAX_KNOWN)
    }

    private fun wordsOf(text: String): Set<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(diacritics, "")
        .split(separators)
        .filterTo(HashSet()) { it.isNotEmpty() }
}
