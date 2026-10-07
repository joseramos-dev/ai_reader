plugins {
    alias(libs.plugins.aireader.android.library)
}

android {
    namespace = "dev.joseramos.aireader.tts"
}

// Voz del sistema (android.speech.tts), pipeline de audio y servicio de reproducción (Media3).
dependencies {
    implementation(libs.koin.android)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":text"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.guava)
}
