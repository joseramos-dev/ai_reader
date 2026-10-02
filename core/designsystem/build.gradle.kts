plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.aireader.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.joseramos.aireader.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)
    api(libs.haze)
    api(libs.haze.blur)
    implementation(libs.androidx.core.ktx)

    // Capturas de los componentes en la JVM: ./gradlew :core:designsystem:recordRoborazziDebug
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
