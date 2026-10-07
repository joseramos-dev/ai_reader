plugins {
    alias(libs.plugins.aireader.kmp.feature)
}

kotlin {
    android {
        namespace = "dev.joseramos.aireader.feature.chat"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":ai:rag"))
            implementation(project(":ai:llm"))
            implementation(project(":ai:models"))
            implementation(project(":ai:embeddings"))
            implementation(project(":indexing"))
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.joseramos.aireader.feature.chat.generated.resources"
    publicResClass = false
}
