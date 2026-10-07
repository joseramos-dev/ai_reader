plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.aireader.kmp.compose)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.shared"
    }
}
