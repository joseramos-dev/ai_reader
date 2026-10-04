# AI Reader

Lector de PDF para Android con lectura en voz alta y funciones de IA sobre el documento: resúmenes por capítulo, repaso de lo leído, chat con el libro (RAG) con citas de página y, en novelas, personajes y relaciones sin spoilers.

- App nativa en **Kotlin + Jetpack Compose**, sin servidor propio.
- Voz (TTS), embeddings, índice y búsqueda se ejecutan **en el dispositivo**.
- La generación de texto usa la **API de Gemini** (Google AI Studio) con la clave del propio usuario, que tiene nivel gratuito.

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
app/                 Aplicación: Application (Hilt + WorkManager), MainActivity y navegación
core/common          Dispatchers y ámbitos de corrutinas inyectables
core/designsystem    Tema (colores, Inter, espaciados) y componentes tipo iOS
core/data            Room, DataStore, clave de API cifrada (Tink) y repositorios
pdf                  Render de páginas (PdfRenderer) y extracción de texto e índice (PdfBox)
text                 Limpieza del texto, frases, fragmentos, normalización para la voz y buscador de nombres
tts                  Voz del sistema (android.speech.tts), pipeline de audio y servicio de reproducción (Media3)
indexing             Importación de PDFs e IndexWorker: texto, capítulos, tipo de documento y embeddings
ai/models            Descarga, verificación e instalación de modelos (embeddings)
ai/llm               Cliente de Gemini (REST), prompts, resúmenes y repaso
ai/embeddings        Embeddings en el dispositivo (multilingual-e5-small con ONNX Runtime)
ai/rag               Búsqueda híbrida, respuestas con citas y evaluación de la búsqueda
ai/characters        Personajes: extracción con IA, fusión de apodos y reglas sin spoilers
feature/library      Pantalla Biblioteca
feature/reader       Lector (PDF y texto), voz, marcapáginas y hojas de IA
feature/chat         Chat con el libro
feature/characters   Personajes, ficha y grafo de relaciones
feature/settings     Pantalla Ajustes
benchmark/           Pruebas de rendimiento con Macrobenchmark
build-logic/         Plugins de convención de Gradle compartidos por los módulos
tools/rag-eval/      Formato y plantilla del conjunto de preguntas para evaluar la búsqueda
config/detekt/       Configuración de análisis estático
gradle/              Wrapper y catálogo de versiones (libs.versions.toml)
```

Capturas de referencia del sistema de diseño (claro y oscuro), generadas en la JVM:

```bash
./gradlew :core:designsystem:recordRoborazziDebug
```

Quedan en `core/designsystem/build/outputs/roborazzi/`.

### Rendimiento (`:benchmark`)

Mide la fluidez del scroll del lector con un PDF generado de 320 páginas. Necesita un móvil conectado
(mejor real que emulador):

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

### Evaluación de la búsqueda

En las compilaciones de depuración, Ajustes → Acerca de → «Evaluar la búsqueda» mide con un fichero
de preguntas si el chat encuentra la página de cada respuesta. Formato e instrucciones en
[tools/rag-eval](tools/rag-eval/README.md).

