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
    implementation(libs.commons.compress)
    testImplementation(libs.okhttp.mockwebserver)
}
