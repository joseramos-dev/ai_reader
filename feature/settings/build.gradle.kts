plugins {
    alias(libs.plugins.aireader.android.feature)
}

android {
    namespace = "dev.joseramos.aireader.feature.settings"
}

dependencies {
    implementation(project(":ai:models"))
    implementation(project(":tts"))
    implementation(project(":ai:rag"))
}
