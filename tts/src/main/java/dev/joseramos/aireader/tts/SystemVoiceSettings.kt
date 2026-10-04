package dev.joseramos.aireader.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/** Pantallas del sistema para instalar voces o elegir el motor de texto a voz. */
object SystemVoiceSettings {
    /** Abre el instalador de voces del motor; si no lo tiene, los ajustes de texto a voz. */
    fun installVoice(context: Context) {
        if (!open(context, Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) openSettings(context)
    }

    /** Abre los ajustes de texto a voz del sistema (motor, voces e idiomas). */
    fun openSettings(context: Context) {
        if (!open(context, Intent(TTS_SETTINGS))) open(context, Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
}
