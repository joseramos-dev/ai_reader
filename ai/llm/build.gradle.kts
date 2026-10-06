plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.llm"
}

// Cliente de la API de Gemini (REST con OkHttp), prompts, hechos clave y repasos.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":indexing"))
    implementation(project(":text"))
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore.preferences)
}
