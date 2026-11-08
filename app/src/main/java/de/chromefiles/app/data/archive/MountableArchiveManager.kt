package de.chromefiles.app.data.archive

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import de.chromefiles.app.model.VfsVolume
import de.chromefiles.app.model.VfsVolumeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Wird geworfen, wenn ein Archiv verschlüsselt ist und ein Passwort benötigt (oder das Passwort falsch war). */
class ArchivePasswordRequiredException(message: String) : IllegalArgumentException(message)

/** Unterstützte Zielformate beim Erstellen neuer Archive. */
enum class ArchiveFormat(val extension: String, val labelDe: String, val mimeType: String) {
    ZIP("zip", "ZIP (.zip)", "application/zip"),
    TAR_GZ("tar.gz", "TAR.GZ (.tar.gz)", "application/gzip"),
    SEVEN_Z("7z", "7-Zip (.7z)", "application/x-7z-compressed")
}

/**
 * Verwaltet eingehängte ("gemountete") Archive (.zip, .tar, .tar.gz, .tgz, .7z, .rar)
 * exakt wie in der ChromeOS Files App:
 * Ein Doppelklick oder Rechtsklick -> "Als Laufwerk einhängen" bindet das Archiv als
 * schreibgeschütztes virtuelles Laufwerk in der linken Seitenleiste ein, inklusive Eject-Button.
 */
@Singleton
class MountableArchiveManager @Inject constructor() {

    data class MountedArchiveSession(
        val volumeId: String,
        val archiveName: String,
        val sourceArchivePath: String,
        val mountRootDir: File,
        val totalUncompressedBytes: Long,
        val mountedAtMillis: Long = System.currentTimeMillis()
    )

    private val _mountedArchives = MutableStateFlow<Map<String, MountedArchiveSession>>(emptyMap())
    val mountedArchives: StateFlow<Map<String, MountedArchiveSession>> = _mountedArchives.asStateFlow()

    /**
     * Hängt ein Archiv (.zip, .tar, .tar.gz, .tgz, .7z, .rar) als virtuelles Laufwerk ein.
     */
    suspend fun mountArchive(
        archiveFile: File,
        mountsBaseDir: File,
        password: CharArray? = null
    ): Result<VfsVolume> = withContext(Dispatchers.IO) {
        runCatching {
            require(archiveFile.exists() && archiveFile.isFile) {
                "Archivdatei existiert nicht: ${archiveFile.absolutePath}"
            }

            val hashId = computeShortHash(archiveFile.absolutePath)
            val volumeId = "archive_$hashId"

            // Falls bereits eingehängt, direkt bestehendes Volume zurückgeben
            _mountedArchives.value[volumeId]?.let { existing ->
                return@runCatching toVfsVolume(existing)
            }

            val targetMountDir = File(mountsBaseDir, hashId).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            val lowerName = archiveFile.name.lowercase()
            try {
                when {
                    lowerName.endsWith(".zip") -> extractZipToMount(archiveFile, targetMountDir, password)
                    lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") ->
                        extractTarGzToMount(archiveFile, targetMountDir)
                    lowerName.endsWith(".tar") -> extractTarToMount(archiveFile, targetMountDir)
                    lowerName.endsWith(".7z") -> extractSevenZToMount(archiveFile, targetMountDir, password)
                    lowerName.endsWith(".rar") -> extractRarToMount(archiveFile, targetMountDir, password)
                    else -> throw IllegalArgumentException("Nicht unterstütztes Archivformat: ${archiveFile.name}")
                }
            } catch (e: Exception) {
                targetMountDir.deleteRecursively()
                throw e
            }

            val uncompressedSize = calculateDirectorySize(targetMountDir)
            val session = MountedArchiveSession(
                volumeId = volumeId,
                archiveName = archiveFile.name,
                sourceArchivePath = archiveFile.absolutePath,
                mountRootDir = targetMountDir,
                totalUncompressedBytes = uncompressedSize
            )

            _mountedArchives.value = _mountedArchives.value + (volumeId to session)
            toVfsVolume(session)
        }
    }

    /**
     * Wirft ein eingehängtes Archiv wieder aus (Eject-Button in der linken Seitenleiste).
     */
    suspend fun unmountArchive(volumeId: String): Boolean = withContext(Dispatchers.IO) {
        val session = _mountedArchives.value[volumeId] ?: return@withContext false
        session.mountRootDir.deleteRecursively()
        _mountedArchives.value = _mountedArchives.value - volumeId
        true
    }

