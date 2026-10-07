package dev.joseramos.aireader.core.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** Un archivo elegido con el selector del sistema o recibido de otra app: se lee a través de su `Uri`. */
class UriPickedFile(private val context: Context, private val uri: Uri) : PickedFile {
    override val name: String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Un `Uri` al que no se puede acceder (sin permiso, archivo borrado) da `null`, no un error. */
    override fun openStream(): InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
}
