plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
}

android {
    namespace = "dev.joseramos.aireader.ai.embeddings"
}

// Embeddings en el dispositivo: multilingual-e5-small (ONNX Runtime) con el tokenizador de DJL.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":ai:models"))
    implementation(libs.onnxruntime.android)
    implementation(libs.djl.tokenizers)
    runtimeOnly(libs.djl.tokenizer.native.android)
}
