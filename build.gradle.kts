plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
}

subprojects {
    // Los nodos intermedios (":core", ":ai", ":feature") no son módulos.
    if (!file("build.gradle.kts").exists()) return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // Los spikes son código desechable: solo se les aplica el formato (ktlint).
    if (name == "spikes") return@subprojects
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }
}
