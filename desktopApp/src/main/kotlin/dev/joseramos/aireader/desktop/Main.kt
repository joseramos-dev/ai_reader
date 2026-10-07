package dev.joseramos.aireader.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.joseramos.aireader.shared.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AI Reader") {
        App()
    }
}
