package dev.joseramos.aireader.feature.chat

import dev.joseramos.aireader.ai.models.ModelState
import dev.joseramos.aireader.core.data.db.IndexStatus

/** Si se puede preguntar y, si no, por qué (para explicarlo con una acción). */
sealed interface ChatAvailability {
    /** Aún no se ha leído el libro de la base de datos (un instante): no se muestra nada. */
    data object Loading : ChatAvailability

    /** Se puede preguntar. [search] dice si la búsqueda es la completa y, si no, por qué. */
    data class Ready(val search: SearchQuality = SearchQuality.Full) : ChatAvailability

    data object NoApiKey : ChatAvailability

    /** La indexación está en marcha y el libro aún no tiene fragmentos en los que buscar. */
    data class Indexing(val progress: Float) : ChatAvailability

    /** La indexación terminó sin fragmentos para el chat (falló esa etapa): se puede reintentar. */
    data object Unavailable : ChatAvailability

    data object Failed : ChatAvailability
}

/** Búsqueda con la que se responde. Sin la semántica, se busca solo por palabras del texto. */
sealed interface SearchQuality {
    /** Por significado (embeddings) y por palabras. */
    data object Full : SearchQuality

    /** Se están calculando los vectores; mientras, se busca por palabras. [progress] de 0 a 1. */
    data class Improving(val progress: Float) : SearchQuality

    /** Falta el modelo de embeddings: se puede descargar para mejorar las respuestas. */
    data class ModelMissing(val model: ModelState) : SearchQuality

    /** Los vectores no se han podido calcular en este móvil (o la etapa se saltó tras fallar). */
    data object Unsupported : SearchQuality
}

private val INDEXING = setOf(IndexStatus.PENDING, IndexStatus.EXTRACTING_TEXT, IndexStatus.EMBEDDING)

/** Parte de `indexProgress` que corresponde al texto y los capítulos (como en `IndexWorker`). */
private const val TEXT_SHARE = 0.5f

/**
 * Decide qué muestra el chat. Solo se enseña progreso mientras la indexación está en marcha y aún
 * no hay fragmentos; en cuanto los hay, se puede preguntar (por palabras si faltan los vectores).
 */
fun chatAvailability(
    status: IndexStatus?,
    progress: Float,
    hasKey: Boolean,
    model: ModelState,
    hasChunks: Boolean
): ChatAvailability = when {
    !hasKey -> ChatAvailability.NoApiKey
    status == null -> ChatAvailability.Loading
    status == IndexStatus.FAILED && !hasChunks -> ChatAvailability.Failed
    status == IndexStatus.PENDING || status == IndexStatus.EXTRACTING_TEXT -> ChatAvailability.Indexing(progress)
    !hasChunks -> when (status) {
        IndexStatus.EMBEDDING -> ChatAvailability.Indexing(progress)
        // Un libro sin texto (escaneado) termina «listo» sin fragmentos: se deja preguntar igual.
        IndexStatus.READY -> ChatAvailability.Ready()
        else -> ChatAvailability.Unavailable
    }
    else -> ChatAvailability.Ready(searchQuality(status, progress, model))
}

private fun searchQuality(status: IndexStatus, progress: Float, model: ModelState): SearchQuality = when {
    status in INDEXING -> SearchQuality.Improving(((progress - TEXT_SHARE) / (1 - TEXT_SHARE)).coerceIn(0f, 1f))
    model !is ModelState.Installed -> SearchQuality.ModelMissing(model)
    status == IndexStatus.READY -> SearchQuality.Full
    else -> SearchQuality.Unsupported
}
