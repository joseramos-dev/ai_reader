plugins {
    alias(libs.plugins.aireader.android.application)
    alias(libs.plugins.aireader.android.compose)
    alias(libs.plugins.aireader.hilt)
}

android {
    namespace = "dev.joseramos.aireader"

    defaultConfig {
        applicationId = "dev.joseramos.aireader"
        versionCode = 1
        versionName = "0.1.0"
    }

    packaging {
        jniLibs {
            // sherpa-onnx y onnxruntime-android traen cada uno su lib/x86/libonnxruntime.so; x86 de 32 bits no se usa.
            excludes += "lib/x86/**"
            // De fbjni solo se usa libc++_shared.so (para el tokenizador de DJL).
            excludes += "lib/*/libfbjni.so"
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
