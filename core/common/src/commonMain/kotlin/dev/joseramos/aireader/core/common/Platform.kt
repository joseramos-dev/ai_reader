package dev.joseramos.aireader.core.common

import java.io.InputStream

/**
 * Almacén sencillo de claves y valores que sobrevive a los reinicios: SharedPreferences en Android y un fichero
 * en Windows. Para estado pequeño y local, no para datos de la app (esos van en Room).
 */
interface KeyValueStore {
    fun getInt(key: String, default: Int = 0): Int

    fun getBoolean(key: String, default: Boolean = false): Boolean

    fun getString(key: String): String?

    fun keys(): Set<String>

    /** Aplica los cambios de [block] de una vez; con [commit], espera a que estén en disco. */
    fun edit(commit: Boolean = false, block: Editor.() -> Unit)

    interface Editor {
        fun putInt(key: String, value: Int)

        fun putBoolean(key: String, value: Boolean)

        fun putString(key: String, value: String)

        fun remove(key: String)

        fun clear()
    }
}

/** Abre los almacenes [KeyValueStore] por nombre. */
fun interface KeyValueStoreFactory {
    fun open(name: String): KeyValueStore
}

/** Identifica la instalación actual de la app: cambia al instalar o actualizar. `null` si no se puede saber. */
fun interface InstallStamp {
    fun get(): String?
}

/**
 * Un archivo que ha elegido el usuario: su nombre y una forma de leerlo (un `Uri` en Android, un fichero en
 * Windows).
 */
interface PickedFile {
    val name: String?

    /** `null` si no se puede abrir. */
    fun openStream(): InputStream?
}

/** Un archivo del disco como [PickedFile]: el que elige el usuario con el diálogo de Windows. */
class FilePickedFile(private val file: java.io.File) : PickedFile {
    override val name: String = file.name

    override fun openStream(): InputStream? = runCatching { file.inputStream() }.getOrNull()
}
