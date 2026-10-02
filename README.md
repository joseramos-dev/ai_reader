# AI Reader

Lector de PDF para Android con lectura en voz alta y funciones de IA sobre el documento: resúmenes por capítulo y chat con el libro (RAG) con citas de página.

- App nativa en **Kotlin + Jetpack Compose**, sin servidor propio.
- Voz (TTS), embeddings, índice y búsqueda se ejecutan **en el dispositivo**.
- La generación de texto usa la **API de Claude** con la clave del propio usuario.

> En desarrollo. El prototipo anterior (React + FastAPI) está en la etiqueta `prototipo-web` y en la rama `legacy/prototipo-web`.

## Requisitos

- Android Studio (reciente) con el SDK de Android 37 instalado.
- JDK 25 para ejecutar Gradle. Sirve el JBR incluido con Android Studio; si no está disponible, Gradle lo descarga automáticamente (ver `gradle/gradle-daemon-jvm.properties`).
- Un dispositivo o emulador con Android 9 (API 28) o superior.

## Compilar y ejecutar

Abre la carpeta del repositorio en Android Studio y ejecuta la configuración `app`, o desde la terminal:

```bash
./gradlew assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Para instalarla en un dispositivo conectado:

```bash
./gradlew installDebug
```

## Calidad de código

```bash
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest
```

- `./gradlew ktlintFormat` corrige automáticamente el formato.
- La configuración de detekt está en [config/detekt/detekt.yml](config/detekt/detekt.yml).
- El CI de GitHub Actions ([.github/workflows/android.yml](.github/workflows/android.yml)) ejecuta estas comprobaciones y compila la APK en cada push a `main` y en cada pull request.

## Estructura

```
app/                 Módulo de la aplicación
config/detekt/       Configuración de análisis estático
gradle/              Wrapper y catálogo de versiones (libs.versions.toml)
```

Los módulos de funcionalidades (`:core:*`, `:feature:*`, `:ai:*`, `:tts`, `:pdf`, `:text`) se irán añadiendo según avance el desarrollo.
