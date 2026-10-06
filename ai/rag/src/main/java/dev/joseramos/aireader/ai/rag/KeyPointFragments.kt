package dev.joseramos.aireader.ai.rag

import dev.joseramos.aireader.core.data.book.Chapter
import dev.joseramos.aireader.core.data.book.ChapterKeyPoints
import dev.joseramos.aireader.core.data.book.KeyPoint
import dev.joseramos.aireader.core.data.db.KeyPointsStatus

/**
 * Lo que aporta cada capítulo a una pregunta respondida con hechos clave: [fragments], un fragmento por
 * capítulo con sus hechos clave, y [withoutKeyPoints], los capítulos que no los tienen (Gemini se negó
 * a enumerarlos o no se pudieron generar), de los que se manda su texto.
 */
data class KeyPointsPlan(val fragments: List<Pair<Chapter, Fragment>>, val withoutKeyPoints: List<Chapter>)

object KeyPointFragments {
    /**
     * Para cada capítulo de [chapters], sus hechos clave que pasan [include] (los que ocurren en las
     * páginas de la pregunta y, con anti-spoilers, hasta [limit]). Un capítulo sin texto, o al que no le
     * queda ningún hecho, no aporta nada. Los fragmentos llegan sin numerar (número 0).
     */
    fun plan(
        chapters: List<Chapter>,
        saved: Map<Long, ChapterKeyPoints>,
        limit: Int?,
        include: (KeyPoint) -> Boolean = { true }
    ): KeyPointsPlan {
        val fragments = mutableListOf<Pair<Chapter, Fragment>>()
        val without = mutableListOf<Chapter>()
        for (chapter in chapters) {
            val keyPoints = saved[chapter.id]
            when (keyPoints?.status) {
                KeyPointsStatus.EMPTY -> Unit
                KeyPointsStatus.READY -> {
                    val points = keyPoints.until(limit).filter(include)
                    if (points.isNotEmpty()) fragments += chapter to fragment(chapter, points, limit)
                }
                KeyPointsStatus.REFUSED, null -> without += chapter
            }
        }
        return KeyPointsPlan(fragments, without)
    }

    /**
     * El fragmento de un capítulo: sus hechos clave, uno por línea con su página («- texto [p. N]»).
     * Abarca el capítulo, como mucho hasta [limit]: las citas a otras páginas suyas también valen.
     */
    fun fragment(chapter: Chapter, points: List<KeyPoint>, limit: Int?): Fragment {
        val end = if (limit == null) chapter.endPage else minOf(chapter.endPage, limit)
        return Fragment(
            number = 0,
            text = points.joinToString("\n") { "- ${it.text} [p. ${it.page}]" },
            startPage = chapter.startPage,
            endPage = maxOf(chapter.startPage, end),
            chapter = chapter.title,
            keyPoints = true
        )
    }
}
