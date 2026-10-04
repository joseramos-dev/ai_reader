pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AIReader"

include(":app")

// Núcleo
include(":core:common")
include(":core:designsystem")
include(":core:data")

// Dominio técnico
include(":pdf")
include(":text")
include(":tts")
include(":indexing")
include(":ai:llm")
include(":ai:embeddings")
include(":ai:rag")
include(":ai:characters")
include(":ai:models")

// Pantallas
include(":feature:library")
include(":feature:reader")
include(":feature:chat")
include(":feature:characters")
include(":feature:settings")

// Pruebas de rendimiento (Macrobenchmark) sobre la variante `benchmark` de la app.
include(":benchmark")
