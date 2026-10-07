import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.aireader.android.application)
    alias(libs.plugins.aireader.android.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Modelos que vienen ya en el APK (ModelManager.installBundledModels): el embedder de RAG. Se
// descargan una vez a src/main/assets/bundled_models/ (gitignored, no se versionan) en vez de
// subirlos a git: pesan ~135 MB y el modelo supera el límite de 100 MB por archivo de GitHub.
// Los nombres, URLs y sha256 deben coincidir con ai/models/.../ModelCatalog.kt.
data class BundledModelFile(val modelId: String, val fileName: String, val url: String, val sha256: String)

val bundledModelFiles = listOf(
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
// Lo que ya no está en la lista (por ejemplo, las antiguas voces de Piper) se borra para que no se empaquete.
val bundledModelIds = bundledModelFiles.map { it.modelId }.toSet()
bundledModelsDir.listFiles()?.filter { it.name !in bundledModelIds }?.forEach { stale ->
    logger.lifecycle("Borrando modelo empaquetado retirado: ${stale.name}")
    stale.deleteRecursively()
}
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
            // onnxruntime-android trae también lib/x86 y armeabi-v7a, que no se usan.
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
    implementation(project(":shared"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    // Baseline Profile generado por :benchmark (./gradlew :app:generateBaselineProfile).
    baselineProfile(project(":benchmark"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)

    testImplementation(libs.junit)
}
