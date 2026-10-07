package dev.joseramos.aireader.ai.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Al arrancar la app, instala los modelos que vienen empaquetados en el APK (ver [ModelManager]). */
class BundledModelsInstaller(private val models: ModelManager, private val scope: CoroutineScope) {
    fun start() {
        scope.launch { models.installBundledModels() }
    }
}
