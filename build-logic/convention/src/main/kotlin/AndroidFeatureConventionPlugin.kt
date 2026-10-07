import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Módulo de pantalla: librería Android + Compose + Koin + navegación con rutas tipadas. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("aireader.android.library")
        pluginManager.apply("aireader.android.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:data"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("koin-compose-viewmodel"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("koin-compose"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
        }
    }
}
