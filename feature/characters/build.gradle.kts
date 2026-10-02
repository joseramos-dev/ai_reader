plugins {
    alias(libs.plugins.aireader.android.feature)
}

android {
    namespace = "dev.joseramos.aireader.feature.characters"
}

// Menú de personajes, ficha de cada uno y grafo de relaciones.
dependencies {
    implementation(project(":ai:characters"))
}
