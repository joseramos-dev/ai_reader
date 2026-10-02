import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// sherpa-onnx no se publica en Maven: se descarga su AAR de GitHub la primera vez.
// Se usa la variante con ONNX Runtime enlazado estáticamente para no chocar con
// el libonnxruntime.so de onnxruntime-android (que usan los embeddings).
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
}

android {
    namespace = "dev.joseramos.aireader.spikes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.joseramos.aireader.spikes"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-spikes"

        ndk {
            // arm64 para móviles reales y x86_64 para el emulador.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // ABIs no usadas; además ambas librerías traen su propio lib/x86/libonnxruntime.so.
            excludes += listOf("lib/x86/**", "lib/armeabi-v7a/**")
        }
        resources {
            excludes += listOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        // Código desechable: los avisos de lint no deben bloquear la compilación.
        abortOnError = false
    }
}

dependencies {
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.onnxruntime.android)
    implementation(libs.pdfbox.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.djl.tokenizers)
    runtimeOnly(libs.djl.tokenizer.native.android)
}
