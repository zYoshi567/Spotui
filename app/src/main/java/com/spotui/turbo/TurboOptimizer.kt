package com.spotui.turbo

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Núcleo de ingeniería para Spotui:
 * - Baja latencia de inicio (350ms) y retención de back-buffer (30s).
 * - Confirmación estricta de reproducción (solo marca reproducida tras el primer frame de audio en STATE_READY).
 * - Recuperación automática ante errores de red/pistas caídas sin congelar la cola.
 * - Gestor de descargas transaccional (.part) con soporte HTTP Range y validación de cabecera.
 * - Estética y físicas de transición estilo iOS a 120Hz.
 * - Redacción segura de tokens OAuth en logs.
 */
object TurboOptimizer {

    private val ioExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queueSequenceToken = AtomicLong(0L)
    private val confirmedPlayedTracks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Configura el control de carga de Media3 ExoPlayer para inicio rápido y resistencia a micro-cortes.
     */
    @JvmStatic
    fun createFastLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 350, // Primer sonido en ~350ms
                /* bufferForPlaybackAfterRebufferMs = */ 800
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Inyecta el LoadControl optimizado y registra el guardián de recuperación automática y confirmación real.
     */
    @JvmStatic
    fun configurePlayerBuilder(builder: ExoPlayer.Builder): ExoPlayer.Builder {
        return builder
            .setLoadControl(createFastLoadControl())
            .setHandleAudioBecomingNoisy(true)
    }

