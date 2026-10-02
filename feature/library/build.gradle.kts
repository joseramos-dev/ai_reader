plugins {
    alias(libs.plugins.aireader.android.feature)
}

android {
    namespace = "dev.joseramos.aireader.feature.library"
}

dependencies {
    implementation(project(":indexing"))
    implementation(libs.coil.compose)
}
