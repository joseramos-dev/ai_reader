package dev.joseramos.aireader.text

import java.text.Normalizer

/** Coincidencia de un nombre en un texto: caracteres `[start, end)` y el índice del patrón. */
data class NameMatch(val start: Int, val end: Int, val pattern: Int)

/**
 * Busca muchos nombres a la vez en un texto con un autómata Aho-Corasick (una sola pasada, sin
 * importar cuántos nombres haya). Ignora tildes y mayúsculas al comparar, pero exige que en el
 * texto la coincidencia empiece por mayúscula (para no marcar «rosa» como «Rosa») y que sea una
 * palabra completa. Si se solapan varias, gana la que empieza antes y, a igualdad, la más larga.
 */
class NameMatcher(patterns: List<String>) {
    private val goto = mutableListOf(HashMap<Char, Int>())
    private val fail = mutableListOf(0)

    /** Patrón más largo que termina en cada nodo (o -1). */
    private val output = mutableListOf(-1)

    /** Siguiente nodo, siguiendo los enlaces de fallo, que tiene un patrón (o -1). */
    private val dictLink = mutableListOf(-1)
    private val lengths = IntArray(patterns.size)

    init {
        patterns.forEachIndexed { index, pattern ->
            val folded = pattern.trim().map(::fold)
            lengths[index] = folded.size
            if (folded.isEmpty()) return@forEachIndexed
            var node = 0
            for (c in folded) {
                node = goto[node][c] ?: newNode().also { goto[node][c] = it }
            }
            if (output[node] < 0 || lengths[output[node]] < folded.size) output[node] = index
        }
        buildLinks()
    }

    val isEmpty: Boolean get() = goto[0].isEmpty()

    fun find(text: String): List<NameMatch> {
        if (isEmpty) return emptyList()
        val candidates = mutableListOf<NameMatch>()
        var node = 0
        text.forEachIndexed { i, raw ->
            val c = fold(raw)
            while (node != 0 && goto[node][c] == null) node = fail[node]
            node = goto[node][c] ?: 0
            var hit = if (output[node] >= 0) node else dictLink[node]
            while (hit >= 0) {
                val pattern = output[hit]
                val start = i - lengths[pattern] + 1
                if (isWholeWord(text, start, i + 1) && text[start].isUpperCase()) {
                    candidates += NameMatch(start, i + 1, pattern)
                }
                hit = dictLink[hit]
            }
        }
        return leftmostLongest(candidates)
    }

    private fun newNode(): Int {
        goto += HashMap()
        fail += 0
        output += -1
        dictLink += -1
        return goto.lastIndex
    }

    private fun buildLinks() {
        val queue = ArrayDeque<Int>()
        goto[0].values.forEach { queue += it }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for ((c, child) in goto[node]) {
                var f = fail[node]
                while (f != 0 && goto[f][c] == null) f = fail[f]
                fail[child] = goto[f][c]?.takeIf { it != child } ?: 0
                val target = fail[child]
                dictLink[child] = if (output[target] >= 0) target else dictLink[target]
                queue += child
            }
        }
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean =
        (start == 0 || !text[start - 1].isLetterOrDigit()) && (end == text.length || !text[end].isLetterOrDigit())

    private fun leftmostLongest(matches: List<NameMatch>): List<NameMatch> {
        val result = mutableListOf<NameMatch>()
        var nextFree = 0
        for (match in matches.sortedWith(compareBy<NameMatch> { it.start }.thenByDescending { it.end })) {
            if (match.start >= nextFree) {
                result += match
                nextFree = match.end
            }
        }
        return result
    }

    private companion object {
        private val folded = HashMap<Char, Char>()

        /** Minúscula sin tilde, conservando un carácter por carácter (los índices no se mueven). */
        fun fold(c: Char): Char = when {
            c.isWhitespace() -> ' '
            c.code < ASCII -> c.lowercaseChar()
            else -> synchronized(folded) {
                folded.getOrPut(c) { Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar() }
            }
        }

        const val ASCII = 128
    }
}
