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
        return values.fold(template) { text, (key, value) -> text.replace("{{$key}}", value.toString()) }.trim()
    }

    val summarySystem: String get() = render(R.raw.summary_system_v1)
}
