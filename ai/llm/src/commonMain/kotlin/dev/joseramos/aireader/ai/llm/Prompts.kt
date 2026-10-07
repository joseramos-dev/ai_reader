package dev.joseramos.aireader.ai.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Prompts versionados en `resources/prompts/<nombre>.txt` (`*_v1`) de cada módulo; se leen del classpath, que en
 * Android y en escritorio incluye los recursos de todos los módulos. Cambiar un prompt de forma significativa
 * debe crear una versión nueva en lugar de editar la existente. Las variables van como `{{nombre}}`.
 */
class Prompts {
    private val cache = ConcurrentHashMap<String, String>()

    fun render(name: String, vararg values: Pair<String, Any>): String {
        val template = cache.getOrPut(name) {
            val stream = checkNotNull(Prompts::class.java.classLoader?.getResourceAsStream("prompts/$name.txt")) {
                "Falta el prompt $name"
            }
            stream.bufferedReader().use { it.readText() }
        }
        return fill(template, *values)
    }

    companion object {
        // Una sola pasada sobre la plantilla original: si un valor insertado (texto del libro, por
        // ejemplo) contiene literalmente "{{otraClave}}", no se vuelve a sustituir por error.
        private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")

        /** Sustituye las variables de [template]. Público para las pruebas que leen la plantilla del disco. */
        fun fill(template: String, vararg values: Pair<String, Any>): String {
            val byKey = values.toMap()
            return PLACEHOLDER.replace(template) { match ->
                byKey[match.groupValues[1]]?.toString() ?: match.value
            }.trim()
        }
    }
}
