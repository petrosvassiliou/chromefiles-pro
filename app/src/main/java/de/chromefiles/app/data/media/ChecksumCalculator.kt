package de.chromefiles.app.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Berechnet kryptografische Prüfsummen (MD5 und SHA-256) im Streaming-Verfahren
 * für beliebige Dateigrößen in der rechten ChromeOS-Info-Spalte.
 */
object ChecksumCalculator {

    data class FileHashes(
        val md5: String,
        val sha256: String
    )

    suspend fun computeHashes(file: File): Result<FileHashes> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists() && file.isFile) { "Datei existiert nicht: ${file.name}" }
            val md5Digest = MessageDigest.getInstance("MD5")
            val sha256Digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var read = fis.read(buffer)
                while (read > 0) {
                    md5Digest.update(buffer, 0, read)
                    sha256Digest.update(buffer, 0, read)
                    read = fis.read(buffer)
                }
            }
            FileHashes(
                md5 = md5Digest.digest().joinToString("") { "%02x".format(it) },
                sha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            )
        }
    }
}
