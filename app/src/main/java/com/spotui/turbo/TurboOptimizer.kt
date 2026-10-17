package com.spotui.turbo

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Se inicializa automáticamente al abrir Spotui sin romper ninguna función original.
 * 1. Desbloquea 120Hz/90Hz nativos en la interfaz.
 * 2. Pre-resuelve DNS de Spotify, Innertube y GoogleVideo para evitar demoras y errores al dar Play.
 * 3. Provee un LoadControl de 350ms para que ExoPlayer inicie las canciones al instante.
 */
object TurboOptimizer {

    private val warmupExecutor = Executors.newCachedThreadPool()

    @JvmStatic
    fun createFastLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 350, // Inicia con solo 0.35s cargados (antes 2.5s)
                /* bufferForPlaybackAfterRebufferMs = */ 800
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    fun prewarmNetworkEndpoints() {
        val hosts = listOf(
            "api.spotify.com",
            "accounts.spotify.com",
            "spclient.wg.spotify.com",
            "music.youtube.com",
            "youtubei.googleapis.com",
            "lrclib.net"
        )
        for (host in hosts) {
            warmupExecutor.execute {
                try {
                    InetAddress.getAllByName(host)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun enableHighRefreshRate(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = activity.display ?: return
                val highestMode = display.supportedModes.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = activity.window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    activity.window.attributes = params
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = activity.windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                val highestMode = display.supportedModes.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = activity.window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    activity.window.attributes = params
                }
            }
        } catch (_: Throwable) {
        }
    }
}

class TurboInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            // Prioridad de hilo principal para evitar tirones de UI
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Throwable) {
        }

        // Pre-calienta los servidores de música en segundo plano apenas arranca la app
        TurboOptimizer.prewarmNetworkEndpoints()

        val app = context?.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                TurboOptimizer.enableHighRefreshRate(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                TurboOptimizer.enableHighRefreshRate(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
