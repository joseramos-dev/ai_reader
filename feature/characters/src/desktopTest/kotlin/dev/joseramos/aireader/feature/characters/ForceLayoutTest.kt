package dev.joseramos.aireader.feature.characters

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceLayoutTest {
    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun linkedNodesEndUpCloserThanUnlinkedOnes() {
        // Dos grupos de tres personajes unidos entre sí, sin aristas entre grupos.
        val ids = (1L..6L).toList()
        val edges = listOf(1L to 2L, 2L to 3L, 1L to 3L, 4L to 5L, 5L to 6L, 4L to 6L)
        val layout = ForceLayout.layout(ids, edges)

        val inside = edges.map { (a, b) -> distance(layout.getValue(a), layout.getValue(b)) }.average()
        val across = listOf(1L to 4L, 2L to 5L, 3L to 6L).map { (a, b) ->
            distance(layout.getValue(a), layout.getValue(b))
        }.average()
        assertTrue("dentro $inside, entre grupos $across", inside < across)
    }

    @Test
    fun isDeterministicAndFiniteForFortyCharacters() {
        val ids = (1L..40L).toList()
        val edges = ids.zipWithNext() + listOf(1L to 20L, 5L to 35L)
        val first = ForceLayout.layout(ids, edges)
        val second = ForceLayout.layout(ids, edges)

        assertEquals(first, second)
        assertTrue(first.values.all { it.x.isFinite() && it.y.isFinite() })
        val points = first.values.toList()
        val closest = points.indices.flatMap { i ->
            (i + 1 until points.size).map { distance(points[i], points[it]) }
        }.min()
        assertTrue("dos nodos casi encima: $closest", closest > 0.05f)
    }

    @Test
    fun handlesTinyGraphs() {
        assertTrue(ForceLayout.layout(emptyList(), emptyList()).isEmpty())
        assertEquals(Point(0f, 0f), ForceLayout.layout(listOf(9L), emptyList())[9L])
    }
}
