import androidx.room.gradle.RoomExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Room multiplataforma con KSP y exportación del esquema a `schemas/` para versionar las migraciones. Va
 * sobre [KmpLibraryConventionPlugin] y usa siempre el driver de SQLite empaquetado (el mismo SQLite en
 * Android y en escritorio).
 */
class KmpRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")
        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }
        // Sin esto, Room (KSP) no genera nada para los objetivos que no son Android.
        extensions.configure<KspExtension> {
            arg("room.generateKotlin", "true")
        }
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                // AppDatabase aparece en la API pública del módulo.
                api(libs.lib("androidx-room-runtime"))
                implementation(libs.lib("androidx-sqlite-bundled"))
            }
        }
        // El procesador de Room del objetivo de escritorio. El de Android lo añade cada módulo en su
        // build.gradle.kts, después de declarar el objetivo (la configuración `kspAndroid` aún no existe aquí).
        dependencies {
            add("kspDesktop", libs.lib("androidx-room-compiler"))
        }
    }
}
