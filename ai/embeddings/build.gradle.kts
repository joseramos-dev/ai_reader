plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.embeddings"
}

// Embeddings en el dispositivo: multilingual-e5-small (ONNX Runtime) con un tokenizador Unigram en Kotlin
// (el de DJL trae una librería nativa no alineada a 16 KB; se mantiene solo para comparar en las pruebas).
dependencies {
    implementation(project(":core:common"))
    implementation(project(":ai:models"))
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.djl.tokenizers)
}
