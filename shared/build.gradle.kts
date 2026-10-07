plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

// La app compartida entre Android y escritorio: la navegación, el tema raíz y la unión de todos los módulos de Koin.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.shared"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":pdf"))
            implementation(project(":indexing"))
            implementation(project(":tts"))
            implementation(project(":ai:models"))
            implementation(project(":ai:llm"))
            implementation(project(":ai:embeddings"))
            implementation(project(":ai:rag"))
            implementation(project(":ai:characters"))
            implementation(project(":feature:library"))
            implementation(project(":feature:reader"))
            implementation(project(":feature:chat"))
            implementation(project(":feature:characters"))
            implementation(project(":feature:settings"))
            implementation(libs.haze)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.shared.generated.resources"
    publicResClass = false
}
