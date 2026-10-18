package com.spotui.turbo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TurboAcceptanceTest {

    @Test
    fun stressTest_120ConsecutiveTrackSwitches_noRaceConditionOrStuckState() {
        val tickets = mutableListOf<Long>()
        // Dispara 120 cambios rápidos de pista consecutivos
        for (i in 1..120) {
            val ticket = TurboOptimizer.nextQueueTransitionTicket()
            tickets.add(ticket)
        }
        // Solo el último ticket (#120) debe seguir activo; los 119 anteriores quedan cancelados limpiamente
        for (i in 0 until 119) {
            assertFalse(
                "El ticket antiguo ${tickets[i]} debió invalidarse para evitar condiciones de carrera",
                TurboOptimizer.isTransitionTicketValid(tickets[i])
            )
        }
        assertTrue(
            "El último ticket (#120) debe ser el único válido",
            TurboOptimizer.isTransitionTicketValid(tickets.last())
        )
    }

    @Test
    fun verifyDownloadIntegrity_rejectsCorruptHtmlAndAcceptsValidAudioContainers() {
        val tempDir = Files.createTempDirectory("spotui_dl_test").toFile()
        try {
            // 1. Archivo corrupto (página de error HTML devuelta por un CDN caído)
            val corruptFile = File(tempDir, "corrupt.m4a")
            corruptFile.writeBytes("<html><body>403 Forbidden</body></html>".toByteArray())
            assertFalse(
                "Un error HTML no debe aceptarse como descarga de audio válida",
                TurboOptimizer.verifyAudioContainerHeader(corruptFile)
            )

            // 2. Contenedor M4A/MP4 válido (cabecera 'ftyp' en offset 4)
            val validM4a = File(tempDir, "valid.m4a")
            validM4a.writeBytes(
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x18,
                    'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                    'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
            )
            assertTrue(
                "Un contenedor M4A legítimo debe superar la verificación binaria",
                TurboOptimizer.verifyAudioContainerHeader(validM4a)
            )

            // 3. Contenedor FLAC válido (cabecera 'fLaC')
            val validFlac = File(tempDir, "valid.flac")
            validFlac.writeBytes(
                byteArrayOf(
                    'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
                    0x00, 0x00, 0x00, 0x22, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00
                )
            )
            assertTrue(
                "Un contenedor FLAC legítimo debe superar la verificación binaria",
                TurboOptimizer.verifyAudioContainerHeader(validFlac)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifySecurity_redactsSpotifyBearerTokensAndCookiesFromLogs() {
        val rawLog = "Request failed: Authorization: Bearer BQD89123798123_secret_token and Cookie sp_dc=AQB99887766_secret"
        val safeLog = TurboOptimizer.sanitizeSensitiveLog(rawLog)
        assertFalse(safeLog.contains("BQD89123798123_secret_token"))
        assertFalse(safeLog.contains("AQB99887766_secret"))
        assertEquals(
            "Request failed: Authorization: Bearer [REDACTED_TOKEN] and Cookie sp_dc=[REDACTED_COOKIE]",
            safeLog
        )
    }
}
