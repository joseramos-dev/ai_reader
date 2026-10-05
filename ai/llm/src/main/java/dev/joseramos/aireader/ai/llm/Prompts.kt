package dev.joseramos.aireader.ai.llm

import android.content.Context
import androidx.annotation.RawRes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prompts versionados en `res/raw` (`*_v1.txt`). Cambiar un prompt de forma significativa
 * debe crear una versión nueva en lugar de editar la existente. Las variables van como `{{nombre}}`.
 */
@Singleton
class Prompts @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun render(@RawRes id: Int, vararg values: Pair<String, Any>): String {
        val template = cache.getOrPut(id) {
            context.resources.openRawResource(id).bufferedReader().use { it.readText() }
        }
        return fill(template, *values)
    }

    val summarySystem: String get() = render(R.raw.summary_system_v1)

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
