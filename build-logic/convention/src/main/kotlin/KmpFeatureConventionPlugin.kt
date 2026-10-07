import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Módulo de pantalla compartido entre Android y escritorio: librería KMP + Compose Multiplatform + Koin + navegación
 * con rutas tipadas. Cada módulo pone su `namespace` en `kotlin { android { ... } }`.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("aireader.kmp.library")
        pluginManager.apply("aireader.kmp.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<KotlinMultiplatformExtension> {
            // Los textos de las pantallas viven en los recursos de Compose (composeResources).
            targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
                androidResources { enable = true }
            }
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":core:common"))
                implementation(project(":core:designsystem"))
                implementation(project(":core:data"))
                implementation(libs.lib("jb-navigation-compose"))
                implementation(libs.lib("jb-lifecycle-runtime-compose"))
                implementation(libs.lib("jb-lifecycle-viewmodel-compose"))
                implementation(libs.lib("koin-compose"))
                implementation(libs.lib("koin-compose-viewmodel"))
                implementation(libs.lib("kotlinx-serialization-json"))
            }
        }
    }
}
