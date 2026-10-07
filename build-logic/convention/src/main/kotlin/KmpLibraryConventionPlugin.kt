import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Módulo de librería compartido entre Android y escritorio (Windows): un único `commonMain` con
 * `androidMain` y `desktopMain` para lo específico de cada plataforma. Cada módulo pone su
 * `namespace` en `kotlin { android { ... } }`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                compileSdk = AndroidConfig.COMPILE_SDK
                minSdk = AndroidConfig.MIN_SDK
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            jvm("desktop") {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.lib("kotlinx-coroutines-core"))
                implementation(libs.lib("koin-core"))
            }
            // Dispatchers.Main: el hilo principal de Android y el de Swing en escritorio.
            sourceSets.getByName("androidMain").dependencies {
                implementation(libs.lib("kotlinx-coroutines-android"))
            }
            sourceSets.getByName("desktopMain").dependencies {
                implementation(libs.lib("kotlinx-coroutines-swing"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.lib("kotlin-test"))
                implementation(libs.lib("kotlinx-coroutines-test"))
            }
        }
        tasks.withType<Test>().configureEach { failOnNoDiscoveredTests.set(false) }
    }
}
