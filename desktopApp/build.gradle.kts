import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:common"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
}

compose.desktop {
    application {
        mainClass = "dev.joseramos.aireader.desktop.MainKt"
        // jlink necesita un JDK con carpeta `jmods` (el que descarga Gradle a veces no la trae):
        // -Paireader.jdkHome="C:/Program Files/Java/jdk-26.0.1"
        providers.gradleProperty("aireader.jdkHome").orNull?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AIReader"
            packageVersion = "0.1.0"
            description = "Lector de PDF con IA"
            vendor = "joseramos-dev"
            // El runtime recortado de jlink se quedaba sin módulos que usan Room, JNA y ONNX Runtime.
            includeAllModules = true
            windows {
                menuGroup = "AI Reader"
                shortcut = true
                dirChooser = true
                // Identificador fijo para que una versión nueva actualice a la anterior en vez de instalarse al lado.
                upgradeUuid = "5b0d7a3e-8f2c-4c61-9a4e-1d6f0b7c2e90"
            }
        }
    }
}
