plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

subprojects {
    // Los nodos intermedios (":core", ":ai", ":feature") no son módulos.
    if (!file("build.gradle.kts").exists()) return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        // Los módulos multiplataforma tienen varios conjuntos de fuentes (commonMain, androidMain, desktopMain…).
        source.setFrom(
            fileTree("src") {
                include("**/*.kt")
                // Las pruebas (test, commonTest, desktopTest, androidHostTest…) no se analizan.
                exclude("**/test/**", "**/*Test/**")
            }
        )
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }
}
