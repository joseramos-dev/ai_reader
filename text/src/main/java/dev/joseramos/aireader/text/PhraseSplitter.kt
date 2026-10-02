package dev.joseramos.aireader.text

/**
 * Divide un párrafo en frases para la lectura en voz alta y el resaltado. Respeta las
 * abreviaturas habituales en español e inglés («Sr.», «pág.», «p. ej.», «Mr.») y las iniciales,
 * y parte por comas o punto y coma las frases que superan [maxChars] (Piper pierde naturalidad
 * con frases muy largas).
 */
object PhraseSplitter {
    const val DEFAULT_MAX_CHARS = 300

    private val abbreviations = setOf(
        "sr", "sra", "srta", "dr", "dra", "d", "dña", "ud", "uds", "vd", "vds", "pág", "págs", "p", "pp",
        "cap", "caps", "vol", "vols", "fig", "figs", "núm", "nº", "art", "arts", "ed", "eds", "ej", "aprox",
        "av", "avda", "c", "cf", "etc", "ibid", "op", "cit", "trad", "s", "ss", "a", "e", "i", "ee", "uu",
        // Inglés
        "mr", "mrs", "ms", "jr", "st", "vs", "approx", "inc", "ltd", "mt"
    )
    private const val SENTENCE_STARTERS = "¿¡«\"“—('"

    fun split(paragraph: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val text = paragraph.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return emptyList()
        return sentences(text).flatMap { splitLong(it, maxChars) }
    }

    private fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '?' || c == '!' || c == '…') {
                var end = i + 1
                while (end < text.length && text[end] in ".?!…»\"”')]") end++
                if (end >= text.length) break
                if (text[end] == ' ' && isBoundary(text, i, end + 1)) {
                    result += text.substring(start, end).trim()
                    start = end + 1
                }
                i = end
            } else {
                i++
            }
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        return result
    }

    private fun isBoundary(text: String, punctuation: Int, nextStart: Int): Boolean {
        if (nextStart >= text.length) return true
        val next = text[nextStart]
        if (!(next.isUpperCase() || next.isDigit() || next in SENTENCE_STARTERS)) return false
        if (text[punctuation] != '.') return true
        val word = text.substring(0, punctuation).takeLastWhile { it.isLetter() || it == 'º' }
        // Iniciales («J. R. R. Tolkien») y abreviaturas no cierran frase.
        return !(word.length == 1 && word[0].isUpperCase()) && word.lowercase() !in abbreviations
    }

    private fun splitLong(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        for (piece in sentence.split(Regex("(?<=[,;:])\\s+"))) {
            if (current.isNotEmpty() && current.length + piece.length + 1 > maxChars) {
                parts += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) parts += current.toString()
        // Un trozo sin comas aún demasiado largo se corta por palabras.
        return parts.flatMap { part -> if (part.length <= maxChars) listOf(part) else byWords(part, maxChars) }
    }

    private fun byWords(text: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            if (current.isNotEmpty() && current.length + word.length + 1 > maxChars) {
                parts += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }
}
