plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.rag"
}

// RAG: indexación de fragmentos, búsqueda híbrida (vectorial + texto completo) y respuestas con citas.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":text"))
    implementation(project(":indexing"))
    implementation(project(":ai:models"))
    implementation(project(":ai:embeddings"))
    implementation(project(":ai:llm"))
    implementation(libs.kotlinx.serialization.json)
}
