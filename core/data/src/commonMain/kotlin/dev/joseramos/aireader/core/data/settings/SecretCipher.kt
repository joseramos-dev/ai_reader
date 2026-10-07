package dev.joseramos.aireader.core.data.settings

/**
 * Cifrado de los secretos de la app. Lo aporta cada plataforma, con una clave que no sale del sistema: el
 * Android Keystore en Android y la protección de datos de Windows (DPAPI) en escritorio.
 */
interface SecretCipher {
    /** [associatedData] liga el texto cifrado a un uso concreto: no se puede trasplantar a otro. */
    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray

    fun decrypt(encrypted: ByteArray, associatedData: ByteArray): ByteArray
}