    /**
     * Erstellt ein neues .zip-Archiv aus einer Liste ausgewählter Dateien und Ordner
     * ("Auswahl zippen / komprimieren" im ChromeOS-Rechtsklickmenü).
     */
    suspend fun compressNodesToZip(
        sourceFiles: List<File>,
        destinationZipFile: File,
        password: CharArray? = null,
        onProgress: (processedFiles: Int, totalFiles: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): Result<File> = compressNodesToArchive(
        sourceFiles = sourceFiles,
        destinationFile = destinationZipFile,
        format = ArchiveFormat.ZIP,
        password = password,
        onProgress = onProgress
    )

    /**
     * Erstellt ein neues Archiv (.zip mit optionalem AES-256-Passwort, .tar.gz oder .7z)
     * aus einer Liste ausgewählter Dateien und Ordner.
     */
    suspend fun compressNodesToArchive(
        sourceFiles: List<File>,
        destinationFile: File,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        password: CharArray? = null,
        onProgress: (processedFiles: Int, totalFiles: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            when (format) {
                ArchiveFormat.ZIP -> createZipArchive(sourceFiles, destinationFile, password, onProgress)
                ArchiveFormat.TAR_GZ -> createTarGzArchive(sourceFiles, destinationFile, onProgress)
                ArchiveFormat.SEVEN_Z -> createSevenZArchive(sourceFiles, destinationFile, onProgress)
            }
            destinationFile
        }
    }

    private fun createZipArchive(
        sourceFiles: List<File>,
        destinationZipFile: File,
        password: CharArray?,
        onProgress: (Int, Int, String) -> Unit
    ) {
        val hasPassword = password != null && password.isNotEmpty()
        val zipFile = if (hasPassword) ZipFile(destinationZipFile, password) else ZipFile(destinationZipFile)
        val parameters = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.NORMAL
            if (hasPassword) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

        val total = sourceFiles.size.coerceAtLeast(1)
        sourceFiles.forEachIndexed { index, file ->
            onProgress(index, total, file.name)
            if (file.isDirectory) {
                zipFile.addFolder(file, parameters)
            } else if (file.isFile) {
                zipFile.addFile(file, parameters)
            }
        }
        onProgress(total, total, destinationZipFile.name)
    }

    private fun createTarGzArchive(
        sourceFiles: List<File>,
        destinationFile: File,
        onProgress: (Int, Int, String) -> Unit
    ) {
        val allEntries = collectRelativeFiles(sourceFiles)
        val total = allEntries.size.coerceAtLeast(1)
        FileOutputStream(destinationFile).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                GzipCompressorOutputStream(bos).use { gzos ->
                    TarArchiveOutputStream(gzos).apply {
                        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    }.use { tos ->
                        allEntries.forEachIndexed { index, (file, relPath) ->
                            onProgress(index, total, file.name)
                            val entryName = if (file.isDirectory && !relPath.endsWith("/")) "$relPath/" else relPath
                            val entry = TarArchiveEntry(file, entryName)
                            tos.putArchiveEntry(entry)
                            if (file.isFile) {
                                FileInputStream(file).use { fis -> fis.copyTo(tos) }
                            }
                            tos.closeArchiveEntry()
                        }
                    }
                }
            }
        }
        onProgress(total, total, destinationFile.name)
    }

    private fun createSevenZArchive(
        sourceFiles: List<File>,
        destinationFile: File,
        onProgress: (Int, Int, String) -> Unit
    ) {
        val allEntries = collectRelativeFiles(sourceFiles)
        val total = allEntries.size.coerceAtLeast(1)
        SevenZOutputFile(destinationFile).apply {
            setContentCompression(SevenZMethod.LZMA2)
        }.use { sevenZOut ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            allEntries.forEachIndexed { index, (file, relPath) ->
                onProgress(index, total, file.name)
                val entry: SevenZArchiveEntry = sevenZOut.createArchiveEntry(file, relPath)
                sevenZOut.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { fis ->
                        var read = fis.read(buffer)
                        while (read > 0) {
                            sevenZOut.write(buffer, 0, read)
                            read = fis.read(buffer)
                        }
                    }
                }
                sevenZOut.closeArchiveEntry()
            }
        }
        onProgress(total, total, destinationFile.name)
    }

    private fun collectRelativeFiles(sourceFiles: List<File>): List<Pair<File, String>> {
        val list = mutableListOf<Pair<File, String>>()
        for (root in sourceFiles) {
            if (!root.exists()) continue
            val parent = root.parentFile
            if (root.isFile) {
                list += root to root.name
            } else {
                root.walkTopDown().forEach { child ->
                    val rel = if (parent != null) {
                        child.relativeTo(parent).invariantSeparatorsPath
                    } else {
                        child.relativeTo(root).invariantSeparatorsPath.ifEmpty { root.name }
                    }
                    list += child to rel
                }
            }
        }
        return list
    }

    fun getMountedVolumeList(): List<VfsVolume> {
        return _mountedArchives.value.values
            .sortedBy { it.mountedAtMillis }
            .map { toVfsVolume(it) }
    }

    private fun toVfsVolume(session: MountedArchiveSession): VfsVolume {
        return VfsVolume(
            id = session.volumeId,
            title = session.archiveName,
            subtitle = "Eingehängtes Archiv",
            type = VfsVolumeType.MOUNTED_ARCHIVE,
            rootPath = session.mountRootDir.absolutePath,
            isEjectable = true,
            isExpandable = true,
            isReadOnly = true,
            totalBytes = session.totalUncompressedBytes,
            usedBytes = session.totalUncompressedBytes,
            sourceArchivePath = session.sourceArchivePath
        )
    }

    private fun extractZipToMount(archiveFile: File, targetMountDir: File, password: CharArray?) {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted) {
            if (password == null || password.isEmpty()) {
                throw ArchivePasswordRequiredException("Das ZIP-Archiv „${archiveFile.name}“ ist passwortgeschützt.")
            }
            zipFile.setPassword(password)
        }
        try {
            zipFile.extractAll(targetMountDir.absolutePath)
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || zipFile.isEncrypted) {
                throw ArchivePasswordRequiredException("Falsches Passwort für „${archiveFile.name}“.")
            }
            throw e
        }
    }

    private fun extractTarGzToMount(archiveFile: File, targetMountDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                GzipCompressorInputStream(bis).use { gzis ->
                    TarArchiveInputStream(gzis).use { tis ->
                        extractTarStream(tis, targetMountDir)
                    }
                }
            }
        }
    }

    private fun extractTarToMount(archiveFile: File, targetMountDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                TarArchiveInputStream(bis).use { tis ->
                    extractTarStream(tis, targetMountDir)
                }
            }
        }
    }

    private fun extractTarStream(tis: TarArchiveInputStream, targetMountDir: File) {
        val canonicalRoot = targetMountDir.canonicalPath
        var entry = tis.nextEntry
        while (entry != null) {
            val outFile = File(targetMountDir, entry.name)
            if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                throw SecurityException("Ungültiger Archiv-Pfad (Zip-Slip verhindert): ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    tis.copyTo(fos)
                }
                outFile.setLastModified(entry.lastModifiedDate.time)
            }
            entry = tis.nextEntry
        }
    }

    private fun extractSevenZToMount(archiveFile: File, targetMountDir: File, password: CharArray?) {
        val builder = SevenZFile.builder().setFile(archiveFile)
        if (password != null && password.isNotEmpty()) {
            builder.setPassword(password)
        }
        val canonicalRoot = targetMountDir.canonicalPath
        try {
            builder.get().use { sevenZFile ->
                var entry = sevenZFile.nextEntry
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entry != null) {
                    val outFile = File(targetMountDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                        throw SecurityException("Ungültiger 7z-Pfad: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var bytesRead = sevenZFile.read(buffer)
                            while (bytesRead > 0) {
                                fos.write(buffer, 0, bytesRead)
                                bytesRead = sevenZFile.read(buffer)
                            }
                        }
                    }
                    entry = sevenZFile.nextEntry
                }
            }
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequiredException("Das 7z-Archiv „${archiveFile.name}“ ist passwortgeschützt.")
        } catch (e: java.io.IOException) {
            if (e.message?.contains("password", ignoreCase = true) == true ||
                e.message?.contains("encrypted", ignoreCase = true) == true
            ) {
                throw ArchivePasswordRequiredException("Passwort für „${archiveFile.name}“ erforderlich oder ungültig.")
            }
            throw e
        }
    }

    private fun extractRarToMount(archiveFile: File, targetMountDir: File, password: CharArray?) {
        val canonicalRoot = targetMountDir.canonicalPath
        val pw = password?.takeIf { it.isNotEmpty() }?.let { String(it) }
        try {
            Archive(archiveFile, pw).use { archive ->
                if (archive.isEncrypted && pw.isNullOrEmpty()) {
                    throw ArchivePasswordRequiredException("Das RAR-Archiv „${archiveFile.name}“ ist passwortgeschützt.")
                }
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    if (header.isEncrypted && pw.isNullOrEmpty()) {
                        throw ArchivePasswordRequiredException("Das RAR-Archiv „${archiveFile.name}“ ist passwortgeschützt.")
                    }
                    val rawName = header.fileName.replace('\\', '/').trimStart('/')
                    if (rawName.isNotEmpty()) {
                        val outFile = File(targetMountDir, rawName)
                        if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("Ungültiger RAR-Pfad (Zip-Slip verhindert): $rawName")
                        }
                        if (header.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                archive.extractFile(header, fos)
                            }
                            header.mTime?.time?.let { outFile.setLastModified(it) }
                        }
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: ArchivePasswordRequiredException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true ||
                e.message?.contains("crc", ignoreCase = true) == true && pw != null
            ) {
                throw ArchivePasswordRequiredException("Falsches Passwort für „${archiveFile.name}“.")
            }
            throw e
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun computeShortHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
