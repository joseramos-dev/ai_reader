package dev.joseramos.aireader.feature.characters

import kotlin.math.sqrt
import kotlin.random.Random

/** Posición de un nodo en unidades del grafo (la distancia ideal entre nodos unidos es 1). */
data class Point(val x: Float, val y: Float)

/**
 * Disposición por fuerzas (Fruchterman–Reingold): los nodos se repelen entre sí, las aristas
 * atraen a los nodos que unen y una gravedad suave mantiene juntos los grupos sueltos. Es
 * determinista (la posición inicial depende del id) y, si se le pasan posiciones previas, parte de
 * ellas para que el grafo no salte al cambiar un filtro. Para unas decenas de nodos tarda
 * milisegundos; se ejecuta fuera del hilo principal.
 */
object ForceLayout {
    private const val ITERATIONS = 300
    private const val GRAVITY = 0.05f
    private const val COOLING = 0.98f
    private const val MIN_DISTANCE = 0.01f
    private const val INITIAL_TEMPERATURE = 0.1f
    private const val SEED = 7
    private const val HALF = 0.5f

    fun layout(
        ids: List<Long>,
        edges: List<Pair<Long, Long>>,
        initial: Map<Long, Point> = emptyMap()
    ): Map<Long, Point> {
        if (ids.isEmpty()) return emptyMap()
        if (ids.size == 1) return mapOf(ids.single() to Point(0f, 0f))
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val links = edges.mapNotNull { (a, b) ->
            val i = index[a] ?: return@mapNotNull null
            val j = index[b] ?: return@mapNotNull null
            (i to j).takeIf { i != j }
        }
        val spread = sqrt(ids.size.toFloat())
        val state = State(ids, initial, spread)
        var temperature = spread * INITIAL_TEMPERATURE
        repeat(ITERATIONS) {
            state.step(links, temperature)
            temperature *= COOLING
        }
        return state.centered(ids)
    }

    private class State(ids: List<Long>, initial: Map<Long, Point>, spread: Float) {
        private val n = ids.size
        private val x = FloatArray(n)
        private val y = FloatArray(n)
        private val dx = FloatArray(n)
        private val dy = FloatArray(n)

        init {
            ids.forEachIndexed { i, id ->
                val start = initial[id]
                val random = Random(SEED + id.hashCode())
                x[i] = start?.x ?: ((random.nextFloat() - HALF) * spread)
                y[i] = start?.y ?: ((random.nextFloat() - HALF) * spread)
            }
        }

        fun step(links: List<Pair<Int, Int>>, temperature: Float) {
            dx.fill(0f)
            dy.fill(0f)
            // Repulsión k²/d entre todos los pares (k = 1).
            for (i in 0 until n) {
                for (j in i + 1 until n) push(i, j) { dist -> 1f / dist }
            }
            // Atracción d²/k a lo largo de cada arista (fuerza negativa: acerca).
            for ((i, j) in links) push(i, j) { dist -> -dist * dist }
            for (i in 0 until n) {
                dx[i] -= x[i] * GRAVITY
                dy[i] -= y[i] * GRAVITY
                val length = sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(MIN_DISTANCE)
                val move = minOf(length, temperature)
                x[i] += dx[i] / length * move
                y[i] += dy[i] / length * move
            }
        }

        /** Aplica a [i] y [j] una fuerza en la dirección que los separa (positiva) o los une (negativa). */
        private inline fun push(i: Int, j: Int, force: (Float) -> Float) {
            val ddx = x[i] - x[j]
            val ddy = y[i] - y[j]
            val dist = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(MIN_DISTANCE)
            val f = force(dist)
            dx[i] += ddx / dist * f
            dy[i] += ddy / dist * f
            dx[j] -= ddx / dist * f
            dy[j] -= ddy / dist * f
        }

        fun centered(ids: List<Long>): Map<Long, Point> {
            val cx = x.average().toFloat()
            val cy = y.average().toFloat()
            return ids.withIndex().associate { (i, id) -> id to Point(x[i] - cx, y[i] - cy) }
        }
    }
}
