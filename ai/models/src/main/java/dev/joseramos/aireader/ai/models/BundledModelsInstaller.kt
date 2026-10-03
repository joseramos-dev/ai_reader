package dev.joseramos.aireader.ai.models

import dev.joseramos.aireader.core.common.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Al arrancar la app, instala los modelos que vienen empaquetados en el APK (ver [ModelManager]). */
class BundledModelsInstaller @Inject constructor(
    private val models: ModelManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        scope.launch { models.installBundledModels() }
    }
}
