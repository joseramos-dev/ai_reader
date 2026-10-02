import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = AndroidConfig.COMPILE_SDK
            defaultConfig {
                minSdk = AndroidConfig.MIN_SDK
                targetSdk = AndroidConfig.TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
