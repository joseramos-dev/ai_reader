package dev.joseramos.aireader.ai.characters

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Respuesta del modelo para un capítulo (o un bloque de un capítulo largo). Todos los campos tienen
// valor por defecto: lo que falte o venga mal se descarta al fusionar, en lugar de romper el análisis.

@Serializable
data class ExtractedName(val name: String = "", val kind: String = "NAME", val page: Int = 0)

@Serializable
data class ExtractedFact(val page: Int = 0, val text: String = "")

@Serializable
data class ExtractedCharacter(
    val ref: String = "",
    val names: List<ExtractedName> = emptyList(),
    val renamedTo: ExtractedName? = null,
    val facts: List<ExtractedFact> = emptyList()
)

@Serializable
data class ExtractedRelation(
    val from: String = "",
    val to: String = "",
    val type: String = "OTHER",
    val label: String = "",
    val page: Int = 0,
    val ended: Boolean = false
)

@Serializable
data class Extraction(
    val characters: List<ExtractedCharacter> = emptyList(),
    val relations: List<ExtractedRelation> = emptyList(),
    val same: List<List<String>> = emptyList()
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        /** Extrae el objeto JSON de la respuesta (aunque venga con texto alrededor) o `null` si no es válido. */
        fun parse(answer: String): Extraction? {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { json.decodeFromString<Extraction>(answer.substring(start, end + 1)) }.getOrNull()
        }
    }
}
