package dev.joseramos.aireader.core.designsystem.platform

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.joseramos.aireader.core.common.PickedFile
import dev.joseramos.aireader.core.common.UriPickedFile

@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): FilePicker {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { currentOnPicked(UriPickedFile(context, it)) }
    }
    return remember(launcher, mimeTypes) { FilePicker { launcher.launch(mimeTypes.toTypedArray()) } }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun <T> rememberWithNotificationPermission(action: (T) -> Unit): (T) -> Unit {
    val context = LocalContext.current
    val currentAction by rememberUpdatedState(action)
    var pending by remember { mutableStateOf<Pending<T>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { currentAction(it.value) }
        pending = null
    }
    return remember(context, permission) {
        { value ->
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                currentAction(value)
            } else {
                pending = Pending(value)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** Envuelve el valor para poder guardar también un `null` como valor pendiente. */
private class Pending<T>(val value: T)