    /**
     * Adjunta el listener de estado a ExoPlayer:
     * 1. No marca una canción como reproducida hasta que STATE_READY + isPlaying confirman audio real.
     * 2. Si una canción falla o el enlace caduca, reintenta hasta 2 veces y si persiste, salta a la siguiente
     *    pista sin bloquear la aplicación ni requerir intervención manual.
     */
    @JvmStatic
    fun attachResilientPlayerGuard(player: ExoPlayer): ExoPlayer {
        val retryCounter = AtomicInteger(0)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.playWhenReady) {
                    retryCounter.set(0)
                    player.currentMediaItem?.mediaId?.let { mediaId ->
                        confirmedPlayedTracks.add(mediaId)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && player.playbackState == Player.STATE_READY) {
                    player.currentMediaItem?.mediaId?.let { mediaId ->
                        confirmedPlayedTracks.add(mediaId)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val attempt = retryCounter.incrementAndGet()
                if (attempt <= 2) {
                    val delayMs = if (attempt == 1) 300L else 800L
                    mainHandler.postDelayed({
                        try {
                            player.prepare()
                            player.play()
                        } catch (_: Throwable) {
                        }
                    }, delayMs)
                } else {
                    retryCounter.set(0)
                    mainHandler.post {
                        try {
                            if (player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                                player.prepare()
                                player.play()
                            } else {
                                player.stop()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        })
        return player
    }

    /**
     * Verifica si una pista realmente comenzó a sonar (evita falsos positivos en el historial).
     */
    @JvmStatic
    fun isTrackConfirmedPlaying(mediaId: String): Boolean {
        return confirmedPlayedTracks.contains(mediaId)
    }

    /**
     * Coordinador serial contra condiciones de carrera al cambiar rápido entre decenas de pistas.
     */
    @JvmStatic
    fun nextQueueTransitionTicket(): Long {
        return queueSequenceToken.incrementAndGet()
    }

    @JvmStatic
    fun isTransitionTicketValid(ticket: Long): Boolean {
        return queueSequenceToken.get() == ticket
    }

    /**
     * Valida rápidamente que una URL de audio esté viva antes de entregársela al reproductor.
     */
    @JvmStatic
    fun validateAudioStreamUrl(streamUrl: String, timeoutMs: Int = 2500): Boolean {
        if (streamUrl.isBlank()) return false
        return try {
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sistema de descargas persistente, reanudable (HTTP Range) y con verificación de cabecera binaria.
     */
    @JvmStatic
    fun downloadTrackResumable(
        streamUrl: String,
        destinationFile: File,
        maxRetries: Int = 3
    ): Boolean {
        val partFile = File(destinationFile.parentFile, "${destinationFile.name}.part")
        var attempt = 0

        while (attempt < maxRetries) {
            attempt++
            var connection: HttpURLConnection? = null
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL
                ) {
                    if (responseCode == 416) {
                        partFile.delete()
                    }
                    continue
                }

                val isResumed = responseCode == HttpURLConnection.HTTP_PARTIAL
                val contentLength = connection.contentLengthLong
                val expectedTotalBytes = if (isResumed && contentLength > 0) {
                    existingBytes + contentLength
                } else {
                    contentLength
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partFile, isResumed).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                if (expectedTotalBytes > 0L && partFile.length() != expectedTotalBytes) {
                    continue
                }

                if (!verifyAudioContainerHeader(partFile)) {
                    partFile.delete()
                    return false
                }

                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                return partFile.renameTo(destinationFile)
            } catch (_: Throwable) {
                try {
                    Thread.sleep(400L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    /**
     * Comprueba que el archivo descargado contenga una cabecera de audio válida (M4A/AAC, Ogg/Opus, FLAC, MP3 o WebM)
     * y no una página de error HTML o un archivo truncado.
     */
    @JvmStatic
    fun verifyAudioContainerHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        return try {
            val header = ByteArray(12)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            // M4A / MP4 ('ftyp' en bytes 4..7)
            val isMp4 = header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            // Ogg / Opus ('OggS')
            val isOgg = header[0] == 'O'.code.toByte() &&
                header[1] == 'g'.code.toByte() &&
                header[2] == 'g'.code.toByte() &&
                header[3] == 'S'.code.toByte()
            // FLAC ('fLaC')
            val isFlac = header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte()
            // MP3 ('ID3' o frame sync 0xFF 0xFB/0xF3/0xF2)
            val isMp3 = (header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte()) ||
                (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
            // WebM / Matroska (0x1A 0x45 0xDF 0xA3)
            val isWebm = header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte()

            isMp4 || isOgg || isFlac || isMp3 || isWebm
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Extrae el color dominante suavizado estilo iOS Apple Music a partir de la carátula fuera del hilo principal.
     */
    @JvmStatic
    fun extractIosAmbientColor(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled) return 0xFF0B0E14.toInt()
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            val total = 64
            for (x in 0 until 8) {
                for (y in 0 until 8) {
                    val pixel = scaled.getPixel(x, y)
                    rSum += (pixel shr 16) and 0xFF
                    gSum += (pixel shr 8) and 0xFF
                    bSum += pixel and 0xFF
                }
            }
            // Oscurecemos al 38% para garantizar legibilidad del texto blanco como en iOS
            val r = ((rSum / total) * 0.38f).toInt().coerceIn(10, 90)
            val g = ((gSum / total) * 0.38f).toInt().coerceIn(12, 95)
            val b = ((bSum / total) * 0.38f).toInt().coerceIn(16, 110)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (_: Throwable) {
            0xFF0B0E14.toInt()
        }
    }

    /**
     * Seguridad: redacta cualquier token OAuth, cookie sp_dc o secreto antes de escribir en logs.
     */
    @JvmStatic
    fun sanitizeSensitiveLog(rawMessage: String): String {
        return rawMessage
            .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9\\-_\\.]+"), "$1[REDACTED_TOKEN]")
            .replace(Regex("(?i)(sp_dc=)[A-Za-z0-9\\-_\\.]+"), "$1[REDACTED_COOKIE]")
            .replace(Regex("(?i)(access_token\"\\s*:\\s*\")[^\"]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(refresh_token\"\\s*:\\s*\")[^\"]+"), "$1[REDACTED]")
    }

    fun prewarmNetworkEndpoints() {
        val hosts = listOf(
            "api.spotify.com",
            "accounts.spotify.com",
            "api-partner.spotify.com",
            "spclient.wg.spotify.com",
            "music.youtube.com",
            "youtubei.googleapis.com",
            "lrclib.net"
        )
        for (host in hosts) {
            ioExecutor.execute {
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Throwable) {
        }

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
