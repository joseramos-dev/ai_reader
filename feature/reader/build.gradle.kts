plugins {
    alias(libs.plugins.aireader.android.feature)
}

android {
    namespace = "dev.joseramos.aireader.feature.reader"
}

dependencies {
    implementation(project(":pdf"))
    implementation(project(":text"))
    implementation(project(":tts"))
    implementation(project(":ai:models"))
    implementation(project(":ai:llm"))
    implementation(project(":ai:characters"))
    implementation(project(":feature:characters"))
    implementation(libs.coil.compose)
}
