plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Embeddings en el dispositivo: multilingual-e5-small (ONNX Runtime) con un tokenizador Unigram en Kotlin
// (el de DJL trae una librería nativa no alineada a 16 KB; se mantiene solo para comparar en las pruebas).
kotlin {
    android {
        namespace = "dev.joseramos.aireader.ai.embeddings"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":ai:models"))
            implementation(libs.kotlinx.serialization.json)
        }
        // ONNX Runtime tiene la misma API (ai.onnxruntime) con la librería nativa de cada plataforma.
        getByName("androidMain").dependencies {
            implementation(libs.onnxruntime.android)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.onnxruntime)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.djl.tokenizers)
        }
    }
}
