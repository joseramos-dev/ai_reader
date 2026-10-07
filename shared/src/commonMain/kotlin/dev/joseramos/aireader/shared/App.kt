package dev.joseramos.aireader.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Raíz de la interfaz compartida entre Android y escritorio. */
@Composable
fun App() {
    MaterialTheme {
        Text("AI Reader")
    }
}
