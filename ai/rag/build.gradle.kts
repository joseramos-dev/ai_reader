plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.rag"
    // Las pruebas JVM pasan por `android.util.Log` en los caminos de error: que no haga nada.
    testOptions { unitTests.isReturnDefaultValues = true }
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
