package dev.joseramos.aireader.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera el Baseline Profile de la app: el código que ART compila antes de la primera ejecución,
 * para que el arranque, la biblioteca y el desplazamiento por el lector no vayan a trompicones
 * mientras el JIT se calienta. Recorre lo más usado: arrancar en la biblioteca, abrir un PDF largo
 * y deslizar por sus páginas.
 *
 * En un móvil o emulador con Android 13 o posterior conectado:
 * `./gradlew :app:generateBaselineProfile`. El perfil se guarda en
 * `app/src/release/generated/baselineProfiles/` y entra en el APK en la siguiente compilación.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun createPdf() {
        TestPdfProvider.pdf(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun generate() = rule.collect(packageName = TARGET, includeInStartupProfile = true) {
        // Biblioteca vacía en cada vuelta, para no acumular copias del libro.
        device.executeShellCommand("pm clear $TARGET")
        pressHome()
        startActivityAndWait()

        startActivityAndWait(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(TestPdfProvider.uri, "application/pdf")
                .setPackage(TARGET)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // El lector se abre cuando termina de extraerse el texto.
        device.wait(Until.hasObject(By.desc("Página 1")), TIMEOUT_MS)
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(FLINGS) {
            device.swipe(x, bottom, x, top, SWIPE_STEPS)
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET = "dev.joseramos.aireader"
        const val FLINGS = 6
        const val SWIPE_STEPS = 8
        const val TIMEOUT_MS = 60_000L
    }
}
