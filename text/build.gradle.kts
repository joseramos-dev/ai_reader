plugins {
    alias(libs.plugins.aireader.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.joseramos.aireader.text"
}

// Las líneas con su geometría (TextLine) se guardan como JSON.
dependencies {
    implementation(libs.kotlinx.serialization.json)
}
