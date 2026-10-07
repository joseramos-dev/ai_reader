plugins {
    alias(libs.plugins.aireader.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// RAG: indexación de fragmentos, búsqueda híbrida (vectorial + texto completo) y respuestas con citas.
kotlin {
    android {
        namespace = "dev.joseramos.aireader.ai.rag"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:data"))
            implementation(project(":text"))
            implementation(project(":indexing"))
            implementation(project(":ai:models"))
            implementation(project(":ai:embeddings"))
            implementation(project(":ai:llm"))
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}
