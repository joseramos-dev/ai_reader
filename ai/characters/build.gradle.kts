plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.ai.characters"
}

// Personajes de las novelas: extracción progresiva con IA, fusión de apodos y reglas sin spoilers.
dependencies {
    implementation(libs.koin.androidx.workmanager)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":indexing"))
    implementation(project(":text"))
    implementation(project(":ai:llm"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
