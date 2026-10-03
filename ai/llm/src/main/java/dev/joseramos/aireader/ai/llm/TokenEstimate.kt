package dev.joseramos.aireader.ai.llm

/**
 * Tokens que gastaría una tarea de IA, estimados en local por el tamaño del texto (≈4 caracteres
 * por token), sin llamar a la API: sirve para avisar antes de una tarea grande, no para facturar.
 */
data class TokenEstimate(val input: Long = 0, val output: Long = 0) {
    val total: Long get() = input + output

    operator fun plus(other: TokenEstimate) = TokenEstimate(input + other.input, output + other.output)

    companion object {
        /** Instrucciones del sistema y plantilla que acompañan a cada petición. */
        const val PROMPT_OVERHEAD_TOKENS = 1_000L
    }
}
