package dev.joseramos.aireader.spikes

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.joseramos.aireader.spikes.common.SpikeLog

class SpikesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        PDFBoxResourceLoader.init(this)
        // DJL guarda su caché nativa en el directorio del usuario, que no existe en Android.
        System.setProperty("DJL_CACHE_DIR", cacheDir.absolutePath)
        System.setProperty("ENGINE_CACHE_DIR", cacheDir.absolutePath)
    }
}
