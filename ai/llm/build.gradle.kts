plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.llm"
}

// Cliente de la API de Claude (SDK oficial de Java), prompts y resúmenes.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":indexing"))
    implementation(project(":text"))
    implementation(libs.anthropic.java)
    implementation(libs.kotlinx.serialization.json)
}
