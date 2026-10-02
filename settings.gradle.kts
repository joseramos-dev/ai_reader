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

// sherpa-onnx (TTS en el dispositivo) solo se publica como AAR en GitHub. Se descarga una vez a un
// repositorio Maven local (ignorado en git) para poder usarlo como dependencia normal desde `:tts`.
val sherpaVersion = "1.13.8"
val sherpaRepo = file("third-party/maven")
val sherpaDir = File(sherpaRepo, "com/k2fsa/sherpa/onnx/sherpa-onnx-static/$sherpaVersion")
val sherpaAar = File(sherpaDir, "sherpa-onnx-static-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaDir.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
    java.net.URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    File(sherpaDir, "sherpa-onnx-static-$sherpaVersion.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.k2fsa.sherpa.onnx</groupId>
          <artifactId>sherpa-onnx-static</artifactId>
          <version>$sherpaVersion</version>
          <packaging>aar</packaging>
        </project>
        """.trimIndent()
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = sherpaRepo.toURI()
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
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

// App desechable para las pruebas técnicas de la fase F1 (ver docs/03-plan-por-fases.md).
include(":spikes")
