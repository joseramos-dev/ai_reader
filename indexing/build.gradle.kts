plugins {
    alias(libs.plugins.aireader.android.library)
}

android {
    namespace = "dev.joseramos.aireader.indexing"
}

// Importación de libros e indexación en segundo plano (texto, capítulos y embeddings).
dependencies {
    implementation(libs.koin.androidx.workmanager)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":pdf"))
    implementation(project(":text"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
}
