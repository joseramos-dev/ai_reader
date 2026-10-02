package dev.joseramos.aireader.text

/**
 * Prepara una frase para la síntesis de voz: expande abreviaturas y convierte números romanos en
 * contextos inequívocos («capítulo IV», «siglo XVIII») a cifras, que la voz lee correctamente.
 * Solo afecta a lo que se pronuncia; el texto mostrado no cambia.
 */
object SpeechNormalizer {
    private val replacements = listOf(
        "p. ej." to "por ejemplo",
        "págs." to "páginas",
        "pág." to "página",
        "Sra." to "señora",
        "Sr." to "señor",
        "Srta." to "señorita",
        "Dra." to "doctora",
        "Dr." to "doctor",
        "Dña." to "doña",
        "Ud." to "usted",
        "Uds." to "ustedes",
        "núm." to "número",
        "nº" to "número",
        "cap." to "capítulo",
        "vol." to "volumen",
        "fig." to "figura",
        "aprox." to "aproximadamente",
        "etc." to "etcétera",
        "EE. UU." to "Estados Unidos",
        "EE.UU." to "Estados Unidos"
    )
    private val romanContext = Regex(
        "\\b(cap[ií]tulo|parte|libro|tomo|siglo|volumen|acto|escena|lecci[oó]n)\\s+([IVXLCDM]+)\\b",
        RegexOption.IGNORE_CASE
    )
    private val romanValues = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    fun normalize(phrase: String): String {
        var text = phrase
        for ((abbreviation, expansion) in replacements) {
            text = text.replace(abbreviation, expansion)
        }
        text = text.replace("%", " por ciento").replace("&", " y ")
        // Si la frase acababa en una abreviatura («… etc.»), se conserva el punto final para la entonación.
        if (phrase.trimEnd().endsWith('.') && !text.trimEnd().endsWith('.')) text = text.trimEnd() + "."
        return romanContext.replace(text) { match ->
            val value = romanToInt(match.groupValues[2].uppercase())
            if (value != null) "${match.groupValues[1]} $value" else match.value
        }
    }

    /** Valor de un número romano válido, o `null` si no lo es. */
    fun romanToInt(roman: String): Int? {
        if (roman.isEmpty() || roman.any { it !in romanValues }) return null
        var total = 0
        for (i in roman.indices) {
            val value = romanValues.getValue(roman[i])
            val next = roman.getOrNull(i + 1)?.let(romanValues::getValue) ?: 0
            total += if (value < next) -value else value
        }
        return total.takeIf { it > 0 && toRoman(it) == roman }
    }

    private fun toRoman(value: Int): String {
        val symbols = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        var rest = value
        return buildString {
            for ((amount, symbol) in symbols) {
                while (rest >= amount) {
                    append(symbol)
                    rest -= amount
                }
            }
        }
    }
}
