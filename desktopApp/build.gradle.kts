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

// El modelo de embeddings que `:app` ya descarga (src/main/assets/bundled_models) viaja también en el instalador:
// lo que haya en `appResources/common` se copia a la carpeta de recursos (`compose.application.resources.dir`).
// Si todavía no se ha descargado, el instalador sale sin él y la app lo baja la primera vez.
val copyBundledModels = tasks.register<Copy>("copyBundledModels") {
    from(layout.projectDirectory.dir("../app/src/main/assets/bundled_models"))
    into(layout.buildDirectory.dir("appResources/common/bundled_models"))
}
tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(copyBundledModels) }

compose.desktop {
    application {
        mainClass = "dev.joseramos.aireader.desktop.MainKt"
        // jlink necesita un JDK con carpeta `jmods` (el que descarga Gradle a veces no la trae):
        // -Paireader.jdkHome="C:/Program Files/Java/jdk-26.0.1"
        providers.gradleProperty("aireader.jdkHome").orNull?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            packageName = "AIReader"
            packageVersion = "0.1.1"
            description = "Lector de PDF con IA"
            vendor = "joseramos-dev"
            // El runtime recortado de jlink se quedaba sin módulos que usan Room, JNA y ONNX Runtime.
            includeAllModules = true
            windows {
                menuGroup = "AI Reader"
                iconFile.set(project.file("src/main/resources/icon.ico"))
                shortcut = true
                dirChooser = true
                // Identificador fijo para que una versión nueva actualice a la anterior en vez de instalarse al lado.
                upgradeUuid = "5b0d7a3e-8f2c-4c61-9a4e-1d6f0b7c2e90"
            }
        }
    }
}
