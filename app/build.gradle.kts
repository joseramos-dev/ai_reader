import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.aireader.android.application)
    alias(libs.plugins.aireader.android.compose)
    alias(libs.plugins.aireader.hilt)
}

// Modelos que vienen ya en el APK (ModelManager.installBundledModels): voces de Piper y el
// embedder de RAG. Como el AAR de sherpa-onnx en settings.gradle.kts, se descargan una vez a
// src/main/assets/bundled_models/ (gitignored, no se versionan) en vez de subirlos a git: pesan
// ~177 MB en total y uno de ellos ya supera el límite de 100 MB por archivo de GitHub.
// Los nombres, URLs y sha256 deben coincidir con ai/models/.../ModelCatalog.kt.
data class BundledModelFile(val modelId: String, val fileName: String, val url: String, val sha256: String)

val bundledModelFiles = listOf(
    BundledModelFile(
        "tts-piper-es_ES-davefx-medium-int8",
        "voice.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "vits-piper-es_ES-davefx-medium-int8.tar.bz2",
        "8bb8ac1cefb727caec9bd9c6c3185c673c8b42c53bd29bb25d5a7715dac37125"
    ),
    BundledModelFile(
        "tts-piper-en_US-lessac-medium-int8",
        "voice.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "vits-piper-en_US-lessac-medium-int8.tar.bz2",
        "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9"
    ),
    BundledModelFile(
        "emb-multilingual-e5-small-int8",
        "model.onnx",
        "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" +
            "761b726dd34fb83930e26aab4e9ac3899aa1fa78/onnx/model_quantized.onnx",
        "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
    ),
    BundledModelFile(
        "emb-multilingual-e5-small-int8",
        "tokenizer.json",
        "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" +
            "761b726dd34fb83930e26aab4e9ac3899aa1fa78/tokenizer.json",
        "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    )
)

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        var read = input.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val bundledModelsDir = file("src/main/assets/bundled_models")
bundledModelFiles.forEach { spec ->
    val dest = File(bundledModelsDir, "${spec.modelId}/${spec.fileName}")
    if (dest.exists() && sha256Of(dest) == spec.sha256) return@forEach
    dest.parentFile.mkdirs()
    logger.lifecycle("Descargando modelo empaquetado: ${spec.modelId}/${spec.fileName}")
    URI(spec.url).toURL().openStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    val actual = sha256Of(dest)
    check(actual == spec.sha256) {
        "sha256 incorrecto para ${dest.name}: esperado ${spec.sha256}, obtenido $actual"
    }
}

android {
    namespace = "dev.joseramos.aireader"

    defaultConfig {
        applicationId = "dev.joseramos.aireader"
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx y onnxruntime-android traen cada uno su lib/x86/libonnxruntime.so; x86 32-bit y armeabi-v7a no se usan.
            excludes += listOf("lib/x86/**", "lib/armeabi-v7a/**")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Variante para las pruebas de rendimiento (`:benchmark`): no depurable, como la de
        // producción, pero sin R8 hasta revisar sus reglas en F12, y firmada con la clave de depuración.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":ai:models"))
    implementation(project(":indexing"))
    implementation(project(":tts"))
    implementation(project(":ai:llm"))
    implementation(project(":ai:embeddings"))
    implementation(project(":ai:rag"))
    implementation(project(":ai:characters"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:characters"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
}
