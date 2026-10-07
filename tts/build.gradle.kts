plugins {
    alias(libs.plugins.aireader.kmp.library)
}

// Lectura en voz alta: motor de reproducción común sobre la voz y la salida de audio de cada plataforma
// (Android: android.speech.tts, AudioTrack y un servicio Media3; Windows: voces SAPI y Java Sound).
kotlin {
    android {
        namespace = "dev.joseramos.aireader.tts"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(project(":text"))
        }
        getByName("androidMain").dependencies {
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
            api(libs.androidx.media3.session)
            api(libs.androidx.media3.common)
            implementation(libs.kotlinx.coroutines.guava)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}
