package dev.joseramos.aireader.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fluidez del lector con un PDF de 320 páginas (F3): abre el libro como «Abrir con» y desliza
 * rápido hacia abajo. FrameTimingMetric da la duración de los fotogramas (P50/P90/P99) y cuántos
 * superan el presupuesto; el objetivo es que el P90 quede por debajo de 16 ms.
 */
@RunWith(AndroidJUnit4::class)
class ReaderScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun createPdf() {
        TestPdfProvider.pdf(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun scrollLongPdf() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // Cada vuelta empieza con la biblioteca vacía, para no acumular copias del libro.
            device.executeShellCommand("pm clear $TARGET")
            startActivityAndWait(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TestPdfProvider.uri, "application/pdf")
                    .setPackage(TARGET)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            device.wait(Until.hasObject(By.desc("Página 1")), TIMEOUT_MS)
        }
    ) {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(FLINGS) {
            device.swipe(x, bottom, x, top, SWIPE_STEPS)
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET = "dev.joseramos.aireader"
        const val ITERATIONS = 5
        const val FLINGS = 15
        const val SWIPE_STEPS = 8
        const val TIMEOUT_MS = 15_000L
    }
}
