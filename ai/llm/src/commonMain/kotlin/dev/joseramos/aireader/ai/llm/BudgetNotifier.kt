package dev.joseramos.aireader.ai.llm

import dev.joseramos.aireader.core.data.settings.DailyUsage

/**
 * Avisa fuera de la app cuando el consumo del día cruza el 80 % o el 100 % del presupuesto, para que se entere
 * también quien tiene un análisis en segundo plano. Solo avisa: la IA sigue funcionando. Cada plataforma lo hace a
 * su manera (notificación del sistema en Android); dentro de la app ya se ve el aviso.
 */
interface BudgetNotifier {
    fun onUsageChanged(before: DailyUsage, after: DailyUsage)
}

/** Sin avisos fuera de la app (escritorio). */
object NoBudgetNotifier : BudgetNotifier {
    override fun onUsageChanged(before: DailyUsage, after: DailyUsage) = Unit
}
