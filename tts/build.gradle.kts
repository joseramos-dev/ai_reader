plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.tts"
}

// Voz del sistema (android.speech.tts) y de Google Cloud TTS (REST con OkHttp), pipeline de
// audio y servicio de reproducción (Media3).
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":text"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
