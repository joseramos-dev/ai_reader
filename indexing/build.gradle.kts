plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
}

android {
    namespace = "dev.joseramos.aireader.indexing"
}

// Importación de libros e indexación en segundo plano (texto, capítulos y embeddings).
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":pdf"))
    implementation(project(":text"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
}
