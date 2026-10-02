plugins {
    alias(libs.plugins.aireader.android.feature)
}

android {
    namespace = "dev.joseramos.aireader.feature.chat"
}

dependencies {
    implementation(project(":ai:rag"))
    implementation(project(":ai:llm"))
    implementation(project(":ai:models"))
    implementation(project(":ai:embeddings"))
    implementation(project(":indexing"))
}
