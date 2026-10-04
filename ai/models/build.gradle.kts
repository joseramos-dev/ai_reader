plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.hilt)
}

android {
    namespace = "dev.joseramos.aireader.ai.models"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
