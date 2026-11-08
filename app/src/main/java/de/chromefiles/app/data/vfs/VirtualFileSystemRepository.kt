package de.chromefiles.app.data.vfs

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import de.chromefiles.app.data.archive.ArchiveFormat
import de.chromefiles.app.data.archive.MountableArchiveManager
import de.chromefiles.app.data.drive.GoogleDriveManager
import de.chromefiles.app.data.remote.RemoteConnectionConfig
import de.chromefiles.app.data.remote.RemoteEntry
import de.chromefiles.app.data.remote.RemoteFileClient
import de.chromefiles.app.data.remote.RemoteVolumeManager
import de.chromefiles.app.data.saf.SafVolumeManager
import de.chromefiles.app.data.local.ChromeFilesDao
import de.chromefiles.app.data.local.FileMetadataEntity
import de.chromefiles.app.data.local.MountedConnectionEntity
import de.chromefiles.app.data.local.TrashEntryEntity
import de.chromefiles.app.data.seeder.SandboxFileSeeder
import de.chromefiles.app.model.AudioMetadata
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.ConflictResolution
import de.chromefiles.app.model.ExifMetadata
import de.chromefiles.app.model.FileOperationProgress
import de.chromefiles.app.model.FileOperationStatus
import de.chromefiles.app.model.FileOperationType
import de.chromefiles.app.model.SearchFileTypeFilter
import de.chromefiles.app.model.SearchFilterState
import de.chromefiles.app.model.SearchLocation
import de.chromefiles.app.model.SearchModifiedFilter
import de.chromefiles.app.model.SortColumn
import de.chromefiles.app.model.SortDirection
import de.chromefiles.app.model.SortSpec
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolume
import de.chromefiles.app.model.VfsVolumeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentrales Virtual-File-System-Backend ("VirtualFileSystemRepository") für ChromeFiles Pro.
 * Vereint lokale Pfade (`java.nio.file` / `java.io.File` inkl. `MANAGE_EXTERNAL_STORAGE`),
 * eingehängte Archive (ZIP/TAR/7Z/RAR), SAF-Bäume für Linux-Dateien (Crostini) & USB-Medien,
 * die Google Drive REST API v3, SMB/SFTP/WebDAV-Freigaben und den Papierkorb (.Trash)
 * unter einer einheitlichen, reaktiven Schnittstelle.
 */
@Singleton
class VirtualFileSystemRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ChromeFilesDao,
    private val archiveManager: MountableArchiveManager,
    private val sandboxSeeder: SandboxFileSeeder,
    private val remoteVolumes: RemoteVolumeManager,
    val safVolumes: SafVolumeManager,
    val driveManager: GoogleDriveManager
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs by lazy {
        context.getSharedPreferences("chromefiles_vfs_prefs", Context.MODE_PRIVATE)
    }

    val sandboxRootDir: File by lazy {
        File(context.filesDir, "chromeos_vfs_root").apply { mkdirs() }
    }

    private val archiveMountsDir: File by lazy {
        File(context.cacheDir, "mounted_archives").apply { mkdirs() }
    }

    val sandboxMyFilesRoot: File
        get() = File(sandboxRootDir, "Meine Dateien")

    /** Prüft, ob `MANAGE_EXTERNAL_STORAGE` (Zugriff auf alle Dateien) vom System gewährt wurde. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun isUsingDeviceStorage(): Boolean = hasAllFilesAccess()

    suspend fun setUseDeviceStorage(enabled: Boolean) {
        prefs.edit().putBoolean("use_device_storage", enabled).apply()
        refreshVolumes()
    }

    /**
     * Echter Gerätespeicher-Wurzelpfad:
     * Auf ChromeOS `/mnt/chromeos/MyFiles`, ansonsten der primäre Android-Speicher (`/storage/emulated/0`).
     * Es wird niemals auf einen künstlichen Sandbox-Ordner zurückgefallen!
     */
    val myFilesRoot: File
        get() {
            val chromeOsMyFiles = File("/mnt/chromeos/MyFiles")
            if (chromeOsMyFiles.exists() && chromeOsMyFiles.canRead()) {
                return chromeOsMyFiles
            }
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null) {
                return ext
            }
            return File("/storage/emulated/0")
        }

    val linuxCrostiniRoot: File
        get() {
            val chromeOsLinux = File("/mnt/chromeos/LinuxFiles")
            if (chromeOsLinux.exists() && chromeOsLinux.canRead()) {
                return chromeOsLinux
            }
            return File(sandboxRootDir, "Linux-Dateien")
        }

    val googleDriveRoot: File
        get() {
            val chromeOsDrive = File("/mnt/chromeos/GoogleDrive")
            if (chromeOsDrive.exists() && chromeOsDrive.canRead()) {
                return chromeOsDrive
            }
            return File(sandboxRootDir, "Google Drive")
        }

    val trashRoot: File
        get() = File(sandboxRootDir, ".Trash").apply { mkdirs() }

    private val _volumesFlow = MutableStateFlow<List<VfsVolume>>(emptyList())
    val volumesFlow: StateFlow<List<VfsVolume>> = _volumesFlow.asStateFlow()

    private val _operationsFlow = MutableStateFlow<List<FileOperationProgress>>(emptyList())
    val operationsFlow: StateFlow<List<FileOperationProgress>> = _operationsFlow.asStateFlow()

    private val pausedOperationIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cancelledOperationIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingConflicts = ConcurrentHashMap<String, CompletableDeferred<ConflictDecision>>()

    /** Antwort des Nutzers im Konfliktdialog; [applyToAll] gilt für alle weiteren Konflikte der Operation. */
    data class ConflictDecision(val resolution: ConflictResolution, val applyToAll: Boolean)

    companion object {
        const val VOLUME_RECENT = "recent"
        const val VOLUME_STARRED = "starred"
        const val VOLUME_MY_FILES = "my_files"
        const val VOLUME_LINUX = "linux_files"
        const val VOLUME_DRIVE = "google_drive"
        const val VOLUME_DRIVE_OFFLINE = "google_drive_offline"
        const val VOLUME_TRASH = "trash"

        const val UNCONNECTED_LINUX_PATH = "chromeos://linux_unconnected"
        const val UNCONNECTED_DRIVE_PATH = "chromeos://drive_unconnected"

        private const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 Tage
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L
        private const val PAUSE_POLL_INTERVAL_MS = 150L
    }

    /**
     * Prüft, ob Google Drive aktuell verbunden ist (über REST API v3, SAF DocumentProvider oder ChromeOS-Mount).
     */
    suspend fun isDriveConnected(): Boolean = withContext(Dispatchers.IO) {
        if (driveManager.isConnected() && driveManager.accountFlow.value != null) return@withContext true
        val chromeOsDrive = File("/mnt/chromeos/GoogleDrive")
        if (chromeOsDrive.exists() && chromeOsDrive.canRead()) return@withContext true
        dao.getAllMountedConnections().any {
            it.protocol == SafVolumeManager.PROTOCOL_SAF_DRIVE || it.volumeId == SafVolumeManager.DRIVE_SAF_VOLUME_ID
        }
    }

    /**
     * Prüft, ob Linux-Dateien (Crostini / Termux) aktuell verbunden sind.
     */
    suspend fun isLinuxConnected(): Boolean = withContext(Dispatchers.IO) {
        val chromeOsLinux = File("/mnt/chromeos/LinuxFiles")
        if (chromeOsLinux.exists() && chromeOsLinux.canRead()) return@withContext true
        dao.getAllMountedConnections().any {
            it.protocol == SafVolumeManager.PROTOCOL_SAF_LINUX || it.volumeId == SafVolumeManager.LINUX_SAF_VOLUME_ID
        }
    }

    /**
     * Initialisiert die ChromeOS-Verzeichnisstruktur, entfernt eventuelle Altlasten aus früheren
     * Sandbox-Builds, bereinigt abgelaufene Papierkorb-Einträge (> 30 Tage) und aktualisiert den Navigationsbaum.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        sandboxSeeder.ensureSeeded(sandboxRootDir)
        runCatching {
            dao.deleteMetadataByPathPrefix(File(sandboxRootDir, "Meine Dateien").absolutePath)
            dao.deleteMetadataByPathPrefix(File(sandboxRootDir, "Linux-Dateien").absolutePath)
            dao.deleteMetadataByPathPrefix(File(sandboxRootDir, "Google Drive").absolutePath)
        }
        purgeExpiredTrashEntries()
        refreshVolumes()
    }

    /**
     * Baut den kompletten linken ChromeOS-Navigationsbaum inkl. Speicherplatz-Metriken
     * und dynamisch eingehängten ZIP/RAR-Archiven, SAF-Ordnern, Wechselmedien, Google Drive & SMB/SFTP-Freigaben neu auf.
     */
    suspend fun refreshVolumes() = withContext(Dispatchers.IO) {
        val activeMyFilesRoot = myFilesRoot
        val hasLocalAccess = hasAllFilesAccess()
        val statFs = runCatching { StatFs(activeMyFilesRoot.absolutePath) }.getOrNull()
        val totalDeviceBytes = statFs?.totalBytes ?: 0L
        val freeDeviceBytes = statFs?.availableBytes ?: 0L
        val usedDeviceBytes = (totalDeviceBytes - freeDeviceBytes).coerceAtLeast(0L)

        val allConnections = dao.getAllMountedConnections()
        val linuxSafConn = allConnections.firstOrNull {
            it.protocol == SafVolumeManager.PROTOCOL_SAF_LINUX || it.volumeId == SafVolumeManager.LINUX_SAF_VOLUME_ID
        }
        val driveSafConn = allConnections.firstOrNull {
            it.protocol == SafVolumeManager.PROTOCOL_SAF_DRIVE || it.volumeId == SafVolumeManager.DRIVE_SAF_VOLUME_ID
        }

        val driveAccount = driveManager.accountFlow.value
        val driveApiConnected = driveManager.isConnected() && driveAccount != null
        val chromeOsDriveRoot = File("/mnt/chromeos/GoogleDrive").takeIf { it.exists() && it.canRead() }
        val chromeOsLinuxRoot = File("/mnt/chromeos/LinuxFiles").takeIf { it.exists() && it.canRead() }

        val myFilesSubVolumes = if (hasLocalAccess) {
            activeMyFilesRoot.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.map { subDir ->
                    VfsVolume(
                        id = "my_files_${subDir.name.lowercase()}",
                        title = subDir.name,
                        type = VfsVolumeType.MY_FILES,
                        rootPath = subDir.absolutePath,
                        isExpandable = true
                    )
                }
                .orEmpty()
        } else {
            emptyList()
        }

        // Echte Wechselmedien (SD-Karten / USB-Sticks) automatisch erkennen
        val removableVolumes = runCatching {
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .mapNotNull { appExternalDir ->
                    val rootPath = appExternalDir.absolutePath.substringBefore("/Android/")
                    val rootFile = File(rootPath)
                    if (rootPath != activeMyFilesRoot.absolutePath && rootFile.exists() && rootFile.canRead()) {
                        val remStat = runCatching { StatFs(rootPath) }.getOrNull()
                        val remTotal = remStat?.totalBytes ?: 0L
                        val remUsed = (remTotal - (remStat?.availableBytes ?: 0L)).coerceAtLeast(0L)
                        VfsVolume(
                            id = "usb_${rootFile.name}",
                            title = "Wechselmedium (${rootFile.name})",
                            subtitle = rootPath,
                            type = VfsVolumeType.REMOVABLE_MEDIA,
                            rootPath = rootPath,
                            isExpandable = true,
                            totalBytes = remTotal,
                            usedBytes = remUsed
                        )
                    } else {
                        null
                    }
                }
        }.getOrDefault(emptyList())

        val mountedArchiveVolumes = archiveManager.getMountedVolumeList()
        val mountedNetworkAndSafVolumes = allConnections
            .filterNot {
                it.protocol == SafVolumeManager.PROTOCOL_SAF_LINUX ||
                    it.volumeId == SafVolumeManager.LINUX_SAF_VOLUME_ID ||
                    it.protocol == SafVolumeManager.PROTOCOL_SAF_DRIVE ||
                    it.volumeId == SafVolumeManager.DRIVE_SAF_VOLUME_ID
            }
            .map { conn ->
                if (conn.protocol == SafVolumeManager.PROTOCOL_SAF || safVolumes.isSafPath(conn.localMountPath)) {
                    safVolumes.toVfsVolume(conn)
                } else {
                    val isRemote = remoteVolumes.isRemotePath(conn.localMountPath)
                    VfsVolume(
                        id = conn.volumeId,
                        title = conn.title,
                        subtitle = "${conn.protocol} • ${conn.hostOrUri}",
                        type = VfsVolumeType.SMB_SHARE,
                        rootPath = conn.localMountPath,
                        isEjectable = true,
                        isExpandable = !isRemote
                    )
                }
            }

        val volumes = buildList {
            add(
                VfsVolume(
                    id = VOLUME_RECENT,
                    title = "Zuletzt verwendet",
                    type = VfsVolumeType.RECENT,
                    rootPath = "chromeos://recent"
                )
            )
            add(
                VfsVolume(
                    id = VOLUME_STARRED,
                    title = "Favoriten",
                    type = VfsVolumeType.STARRED,
                    rootPath = "chromeos://starred"
                )
            )
            add(
                VfsVolume(
                    id = VOLUME_MY_FILES,
                    title = "Meine Dateien",
                    subtitle = if (hasLocalAccess) {
                        "Gerätespeicher (${activeMyFilesRoot.absolutePath})"
                    } else {
                        "Berechtigung erforderlich – Tippen zum Freigeben"
                    },
                    type = VfsVolumeType.MY_FILES,
                    rootPath = activeMyFilesRoot.absolutePath,
                    isExpandable = hasLocalAccess,
                    totalBytes = totalDeviceBytes,
                    usedBytes = usedDeviceBytes,
                    children = myFilesSubVolumes
                )
            )

            // Linux-Dateien (Crostini / Termux)
            when {
                linuxSafConn != null -> add(safVolumes.toVfsVolume(linuxSafConn))
                chromeOsLinuxRoot != null -> add(
                    VfsVolume(
                        id = VOLUME_LINUX,
                        title = "Linux-Dateien",
                        subtitle = chromeOsLinuxRoot.absolutePath,
                        type = VfsVolumeType.LINUX_CROSTINI,
                        rootPath = chromeOsLinuxRoot.absolutePath,
                        isExpandable = true,
                        totalBytes = totalDeviceBytes,
                        usedBytes = usedDeviceBytes
                    )
                )
                else -> add(
                    VfsVolume(
                        id = VOLUME_LINUX,
                        title = "Linux-Dateien",
                        subtitle = "Nicht verbunden – Tippen zum Einbinden",
                        type = VfsVolumeType.LINUX_CROSTINI,
                        rootPath = UNCONNECTED_LINUX_PATH,
                        isExpandable = false
                    )
                )
            }

            // Google Drive (REST API v3, SAF DocumentProvider oder ChromeOS-Mount)
            when {
                driveApiConnected -> {
                    val driveTotalBytes = driveAccount!!.totalQuotaBytes.takeIf { it > 0L } ?: (15L * 1024 * 1024 * 1024)
                    val driveUsedBytes = driveAccount.usedQuotaBytes
                    val driveSubVolumes = listOf(
                        VfsVolume(
                            id = "drive_my_drive",
                            title = "Meine Ablage",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = GoogleDriveManager.ROOT_MY_DRIVE,
                            isExpandable = false
                        ),
                        VfsVolume(
                            id = "drive_shared_drives",
                            title = "Geteilte Ablagen",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = GoogleDriveManager.ROOT_SHARED_DRIVES,
                            isExpandable = false
                        ),
                        VfsVolume(
                            id = "drive_shared_with_me",
                            title = "Für mich freigegeben",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = GoogleDriveManager.ROOT_SHARED_WITH_ME
                        ),
                        VfsVolume(
                            id = "drive_computers",
                            title = "Computer",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = GoogleDriveManager.ROOT_COMPUTERS,
                            isExpandable = false
                        ),
                        VfsVolume(
                            id = VOLUME_DRIVE_OFFLINE,
                            title = "Offline verfügbar",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = "chromeos://drive_offline"
                        )
                    )
                    add(
                        VfsVolume(
                            id = VOLUME_DRIVE,
                            title = "Google Drive",
                            subtitle = "Verbunden (${driveAccount.email})",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = GoogleDriveManager.ROOT_MY_DRIVE,
                            isExpandable = true,
                            isEjectable = true,
                            totalBytes = driveTotalBytes,
                            usedBytes = driveUsedBytes,
                            children = driveSubVolumes
                        )
                    )
                }
                driveSafConn != null -> {
                    add(safVolumes.toVfsVolume(driveSafConn))
                }
                chromeOsDriveRoot != null -> {
                    val subDirs = chromeOsDriveRoot.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") }
                        ?.map { sub ->
                            VfsVolume(
                                id = "drive_cros_${sub.name.lowercase()}",
                                title = when (sub.name) {
                                    "MyDrive" -> "Meine Ablage"
                                    "SharedWithMe" -> "Für mich freigegeben"
                                    "Computers" -> "Computer"
                                    else -> sub.name
                                },
                                type = VfsVolumeType.GOOGLE_DRIVE,
                                rootPath = sub.absolutePath,
                                isExpandable = true
                            )
                        }
                        .orEmpty()
                    add(
                        VfsVolume(
                            id = VOLUME_DRIVE,
                            title = "Google Drive",
                            subtitle = chromeOsDriveRoot.absolutePath,
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = chromeOsDriveRoot.absolutePath,
                            isExpandable = true,
                            children = subDirs
                        )
                    )
                }
                else -> {
                    add(
                        VfsVolume(
                            id = VOLUME_DRIVE,
                            title = "Google Drive",
                            subtitle = "Nicht verbunden – Tippen zum Anmelden",
                            type = VfsVolumeType.GOOGLE_DRIVE,
                            rootPath = UNCONNECTED_DRIVE_PATH,
                            isExpandable = false,
                            isEjectable = false,
                            totalBytes = 0L,
                            usedBytes = 0L,
                            children = emptyList()
                        )
                    )
                }
            }

            addAll(removableVolumes)
            addAll(mountedArchiveVolumes)
            addAll(mountedNetworkAndSafVolumes)
            add(
                VfsVolume(
                    id = VOLUME_TRASH,
                    title = "Papierkorb",
                    subtitle = "Automatische Löschung nach 30 Tagen",
                    type = VfsVolumeType.TRASH,
                    rootPath = trashRoot.absolutePath,
                    totalBytes = totalDeviceBytes,
                    usedBytes = calculateDirectorySize(trashRoot)
                )
            )
        }

        _volumesFlow.value = volumes
    }

    /**
     * Listet den Inhalt eines Verzeichnisses oder eines virtuellen ChromeOS-Ordners
     * (Zuletzt verwendet, Favoriten, Offline verfügbar, Papierkorb) inklusive Sortierung.
     */
    suspend fun listDirectory(
        pathOrUri: String,
        volumeId: String,
        sortSpec: SortSpec = SortSpec(),
        showHiddenFiles: Boolean = false,
        recentCategoryFilter: SearchFileTypeFilter = SearchFileTypeFilter.ALL
    ): List<VfsNode> = withContext(Dispatchers.IO) {
        if (pathOrUri == UNCONNECTED_DRIVE_PATH || pathOrUri == UNCONNECTED_LINUX_PATH) {
            return@withContext emptyList()
        }
        val rawNodes: List<VfsNode> = when {
            volumeId == VOLUME_RECENT || pathOrUri == "chromeos://recent" -> {
                listRecentNodes(showHiddenFiles, recentCategoryFilter)
            }
            volumeId == VOLUME_STARRED || pathOrUri == "chromeos://starred" -> {
                listStarredNodes(showHiddenFiles)
            }
            volumeId == VOLUME_DRIVE_OFFLINE || pathOrUri == "chromeos://drive_offline" -> {
                listOfflinePinnedNodes(showHiddenFiles)
            }
            volumeId == VOLUME_TRASH || pathOrUri.startsWith(trashRoot.absolutePath) -> {
                listTrashNodes()
            }
            remoteVolumes.isRemotePath(pathOrUri) -> {
                val location = remoteVolumes.parse(pathOrUri)
                remoteVolumes.withClient(location.volumeId) { it.list(location.relativePath) }
                    .filter { showHiddenFiles || !it.name.startsWith(".") }
                    .map { remoteVolumes.toVfsNode(location.volumeId, it) }
            }
            safVolumes.isSafPath(pathOrUri) -> {
                safVolumes.list(pathOrUri, volumeId, showHiddenFiles)
            }
            driveManager.isDriveApiPath(pathOrUri) -> {
                driveManager.list(pathOrUri, volumeId, showHiddenFiles)
            }
            else -> {
                if (!hasAllFilesAccess() && pathOrUri.startsWith(myFilesRoot.absolutePath)) {
                    emptyList()
                } else {
                    val directory = File(pathOrUri)
                    if (!directory.exists() || !directory.isDirectory) {
                        emptyList()
                    } else {
                        val children = directory.listFiles().orEmpty()
                            .filter { showHiddenFiles || !it.name.startsWith(".") }
                        val metadataMap = dao.getMetadataForPaths(children.map { it.absolutePath })
                            .associateBy { it.path }
                        val isReadOnlyVolume = volumeId.startsWith("archive_")
                        children.map { file ->
                            fileToVfsNode(
                                file = file,
                                volumeId = volumeId,
                                metadata = metadataMap[file.absolutePath],
                                isReadOnly = isReadOnlyVolume
                            )
                        }
                    }
                }
            }
        }

        sortNodes(rawNodes, sortSpec)
    }

    /**
     * Führt die ChromeOS-Echtzeitsuche mit den 3 ausklappbaren Filter-Chips aus:
     * - Ort (Überall, Aktueller Ordner, Meine Dateien, Google Drive)
     * - Änderungsdatum (Beliebig, Heute, Gestern, Letzte 7 Tage, Letzte 30 Tage, Dieses Jahr)
     * - Dateityp (Alle Typen, Bilder, Videos, Audio, Dokumente)
     */
    suspend fun searchFiles(
        filterState: SearchFilterState,
        currentDirectoryPath: String,
        currentVolumeId: String,
        showHiddenFiles: Boolean,
        sortSpec: SortSpec = SortSpec()
    ): List<VfsNode> = withContext(Dispatchers.IO) {
        val searchRoots: List<Pair<String, File>> = when (filterState.location) {
            SearchLocation.EVERYWHERE -> buildList {
                add(VOLUME_MY_FILES to myFilesRoot)
                add(VOLUME_LINUX to linuxCrostiniRoot)
                add(VOLUME_DRIVE to googleDriveRoot)
                archiveManager.getMountedVolumeList().forEach { vol ->
                    add(vol.id to File(vol.rootPath))
                }
            }
            SearchLocation.CURRENT_FOLDER -> {
                val dir = File(currentDirectoryPath)
                if (dir.exists() && dir.isDirectory) {
                    listOf(currentVolumeId to dir)
                } else {
                    listOf(VOLUME_MY_FILES to myFilesRoot)
                }
            }
            SearchLocation.MY_FILES -> listOf(VOLUME_MY_FILES to myFilesRoot)
            SearchLocation.GOOGLE_DRIVE -> listOf(VOLUME_DRIVE to googleDriveRoot)
        }

        val queryTrimmed = filterState.query.trim()
        val ftsMatchedPaths: Set<String> = if (queryTrimmed.isNotEmpty()) {
            val sanitizedFts = queryTrimmed
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { "${it.replace("\"", "")}*" }
            val ftsResults = runCatching { dao.searchFullText(sanitizedFts) }
                .getOrElse { dao.searchSubstring(queryTrimmed) }
            val substringResults = dao.searchSubstring(queryTrimmed)
            (ftsResults + substringResults).map { it.path }.toSet()
        } else {
            emptySet()
        }

        val now = System.currentTimeMillis()
        val minModifiedMillis = filterState.modifiedFilter.maxAgeMillis?.let { now - it }

        val matchedFiles = mutableListOf<Pair<String, File>>()
        for ((volId, rootDir) in searchRoots) {
            if (!rootDir.exists()) continue
            rootDir.walkTopDown()
                .onEnter { dir -> showHiddenFiles || !dir.name.startsWith(".") }
                .forEach { file ->
                    if (file == rootDir) return@forEach
                    if (!showHiddenFiles && file.name.startsWith(".")) return@forEach

                    val nameMatches = queryTrimmed.isEmpty() ||
                        file.name.contains(queryTrimmed, ignoreCase = true) ||
                        file.absolutePath in ftsMatchedPaths

                    if (!nameMatches) return@forEach

                    if (minModifiedMillis != null && file.lastModified() < minModifiedMillis) {
                        return@forEach
                    }

                    matchedFiles.add(volId to file)
                }
        }

        val metadataMap = dao.getMetadataForPaths(matchedFiles.map { it.second.absolutePath })
            .associateBy { it.path }

        val localNodes = matchedFiles.map { (volId, file) ->
            fileToVfsNode(
                file = file,
                volumeId = volId,
                metadata = metadataMap[file.absolutePath],
                isReadOnly = volId.startsWith("archive_")
            )
        }

        val driveApiNodes: List<VfsNode> = if (
            driveManager.isConnected() &&
            (filterState.location == SearchLocation.EVERYWHERE || filterState.location == SearchLocation.GOOGLE_DRIVE)
        ) {
            driveManager.search(queryTrimmed, VOLUME_DRIVE, showHiddenFiles).filter { node ->
                minModifiedMillis == null || node.lastModifiedMillis >= minModifiedMillis
            }
        } else {
            emptyList()
        }

        val candidateNodes = (localNodes + driveApiNodes).filter { node ->
            matchesFileTypeFilter(node, filterState.fileTypeFilter)
        }

        sortNodes(candidateNodes, sortSpec)
    }

    /**
     * Erzeugt die interaktiven Breadcrumb-Segmente für die Top-Action-Bar
     * (z. B. `Meine Dateien > Projekte > 2026`).
     */
    fun buildBreadcrumbs(currentPath: String, volumeId: String): List<BreadcrumbSegment> {
        if (currentPath == UNCONNECTED_DRIVE_PATH) {
            return listOf(BreadcrumbSegment("Google Drive", UNCONNECTED_DRIVE_PATH, VOLUME_DRIVE))
        }
        if (currentPath == UNCONNECTED_LINUX_PATH) {
            return listOf(BreadcrumbSegment("Linux-Dateien", UNCONNECTED_LINUX_PATH, VOLUME_LINUX))
        }
        when (volumeId) {
            VOLUME_RECENT -> return listOf(BreadcrumbSegment("Zuletzt verwendet", "chromeos://recent", VOLUME_RECENT))
            VOLUME_STARRED -> return listOf(BreadcrumbSegment("Favoriten", "chromeos://starred", VOLUME_STARRED))
            VOLUME_DRIVE_OFFLINE -> return listOf(
                BreadcrumbSegment("Google Drive", GoogleDriveManager.ROOT_MY_DRIVE, VOLUME_DRIVE),
                BreadcrumbSegment("Offline verfügbar", "chromeos://drive_offline", VOLUME_DRIVE_OFFLINE)
            )
            VOLUME_TRASH -> return listOf(BreadcrumbSegment("Papierkorb", trashRoot.absolutePath, VOLUME_TRASH))
        }

        if (remoteVolumes.isRemotePath(currentPath)) {
            val location = remoteVolumes.parse(currentPath)
            val title = _volumesFlow.value.firstOrNull { it.id == location.volumeId }?.title ?: "Netzwerk"
            val segments = mutableListOf(
                BreadcrumbSegment(title, remoteVolumes.rootPath(location.volumeId), location.volumeId)
            )
            var running = ""
            location.relativePath.split('/').filter { it.isNotEmpty() }.forEach { part ->
                running = remoteVolumes.childPath(running, part)
                segments += BreadcrumbSegment(part, remoteVolumes.pathFor(location.volumeId, running), location.volumeId)
            }
            return segments
        }

        if (safVolumes.isSafPath(currentPath)) {
            val title = _volumesFlow.value.firstOrNull { it.id == volumeId }?.title
                ?: when (volumeId) {
                    VOLUME_LINUX -> "Linux-Dateien"
                    VOLUME_DRIVE -> "Google Drive"
                    else -> "Externer Ordner"
                }
            val rootPath = _volumesFlow.value.firstOrNull { it.id == volumeId }?.rootPath ?: currentPath
            return if (currentPath == rootPath) {
                listOf(BreadcrumbSegment(title, rootPath, volumeId))
            } else {
                listOf(
                    BreadcrumbSegment(title, rootPath, volumeId),
                    BreadcrumbSegment(
                        Uri.decode(currentPath.substringAfterLast('/')).substringAfterLast('/').substringAfterLast(':'),
                        currentPath,
                        volumeId
                    )
                )
            }
        }

        if (driveManager.isDriveApiPath(currentPath)) {
            return driveManager.buildBreadcrumbs(currentPath, volumeId)
        }

        val baseRoot: Pair<String, File> = when {
            currentPath.startsWith(myFilesRoot.absolutePath) -> "Meine Dateien" to myFilesRoot
            currentPath.startsWith(linuxCrostiniRoot.absolutePath) -> "Linux-Dateien" to linuxCrostiniRoot
            currentPath.startsWith(googleDriveRoot.absolutePath) -> "Google Drive" to googleDriveRoot
            else -> {
                val mountedVol = archiveManager.getMountedVolumeList().firstOrNull {
                    currentPath.startsWith(it.rootPath)
                } ?: _volumesFlow.value.firstOrNull { currentPath.startsWith(it.rootPath) }

                if (mountedVol != null) {
                    mountedVol.title to File(mountedVol.rootPath)
                } else {
                    "Meine Dateien" to myFilesRoot
                }
            }
        }

        val (rootTitle, rootFile) = baseRoot
        val segments = mutableListOf(
            BreadcrumbSegment(title = rootTitle, path = rootFile.absolutePath, volumeId = volumeId)
        )

        val relative = currentPath.removePrefix(rootFile.absolutePath).trim('/')
        if (relative.isNotEmpty()) {
            var runningFile = rootFile
            relative.split('/').filter { it.isNotBlank() }.forEach { part ->
                runningFile = File(runningFile, part)
                segments.add(
                    BreadcrumbSegment(
                        title = part,
                        path = runningFile.absolutePath,
                        volumeId = volumeId
                    )
                )
            }
        }
        return segments
    }

    /**
     * Erstellt einen neuen Ordner im angegebenen Verzeichnis (`Strg + E` / `Strg + Umschalt + N`).
     */
    suspend fun createDirectory(
        parentPath: String,
        folderName: String,
        volumeId: String = VOLUME_MY_FILES
    ): Result<VfsNode> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitizedName = folderName.trim().replace("/", "_")
            require(sanitizedName.isNotEmpty()) { "Ordnername darf nicht leer sein." }
            if (remoteVolumes.isRemotePath(parentPath)) {
                val location = remoteVolumes.parse(parentPath)
                val entry = remoteVolumes.withClient(location.volumeId) { client ->
                    val target = uniqueRemotePath(client, location.relativePath, sanitizedName, isDirectory = true)
                    client.mkdir(target)
                    client.stat(target) ?: RemoteEntry(
                        name = target.substringAfterLast('/'),
                        path = target,
                        isDirectory = true,
                        sizeBytes = 0L,
                        lastModifiedMillis = System.currentTimeMillis()
                    )
                }
                return@runCatching remoteVolumes.toVfsNode(location.volumeId, entry)
            }
            if (safVolumes.isSafPath(parentPath)) {
                return@runCatching safVolumes.createDirectory(parentPath, sanitizedName, volumeId)
            }
            if (driveManager.isDriveApiPath(parentPath)) {
                return@runCatching driveManager.createFolder(parentPath, sanitizedName, volumeId)
            }
            val parentDir = File(parentPath)
            require(parentDir.exists() && parentDir.isDirectory) { "Zielordner existiert nicht." }

            val targetDir = resolveUniqueFile(File(parentDir, sanitizedName))
            if (!targetDir.mkdirs()) {
                throw IllegalStateException("Ordner konnte nicht erstellt werden: ${targetDir.name}")
            }

            val entity = FileMetadataEntity(
                path = targetDir.absolutePath,
                name = targetDir.name,
                volumeId = volumeId,
                mimeType = "vnd.android.document/directory",
                lastModifiedMillis = targetDir.lastModified(),
                sizeBytes = 0L,
                isDirectory = true
            )
            dao.upsertMetadata(entity)
            refreshVolumes()
            fileToVfsNode(targetDir, volumeId, entity)
        }
    }

    /**
     * Benennt eine Datei oder einen Ordner um (`Strg + Enter`).
     */
    suspend fun renameNode(node: VfsNode, newName: String): Result<VfsNode> = withContext(Dispatchers.IO) {
        runCatching {
            require(!node.isReadOnly) { "Schreibgeschütztes Element kann nicht umbenannt werden." }
            val cleanName = newName.trim().replace("/", "_")
            require(cleanName.isNotEmpty()) { "Dateiname darf nicht leer sein." }

            if (node.isSaf) {
                return@runCatching safVolumes.rename(node, cleanName)
            }
            if (node.isGoogleDriveApi) {
                return@runCatching driveManager.rename(node, cleanName)
            }
            if (node.isRemote) {
                val location = remoteVolumes.parse(node.path)
                val parentRel = location.relativePath.substringBeforeLast('/', "")
                val targetRel = remoteVolumes.childPath(parentRel, cleanName)
                val entry = remoteVolumes.withClient(location.volumeId) { client ->
                    if (targetRel != location.relativePath) {
                        if (client.stat(targetRel) != null) {
                            throw IllegalStateException("Eine Datei mit dem Namen '$cleanName' existiert bereits.")
                        }
                        client.rename(location.relativePath, targetRel)
                    }
                    client.stat(targetRel)
                } ?: throw IllegalStateException("Umbenennen fehlgeschlagen.")
                return@runCatching remoteVolumes.toVfsNode(location.volumeId, entry)
            }

            val sourceFile = File(node.path)
            require(sourceFile.exists()) { "Quelldatei existiert nicht." }

            val targetFile = File(sourceFile.parentFile, cleanName)
            if (targetFile.exists() && targetFile.absolutePath != sourceFile.absolutePath) {
                throw IllegalStateException("Eine Datei mit dem Namen '$cleanName' existiert bereits.")
            }

            if (!sourceFile.renameTo(targetFile)) {
                throw IllegalStateException("Umbenennen fehlgeschlagen.")
            }

            val existingMeta = dao.getMetadataByPath(node.path)
            dao.deleteMetadataByPath(node.path)
            val updatedMeta = (existingMeta ?: FileMetadataEntity(
                path = targetFile.absolutePath,
                name = targetFile.name,
                volumeId = node.volumeId,
                mimeType = resolveMimeType(targetFile)
            )).copy(
                path = targetFile.absolutePath,
                name = targetFile.name,
                lastModifiedMillis = targetFile.lastModified()
            )
            dao.upsertMetadata(updatedMeta)
            refreshVolumes()
            fileToVfsNode(targetFile, node.volumeId, updatedMeta)
        }
    }

    /**
     * Kopiert oder verschiebt Dateien asynchron über das ChromeOS-Operations-Center
     * inklusive Live-Fortschritt (MB/s, Restzeit) und Konfliktauflösung.
     */
    fun copyOrMoveNodesAsync(
        nodes: List<VfsNode>,
        destinationDirPath: String,
        destinationVolumeId: String,
        isMove: Boolean,
        conflictResolution: ConflictResolution = ConflictResolution.KEEP_BOTH,
        onCompleted: () -> Unit = {}
    ): String {
        val operationId = UUID.randomUUID().toString()
        repositoryScope.launch {
            executeCopyOrMove(
                operationId = operationId,
                nodes = nodes,
                destinationDirPath = destinationDirPath,
                destinationVolumeId = destinationVolumeId,
                isMove = isMove,
                conflictResolution = conflictResolution
            )
            refreshVolumes()
            onCompleted()
        }
        return operationId
    }

    suspend fun executeCopyOrMove(
        operationId: String = UUID.randomUUID().toString(),
        nodes: List<VfsNode>,
        destinationDirPath: String,
        destinationVolumeId: String,
        isMove: Boolean,
        conflictResolution: ConflictResolution? = null
    ): Result<List<VfsNode>> = withContext(Dispatchers.IO) {
        if (safVolumes.isSafPath(destinationDirPath) ||
            driveManager.isDriveApiPath(destinationDirPath) ||
            nodes.any { it.isSaf || it.isGoogleDriveApi }
        ) {
            return@withContext executeSafOrDriveTransfer(
                operationId, nodes, destinationDirPath, destinationVolumeId, isMove, conflictResolution
            )
        }
        if (remoteVolumes.isRemotePath(destinationDirPath) || nodes.any { it.isRemote }) {
            return@withContext executeRemoteTransfer(
                operationId, nodes, destinationDirPath, destinationVolumeId, isMove, conflictResolution
            )
        }
        var stickyResolution: ConflictResolution? = conflictResolution
        val partialTargets = mutableListOf<File>()
        runCatching {
            val destinationDir = File(destinationDirPath).apply { mkdirs() }
            val sourceFiles = nodes.map { File(it.path) }.filter { it.exists() }
            val totalBytes = sourceFiles.sumOf { calculateDirectorySizeOrFileSize(it) }.coerceAtLeast(1L)
            val totalFiles = sourceFiles.sumOf { countFilesRecursively(it) }.coerceAtLeast(1)

            val opType = if (isMove) FileOperationType.MOVE else FileOperationType.COPY
            val title = if (isMove) {
                "${nodes.size} Element(e) werden verschoben"
            } else {
                "${nodes.size} Element(e) werden kopiert"
            }

            var processedBytes = 0L
            var processedFiles = 0
            val startTimeMillis = System.currentTimeMillis()

            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = title,
                    currentFileName = nodes.firstOrNull()?.name.orEmpty(),
                    processedFiles = 0,
                    totalFiles = totalFiles,
                    processedBytes = 0L,
                    totalBytes = totalBytes,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = FileOperationStatus.RUNNING
                )
            )

            val resultNodes = mutableListOf<VfsNode>()

            for (node in nodes) {
                if (operationId in cancelledOperationIds) {
                    throw CancellationException("Dateioperation vom Nutzer abgebrochen.")
                }

                val sourceFile = File(node.path)
                if (!sourceFile.exists()) continue

                val canonicalSource = sourceFile.canonicalPath
                val canonicalDest = destinationDir.canonicalPath
                if (sourceFile.isDirectory &&
                    (canonicalDest == canonicalSource || canonicalDest.startsWith(canonicalSource + File.separator))
                ) {
                    throw IllegalArgumentException(
                        "„${sourceFile.name}“ kann nicht in sich selbst kopiert oder verschoben werden."
                    )
                }

                var targetFile = File(destinationDir, sourceFile.name)
                if (targetFile.absolutePath == sourceFile.absolutePath) {
                    if (isMove) {
                        // Verschieben in denselben Ordner ist ein No-op.
                        processedFiles += countFilesRecursively(sourceFile)
                        processedBytes += calculateDirectorySizeOrFileSize(sourceFile)
                        continue
                    }
                    // Kopieren in denselben Ordner -> "Name (1).ext" (niemals Quelle überschreiben!)
                    targetFile = resolveUniqueFile(targetFile)
                } else if (targetFile.exists()) {
                    val resolution = stickyResolution ?: run {
                        updateOperation(
                            currentProgress(operationId).copy(
                                status = FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION,
                                conflictingNodeName = targetFile.name,
                                speedMegabytesPerSec = 0f
                            )
                        )
                        val deferred = CompletableDeferred<ConflictDecision>()
                        pendingConflicts[operationId] = deferred
                        val decision = try {
                            deferred.await()
                        } finally {
                            pendingConflicts.remove(operationId)
                        }
                        if (operationId in cancelledOperationIds) {
                            throw CancellationException("Dateioperation vom Nutzer abgebrochen.")
                        }
                        updateOperation(
                            currentProgress(operationId).copy(
                                status = FileOperationStatus.RUNNING,
                                conflictingNodeName = null
                            )
                        )
                        if (decision.applyToAll) stickyResolution = decision.resolution
                        decision.resolution
                    }
                    when (resolution) {
                        ConflictResolution.SKIP -> {
                            processedFiles += countFilesRecursively(sourceFile)
                            processedBytes += calculateDirectorySizeOrFileSize(sourceFile)
                            continue
                        }
                        ConflictResolution.REPLACE -> {
                            targetFile.deleteRecursively()
                        }
                        ConflictResolution.KEEP_BOTH -> {
                            targetFile = resolveUniqueFile(targetFile)
                        }
                    }
                }

                // Schneller Pfad: Verschieben innerhalb desselben Dateisystems per rename().
                val movedByRename = isMove && !node.isReadOnly && sourceFile.renameTo(targetFile)
                if (movedByRename) {
                    processedFiles += countFilesRecursively(targetFile)
                    processedBytes += calculateDirectorySizeOrFileSize(targetFile)
                    dao.deleteMetadataByPath(sourceFile.absolutePath)
                } else {
                partialTargets += targetFile
                var lastEmitMillis = 0L
                copyRecursiveWithProgress(
                    source = sourceFile,
                    target = targetFile,
                    checkpoint = { awaitResumeOrThrow(operationId) },
                    onBytesCopied = { deltaBytes, currentName ->
                        processedBytes += deltaBytes
                        val nowMillis = System.currentTimeMillis()
                        if (nowMillis - lastEmitMillis < PROGRESS_EMIT_INTERVAL_MS) return@copyRecursiveWithProgress
                        lastEmitMillis = nowMillis
                        val elapsedSec = ((System.currentTimeMillis() - startTimeMillis) / 1000f).coerceAtLeast(0.05f)
                        val speedMbps = (processedBytes / (1024f * 1024f)) / elapsedSec
                        val remainingBytes = (totalBytes - processedBytes).coerceAtLeast(0L)
                        val remainingSec = if (speedMbps > 0.01f) {
                            ((remainingBytes / (1024f * 1024f)) / speedMbps).toLong()
                        } else {
                            0L
                        }

                        updateOperation(
                            FileOperationProgress(
                                operationId = operationId,
                                type = opType,
                                title = title,
                                currentFileName = currentName,
                                processedFiles = processedFiles,
                                totalFiles = totalFiles,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                                speedMegabytesPerSec = speedMbps,
                                estimatedRemainingSeconds = remainingSec,
                                status = FileOperationStatus.RUNNING
                            )
                        )
                    },
                    onFileDone = {
                        processedFiles++
                    }
                )
                partialTargets.remove(targetFile)
                }

                if (!movedByRename && isMove && !node.isReadOnly && sourceFile.absolutePath != targetFile.absolutePath) {
                    sourceFile.deleteRecursively()
                    dao.deleteMetadataByPath(sourceFile.absolutePath)
                }

                val newMeta = FileMetadataEntity(
                    path = targetFile.absolutePath,
                    name = targetFile.name,
                    volumeId = destinationVolumeId,
                    mimeType = resolveMimeType(targetFile),
                    isStarred = node.isStarred,
                    isOfflinePinned = node.isOfflinePinned || destinationVolumeId == VOLUME_DRIVE_OFFLINE,
                    lastModifiedMillis = targetFile.lastModified(),
                    sizeBytes = if (targetFile.isDirectory) 0L else targetFile.length(),
                    isDirectory = targetFile.isDirectory
                )
                dao.upsertMetadata(newMeta)
                resultNodes.add(fileToVfsNode(targetFile, destinationVolumeId, newMeta))
            }

            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = title,
                    currentFileName = "Abgeschlossen",
                    processedFiles = totalFiles,
                    totalFiles = totalFiles,
                    processedBytes = totalBytes,
                    totalBytes = totalBytes,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = FileOperationStatus.COMPLETED
                )
            )
            resultNodes
        }.onFailure { err ->
            // Halb kopierte Ziele entfernen, damit keine beschädigten Dateien zurückbleiben.
            partialTargets.forEach { it.deleteRecursively() }
            pausedOperationIds.remove(operationId)
            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = if (isMove) FileOperationType.MOVE else FileOperationType.COPY,
                    title = "Fehler bei Dateioperation",
                    currentFileName = err.message ?: "Unbekannter Fehler",
                    processedFiles = 0,
                    totalFiles = nodes.size,
                    processedBytes = 0L,
                    totalBytes = 0L,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = if (err is CancellationException) FileOperationStatus.CANCELLED else FileOperationStatus.FAILED,
                    errorMessage = err.message
                )
            )
        }
    }

    /**
     * Verschiebt ausgewählte Dateien in den ChromeOS-Papierkorb (`.Trash`) und speichert
     * den Ursprungspfad für eine spätere Wiederherstellung (`Entf` / `Alt + Backspace`).
     */
    suspend fun moveToTrash(nodes: List<VfsNode>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var trashedCount = 0
            for (node in nodes) {
                if (node.isReadOnly || node.isRemote || node.volumeId == VOLUME_TRASH) continue
                val sourceFile = File(node.path)
                if (!sourceFile.exists()) continue

                val trashTarget = resolveUniqueFile(File(trashRoot, sourceFile.name))
                if (!sourceFile.renameTo(trashTarget)) {
                    sourceFile.copyRecursively(trashTarget, overwrite = true)
                    sourceFile.deleteRecursively()
                }

                dao.insertTrashEntry(
                    TrashEntryEntity(
                        trashPath = trashTarget.absolutePath,
                        originalPath = sourceFile.absolutePath,
                        originalVolumeId = node.volumeId,
                        fileName = sourceFile.name,
                        isDirectory = node.isDirectory,
                        sizeBytes = node.sizeBytes,
                        trashedAtMillis = System.currentTimeMillis()
                    )
                )
                dao.deleteMetadataByPath(sourceFile.absolutePath)
                trashedCount++
            }
            refreshVolumes()
            trashedCount
        }
    }

    /**
     * Stellt Dateien aus dem Papierkorb an ihrem ursprünglichen Pfad wieder her.
     */
    suspend fun restoreFromTrash(trashNodes: List<VfsNode>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var restoredCount = 0
            for (node in trashNodes) {
                val entry = dao.getTrashEntry(node.path)
                val trashFile = File(node.path)
                if (!trashFile.exists()) {
                    dao.deleteTrashEntry(node.path)
                    continue
                }

                val targetOriginal = entry?.originalPath?.let { File(it) }
                    ?: File(myFilesRoot, "Downloads/${trashFile.name}")
                targetOriginal.parentFile?.mkdirs()
                val restoredFile = resolveUniqueFile(targetOriginal)

                if (!trashFile.renameTo(restoredFile)) {
                    trashFile.copyRecursively(restoredFile, overwrite = true)
                    trashFile.deleteRecursively()
                }

                val restoredVolumeId = entry?.originalVolumeId ?: VOLUME_MY_FILES
                dao.upsertMetadata(
                    FileMetadataEntity(
                        path = restoredFile.absolutePath,
                        name = restoredFile.name,
                        volumeId = restoredVolumeId,
                        mimeType = resolveMimeType(restoredFile),
                        lastModifiedMillis = restoredFile.lastModified(),
                        sizeBytes = if (restoredFile.isDirectory) 0L else restoredFile.length(),
                        isDirectory = restoredFile.isDirectory
                    )
                )
                dao.deleteTrashEntry(node.path)
                restoredCount++
            }
            refreshVolumes()
            restoredCount
        }
    }

    /**
     * Löscht Dateien endgültig (`Umschalt + Entf`).
     */
    suspend fun deletePermanently(nodes: List<VfsNode>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var deletedCount = 0
            for (node in nodes) {
                if (node.isReadOnly) continue
                if (node.isSaf) {
                    if (safVolumes.delete(node)) deletedCount++
                    continue
                }
                if (node.isGoogleDriveApi) {
                    if (driveManager.delete(node)) deletedCount++
                    continue
                }
                if (node.isRemote) {
                    val location = remoteVolumes.parse(node.path)
                    remoteVolumes.withClient(location.volumeId) { it.delete(location.relativePath, node.isDirectory) }
                    deletedCount++
                    continue
                }
                val file = File(node.path)
                if (file.exists() && file.deleteRecursively()) {
                    deletedCount++
                }
                dao.deleteMetadataByPath(node.path)
                dao.deleteTrashEntry(node.path)
            }
            refreshVolumes()
            deletedCount
        }
    }

    /**
     * Leert den gesamten ChromeOS-Papierkorb.
     */
    suspend fun emptyTrash(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            trashRoot.listFiles()?.forEach { it.deleteRecursively() }
            dao.clearAllTrashEntries()
            refreshVolumes()
        }
    }

    /**
     * Hängt ein Archiv (.zip, .tar, .tar.gz, .7z, .rar) als virtuelles Laufwerk in die linke Seitenleiste ein.
     */
    suspend fun mountArchiveNode(archiveNode: VfsNode, password: CharArray? = null): Result<VfsVolume> {
        val localFile = materialize(archiveNode).getOrElse { return Result.failure(it) }
        val result = archiveManager.mountArchive(
            archiveFile = localFile,
            mountsBaseDir = archiveMountsDir,
            password = password
        )
        if (result.isSuccess) {
            refreshVolumes()
        }
        return result
    }

    /**
     * Wirft ein eingehängtes Archiv, ein SAF-Laufwerk, Google Drive oder eine SMB/SFTP-Verbindung aus.
     */
    suspend fun ejectVolume(volumeId: String): Boolean = withContext(Dispatchers.IO) {
        if (volumeId == VOLUME_DRIVE) {
            driveManager.disconnect()
            safVolumes.disconnect(VOLUME_DRIVE)
            refreshVolumes()
            return@withContext true
        }
        if (volumeId == VOLUME_LINUX || volumeId.startsWith("saf_")) {
            safVolumes.disconnect(volumeId)
            refreshVolumes()
            return@withContext true
        }
        val unmountedArchive = archiveManager.unmountArchive(volumeId)
        if (!unmountedArchive) {
            dao.deleteMountedConnection(volumeId)
            remoteVolumes.forget(volumeId)
            File(context.cacheDir, "remote_cache/$volumeId").deleteRecursively()
        }
        refreshVolumes()
        true
    }

    /**
     * Komprimiert eine Auswahl von Dateien/Ordnern zu einem .zip-Archiv ("Auswahl zippen").
     */
    suspend fun compressSelectionToZip(
        nodes: List<VfsNode>,
        destinationZipName: String? = null
    ): Result<VfsNode> = compressSelectionToArchive(
        nodes = nodes,
        format = ArchiveFormat.ZIP,
        destinationName = destinationZipName
    )

    /**
     * Komprimiert eine Auswahl von Dateien/Ordnern in das gewählte Archivformat (.zip, .tar.gz, .7z).
     */
    suspend fun compressSelectionToArchive(
        nodes: List<VfsNode>,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        destinationName: String? = null,
        password: CharArray? = null
    ): Result<VfsNode> = withContext(Dispatchers.IO) {
        runCatching {
            require(nodes.isNotEmpty()) { "Keine Dateien zum Komprimieren ausgewählt." }
            val firstParent = File(nodes.first().parentPath)
            val extSuffix = ".${format.extension}"
            val baseName = destinationName?.takeIf { it.isNotBlank() }
                ?: if (nodes.size == 1) {
                    "${nodes.first().name.substringBeforeLast('.')}$extSuffix"
                } else {
                    "Archiv_${nodes.size}_Elemente$extSuffix"
                }
            val finalArchiveFile = resolveUniqueFile(
                File(firstParent, if (baseName.endsWith(extSuffix, true)) baseName else "$baseName$extSuffix")
            )

            val operationId = UUID.randomUUID().toString()
            val sourceFiles = nodes.map { File(it.path) }.filter { it.exists() }
            val sizesPerSource = sourceFiles.map { calculateDirectorySizeOrFileSize(it) }
            val totalBytes = sizesPerSource.sum().coerceAtLeast(1L)
            val compressStartMillis = System.currentTimeMillis()

            archiveManager.compressNodesToArchive(
                sourceFiles = sourceFiles,
                destinationFile = finalArchiveFile,
                format = format,
                password = password,
                onProgress = { index, total, currentName ->
                    val doneBytes = sizesPerSource.take(index.coerceAtMost(sizesPerSource.size)).sum()
                    val elapsedSec = ((System.currentTimeMillis() - compressStartMillis) / 1000f).coerceAtLeast(0.05f)
                    val speedMbps = (doneBytes / (1024f * 1024f)) / elapsedSec
                    val remainingSec = if (speedMbps > 0.01f) {
                        (((totalBytes - doneBytes).coerceAtLeast(0L) / (1024f * 1024f)) / speedMbps).toLong()
                    } else {
                        0L
                    }
                    updateOperation(
                        FileOperationProgress(
                            operationId = operationId,
                            type = FileOperationType.COMPRESS,
                            title = "${format.labelDe} wird erstellt (${finalArchiveFile.name})",
                            currentFileName = currentName,
                            processedFiles = index,
                            totalFiles = total,
                            processedBytes = doneBytes,
                            totalBytes = totalBytes,
                            speedMegabytesPerSec = speedMbps,
                            estimatedRemainingSeconds = remainingSec,
                            status = if (index >= total) FileOperationStatus.COMPLETED else FileOperationStatus.RUNNING
                        )
                    )
                }
            ).getOrThrow()

            val entity = FileMetadataEntity(
                path = finalArchiveFile.absolutePath,
                name = finalArchiveFile.name,
                volumeId = nodes.first().volumeId,
                mimeType = format.mimeType,
                lastModifiedMillis = finalArchiveFile.lastModified(),
                sizeBytes = finalArchiveFile.length(),
                isDirectory = false
            )
            dao.upsertMetadata(entity)
            fileToVfsNode(finalArchiveFile, nodes.first().volumeId, entity)
        }
    }

    /**
     * Schaltet ein Farb-Label (Tag) für eine Datei oder einen Ordner um und aktualisiert den FTS-Index.
     */
    suspend fun toggleColorTag(node: VfsNode, tagId: String): VfsNode = withContext(Dispatchers.IO) {
        val currentTags = node.tags.toMutableList()
        if (currentTags.any { it.equals(tagId, ignoreCase = true) }) {
            currentTags.removeAll { it.equals(tagId, ignoreCase = true) }
        } else {
            currentTags.add(tagId.lowercase())
        }
        val tagsCsv = currentTags.joinToString(",")
        val existing = dao.getMetadataByPath(node.path)
        val updatedEntity = (existing ?: FileMetadataEntity(
            path = node.path,
            name = node.name,
            volumeId = node.volumeId,
            mimeType = node.mimeType,
            isStarred = node.isStarred,
            isOfflinePinned = node.isOfflinePinned,
            lastModifiedMillis = node.lastModifiedMillis,
            sizeBytes = node.sizeBytes,
            isDirectory = node.isDirectory
        )).copy(tags = tagsCsv)
        dao.upsertMetadata(updatedEntity)
        node.copy(tags = currentTags)
    }

    /**
     * Speichert geänderten Text-/Quellcode-Inhalt aus dem integrierten QuickView-Editor
     * zurück in die Datei (lokal, SAF/Crostini, Google Drive oder SMB/SFTP/WebDAV) und
     * aktualisiert den Volltext-Suchindex (`contentExcerpt`).
     */
    suspend fun saveTextContent(node: VfsNode, newText: String): Result<VfsNode> = withContext(Dispatchers.IO) {
        runCatching {
            require(!node.isReadOnly && !node.isDirectory) { "Datei ist schreibgeschützt." }
            val excerpt = newText.take(4096)
            when {
                node.isSaf -> {
                    val temp = File.createTempFile("saf_edit_", "_${node.name}", context.cacheDir)
                    try {
                        temp.writeText(newText, Charsets.UTF_8)
                        safVolumes.writeFileContent(node, temp)
                    } finally {
                        temp.delete()
                    }
                }
                node.isGoogleDriveApi -> {
                    val temp = File.createTempFile("gdrive_edit_", "_${node.name}", context.cacheDir)
                    try {
                        temp.writeText(newText, Charsets.UTF_8)
                        driveManager.updateFileContent(node, temp)
                    } finally {
                        temp.delete()
                    }
                }
                node.isRemote -> {
                    val loc = remoteVolumes.parse(node.path)
                    val bytes = newText.toByteArray(Charsets.UTF_8)
                    val entry = remoteVolumes.withClient(loc.volumeId) { client ->
                        bytes.inputStream().use { input ->
                            client.upload(loc.relativePath, input, bytes.size.toLong()) {}
                        }
                        client.stat(loc.relativePath)
                    }
                    val cached = File(context.cacheDir, "remote_cache/${loc.volumeId}/${loc.relativePath}")
                    if (cached.exists()) {
                        cached.writeText(newText, Charsets.UTF_8)
                    }
                    if (entry != null) remoteVolumes.toVfsNode(loc.volumeId, entry)
                    else node.copy(sizeBytes = bytes.size.toLong(), lastModifiedMillis = System.currentTimeMillis())
                }
                else -> {
                    val file = File(node.path)
                    file.writeText(newText, Charsets.UTF_8)
                    val existing = dao.getMetadataByPath(node.path)
                    val entity = (existing ?: FileMetadataEntity(
                        path = file.absolutePath,
                        name = file.name,
                        volumeId = node.volumeId,
                        mimeType = node.mimeType,
                        isStarred = node.isStarred,
                        isOfflinePinned = node.isOfflinePinned,
                        tags = node.tags.joinToString(",")
                    )).copy(
                        contentExcerpt = excerpt,
                        lastModifiedMillis = file.lastModified(),
                        sizeBytes = file.length()
                    )
                    dao.upsertMetadata(entity)
                    fileToVfsNode(file, node.volumeId, entity)
                }
            }
        }
    }

    /**
     * Schaltet den "Favoriten" (Starred)-Status einer Datei/eines Ordners um.
     */
    suspend fun toggleStarred(node: VfsNode): VfsNode = withContext(Dispatchers.IO) {
        val newStarred = !node.isStarred
        val existing = dao.getMetadataByPath(node.path)
        if (existing != null) {
            dao.updateStarred(node.path, newStarred)
        } else {
            dao.upsertMetadata(
                FileMetadataEntity(
                    path = node.path,
                    name = node.name,
                    volumeId = node.volumeId,
                    mimeType = node.mimeType,
                    isStarred = newStarred,
                    isOfflinePinned = node.isOfflinePinned,
                    lastModifiedMillis = node.lastModifiedMillis,
                    sizeBytes = node.sizeBytes,
                    isDirectory = node.isDirectory
                )
            )
        }
        node.copy(isStarred = newStarred)
    }

    /**
     * Schaltet den "Offline verfügbar"-Schalter (z. B. für Google Drive Dateien) um.
     */
    suspend fun toggleOfflinePinned(node: VfsNode): VfsNode = withContext(Dispatchers.IO) {
        val newPinned = !node.isOfflinePinned
        val existing = dao.getMetadataByPath(node.path)
        if (existing != null) {
            dao.updateOfflinePinned(node.path, newPinned)
        } else {
            dao.upsertMetadata(
                FileMetadataEntity(
                    path = node.path,
                    name = node.name,
                    volumeId = node.volumeId,
                    mimeType = node.mimeType,
                    isStarred = node.isStarred,
                    isOfflinePinned = newPinned,
                    lastModifiedMillis = node.lastModifiedMillis,
                    sizeBytes = node.sizeBytes,
                    isDirectory = node.isDirectory
                )
            )
        }
        node.copy(isOfflinePinned = newPinned)
    }

    /**
     * Bindet eine echte SMB- / SFTP- / WebDAV-Freigabe in die linke Seitenleiste ein.
     * Die Verbindung wird vorher aufgebaut und durch Auflisten des Wurzelordners geprüft;
     * das Passwort landet verschlüsselt im Android Keystore, nie im Klartext in der Datenbank.
     */
    suspend fun mountRemoteShare(title: String, config: RemoteConnectionConfig): Result<VfsVolume> =
        withContext(Dispatchers.IO) {
            runCatching {
                val volumeId = "net_${UUID.randomUUID().toString().take(8)}"
                val displayTitle = title.trim().ifEmpty { config.host.trim() }
                remoteVolumes.testAndRegister(volumeId, config)
                remoteVolumes.saveCredentials(volumeId, config)
                val rootPath = remoteVolumes.rootPath(volumeId)
                dao.upsertMountedConnection(
                    MountedConnectionEntity(
                        volumeId = volumeId,
                        title = displayTitle,
                        protocol = config.protocol.name,
                        hostOrUri = config.host.trim(),
                        shareOrRemotePath = config.shareOrBasePath.trim(),
                        username = config.username.trim(),
                        localMountPath = rootPath
                    )
                )
                refreshVolumes()
                _volumesFlow.value.first { it.id == volumeId }
            }
        }

    /**
     * Liefert die direkten Unterordner eines Pfads für den aufklappbaren Seitenleisten-Baum.
     * Netzwerkfreigaben werden nicht im Baum aufgeklappt (jeder Aufruf wäre ein Server-Roundtrip).
     */
    suspend fun listSubdirectories(path: String, showHiddenFiles: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            if (remoteVolumes.isRemotePath(path) || safVolumes.isSafPath(path) || driveManager.isDriveApiPath(path)) {
                return@withContext emptyList()
            }
            File(path).listFiles()
                .orEmpty()
                .filter { it.isDirectory && (showHiddenFiles || !it.name.startsWith(".")) }
                .sortedBy { it.name.lowercase() }
        }

    /**
     * Liefert eine lokale Datei für den Knoten. Netzwerk-, SAF- und Google-Drive-Dateien
     * werden in den App-Cache geladen und wiederverwendet, solange Größe und Änderungszeit übereinstimmen.
     */
    suspend fun materialize(node: VfsNode): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (node.isSaf) {
                require(!node.isDirectory) { "Ordner können nicht geöffnet werden." }
                return@runCatching safVolumes.materializeToCache(node)
            }
            if (node.isGoogleDriveApi) {
                require(!node.isDirectory) { "Ordner können nicht geöffnet werden." }
                return@runCatching driveManager.materializeToCache(node)
            }
            if (!node.isRemote) {
                val local = File(node.path)
                require(local.exists()) { "Datei existiert nicht mehr: ${node.name}" }
                return@runCatching local
            }
            require(!node.isDirectory) { "Ordner können nicht geöffnet werden." }
            val location = remoteVolumes.parse(node.path)
            val cached = File(context.cacheDir, "remote_cache/${location.volumeId}/${location.relativePath}")
            val upToDate = cached.exists() && cached.length() == node.sizeBytes &&
                (node.lastModifiedMillis <= 0L || cached.lastModified() == node.lastModifiedMillis)
            if (upToDate) return@runCatching cached
            cached.parentFile?.mkdirs()
            val temp = File(cached.parentFile, ".${cached.name}.part")
            val client = remoteVolumes.openDedicated(location.volumeId)
            try {
                FileOutputStream(temp).use { out -> client.download(location.relativePath, out) { } }
            } catch (e: Exception) {
                temp.delete()
                throw e
            } finally {
                runCatching { client.close() }
            }
            cached.delete()
            if (!temp.renameTo(cached)) {
                temp.delete()
                throw IllegalStateException("Zwischenspeichern fehlgeschlagen: ${node.name}")
            }
            if (node.lastModifiedMillis > 0L) cached.setLastModified(node.lastModifiedMillis)
            cached
        }
    }

    /** Erzeugt einen [VfsNode] für eine lokal neu entstandene Datei (z. B. nach Zuschneiden/Skalieren). */
    suspend fun nodeForLocalFile(file: File, volumeId: String): VfsNode = withContext(Dispatchers.IO) {
        val entity = FileMetadataEntity(
            path = file.absolutePath,
            name = file.name,
            volumeId = volumeId,
            mimeType = resolveMimeType(file),
            lastModifiedMillis = file.lastModified(),
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            isDirectory = file.isDirectory
        )
        dao.upsertMetadata(entity)
        fileToVfsNode(file, volumeId, entity)
    }

    /**
     * Importiert Dateien anderer Apps (Drag & Drop aus der ChromeOS Files App, Zwischenablage)
     * als Kopie in den Zielordner.
     */
    suspend fun importContentUris(
        uris: List<Uri>,
        destinationDirPath: String,
        destinationVolumeId: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val destinationDir = File(destinationDirPath)
            require(destinationDir.isDirectory) { "Zielordner existiert nicht." }
            var imported = 0
            for (uri in uris) {
                val displayName = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "Importierte Datei"
                val target = resolveUniqueFile(File(destinationDir, displayName.replace("/", "_")))
                val input = resolver.openInputStream(uri) ?: continue
                input.use { stream ->
                    FileOutputStream(target).use { out -> stream.copyTo(out, 64 * 1024) }
                }
                dao.upsertMetadata(
                    FileMetadataEntity(
                        path = target.absolutePath,
                        name = target.name,
                        volumeId = destinationVolumeId,
                        mimeType = resolveMimeType(target),
                        lastModifiedMillis = target.lastModified(),
                        sizeBytes = target.length()
                    )
                )
                imported++
            }
            refreshVolumes()
            imported
        }
    }

    /** Pausiert eine laufende Kopier-/Verschiebe-Operation (der Kopierthread wartet am nächsten Block). */
    fun pauseOperation(operationId: String) {
        pausedOperationIds.add(operationId)
        _operationsFlow.update { list ->
            list.map { if (it.operationId == operationId && it.status == FileOperationStatus.RUNNING) it.copy(status = FileOperationStatus.PAUSED, speedMegabytesPerSec = 0f) else it }
        }
    }

    fun resumeOperation(operationId: String) {
        pausedOperationIds.remove(operationId)
        _operationsFlow.update { list ->
            list.map { if (it.operationId == operationId && it.status == FileOperationStatus.PAUSED) it.copy(status = FileOperationStatus.RUNNING) else it }
        }
    }

    /** Antwort aus dem Konfliktdialog („Ersetzen“, „Beide behalten“, „Überspringen“). */
    fun resolveConflict(operationId: String, resolution: ConflictResolution, applyToAll: Boolean) {
        pendingConflicts[operationId]?.complete(ConflictDecision(resolution, applyToAll))
    }

    private fun currentProgress(operationId: String): FileOperationProgress =
        _operationsFlow.value.first { it.operationId == operationId }

    /** Wird zwischen Kopierblöcken aufgerufen: blockiert bei Pause, wirft bei Abbruch. */
    private fun awaitResumeOrThrow(operationId: String) {
        while (operationId in pausedOperationIds && operationId !in cancelledOperationIds) {
            Thread.sleep(PAUSE_POLL_INTERVAL_MS)
        }
        if (operationId in cancelledOperationIds) {
            throw CancellationException("Dateioperation vom Nutzer abgebrochen.")
        }
    }

    fun cancelOperation(operationId: String) {
        cancelledOperationIds.add(operationId)
        pausedOperationIds.remove(operationId)
        pendingConflicts[operationId]?.complete(ConflictDecision(ConflictResolution.SKIP, applyToAll = true))
        _operationsFlow.update { list ->
            list.map {
                if (it.operationId == operationId) {
                    it.copy(status = FileOperationStatus.CANCELLED)
                } else {
                    it
                }
            }
        }
    }

    fun dismissCompletedOperations() {
        _operationsFlow.update { list ->
            list.filter {
                it.status == FileOperationStatus.RUNNING ||
                    it.status == FileOperationStatus.PAUSED ||
                    it.status == FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION
            }
        }
    }

    /** Element einer Netzwerk-Übertragung; [relative] ist relativ zum obersten Quellelement ("" = es selbst). */
    private data class TransferItem(
        val relative: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedMillis: Long
    )

    private fun joinRemote(base: String, relative: String): String =
        if (relative.isEmpty()) base else remoteVolumes.childPath(base, relative)

    /** Liefert einen freien Pfad nach dem Muster „Name (1).ext“ im entfernten Ordner. */
    private fun uniqueRemotePath(client: RemoteFileClient, parentRel: String, name: String, isDirectory: Boolean): String {
        val first = remoteVolumes.childPath(parentRel, name)
        if (client.stat(first) == null) return first
        val dot = name.lastIndexOf('.')
        val hasExt = !isDirectory && dot > 0
        val base = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = remoteVolumes.childPath(parentRel, "$base ($counter)$ext")
            if (client.stat(candidate) == null) return candidate
            counter++
        }
    }

    private fun walkRemote(client: RemoteFileClient, rootRel: String, prefix: String, into: MutableList<TransferItem>) {
        for (entry in client.list(joinRemote(rootRel, prefix))) {
            val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            into += TransferItem(relative, entry.isDirectory, if (entry.isDirectory) 0L else entry.sizeBytes, entry.lastModifiedMillis)
            if (entry.isDirectory) walkRemote(client, rootRel, relative, into)
        }
    }

    /**
     * Kopieren/Verschieben, sobald eine Netzwerkfreigabe beteiligt ist (lokal ↔ Netzwerk, Netzwerk ↔ Netzwerk).
     * Unterstützt Fortschritt (MB/s, Restzeit), Pause, Abbruch und denselben Konfliktdialog wie lokale Operationen.
     * Verschieben innerhalb derselben Freigabe wird als serverseitiges Umbenennen ausgeführt.
     */
    private suspend fun executeRemoteTransfer(
        operationId: String,
        nodes: List<VfsNode>,
        destinationDirPath: String,
        destinationVolumeId: String,
        isMove: Boolean,
        conflictResolution: ConflictResolution?
    ): Result<List<VfsNode>> = withContext(Dispatchers.IO) {
        var stickyResolution: ConflictResolution? = conflictResolution
        val clients = HashMap<String, RemoteFileClient>()
        var partialCleanup: (() -> Unit)? = null
        val opType = if (isMove) FileOperationType.MOVE else FileOperationType.COPY
        val title = if (isMove) "${nodes.size} Element(e) werden verschoben" else "${nodes.size} Element(e) werden kopiert"

        suspend fun clientFor(volumeId: String): RemoteFileClient =
            clients[volumeId] ?: remoteVolumes.openDedicated(volumeId).also { clients[volumeId] = it }

        val result = runCatching {
            val destRemote = remoteVolumes.isRemotePath(destinationDirPath)
            val destLocation = if (destRemote) remoteVolumes.parse(destinationDirPath) else null
            val destLocalDir = if (destRemote) null else File(destinationDirPath).apply { mkdirs() }

            // 1. Quellen erfassen (rekursiv), um Gesamtgröße und Dateianzahl zu kennen.
            val plans = mutableListOf<Pair<VfsNode, List<TransferItem>>>()
            for (node in nodes) {
                if (node.isRemote) {
                    val loc = remoteVolumes.parse(node.path)
                    val client = clientFor(loc.volumeId)
                    val items = mutableListOf(TransferItem("", node.isDirectory, node.sizeBytes, node.lastModifiedMillis))
                    if (node.isDirectory) walkRemote(client, loc.relativePath, "", items)
                    plans += node to items
                } else {
                    val root = File(node.path)
                    if (!root.exists()) continue
                    val items = root.walkTopDown().map { f ->
                        TransferItem(f.relativeTo(root).invariantSeparatorsPath, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified())
                    }.toList()
                    plans += node to items
                }
            }
            fun weight(node: VfsNode) = if (node.isRemote && destRemote) 2 else 1
            val totalBytes = plans.sumOf { (n, items) -> items.sumOf { it.sizeBytes } * weight(n) }.coerceAtLeast(1L)
            val totalFiles = plans.sumOf { it.second.size }.coerceAtLeast(1)

            var processedBytes = 0L
            var processedFiles = 0
            var lastEmitMillis = 0L
            val startMillis = System.currentTimeMillis()

            fun emit(currentName: String, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastEmitMillis < PROGRESS_EMIT_INTERVAL_MS) return
                lastEmitMillis = now
                val elapsedSec = ((now - startMillis) / 1000f).coerceAtLeast(0.05f)
                val speed = (processedBytes / (1024f * 1024f)) / elapsedSec
                val remaining = (totalBytes - processedBytes).coerceAtLeast(0L)
                updateOperation(
                    FileOperationProgress(
                        operationId = operationId,
                        type = opType,
                        title = title,
                        currentFileName = currentName,
                        processedFiles = processedFiles,
                        totalFiles = totalFiles,
                        processedBytes = processedBytes,
                        totalBytes = totalBytes,
                        speedMegabytesPerSec = speed,
                        estimatedRemainingSeconds = if (speed > 0.01f) ((remaining / (1024f * 1024f)) / speed).toLong() else 0L,
                        status = if (operationId in pausedOperationIds) FileOperationStatus.PAUSED else FileOperationStatus.RUNNING
                    )
                )
            }

            fun sink(fileName: String): (Long) -> Unit {
                var last = 0L
                return { total ->
                    awaitResumeOrThrow(operationId)
                    processedBytes += (total - last).coerceAtLeast(0L)
                    last = total
                    emit(fileName)
                }
            }

            suspend fun askConflict(name: String): ConflictResolution {
                stickyResolution?.let { return it }
                updateOperation(
                    currentProgress(operationId).copy(
                        status = FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION,
                        conflictingNodeName = name,
                        speedMegabytesPerSec = 0f
                    )
                )
                val deferred = CompletableDeferred<ConflictDecision>()
                pendingConflicts[operationId] = deferred
                val decision = try {
                    deferred.await()
                } finally {
                    pendingConflicts.remove(operationId)
                }
                if (operationId in cancelledOperationIds) throw CancellationException("Dateioperation vom Nutzer abgebrochen.")
                updateOperation(
                    currentProgress(operationId).copy(status = FileOperationStatus.RUNNING, conflictingNodeName = null)
                )
                if (decision.applyToAll) stickyResolution = decision.resolution
                return decision.resolution
            }

            emit(nodes.firstOrNull()?.name.orEmpty(), force = true)
            val resultNodes = mutableListOf<VfsNode>()

            for ((node, items) in plans) {
                awaitResumeOrThrow(operationId)
                val nodeBytes = items.sumOf { it.sizeBytes } * weight(node)
                val srcLoc = if (node.isRemote) remoteVolumes.parse(node.path) else null
                val srcClient = srcLoc?.let { clientFor(it.volumeId) }
                val destClient = destLocation?.let { clientFor(it.volumeId) }
                val sameRemoteVolume = srcLoc != null && destLocation != null && srcLoc.volumeId == destLocation.volumeId

                if (sameRemoteVolume && node.isDirectory) {
                    val src = srcLoc!!.relativePath
                    val dst = destLocation!!.relativePath
                    if (dst == src || dst.startsWith("$src/")) {
                        throw IllegalArgumentException("„${node.name}“ kann nicht in sich selbst kopiert oder verschoben werden.")
                    }
                }

                // 2. Zielname bestimmen und Konflikte klären.
                var targetRemoteRel: String? = null
                var targetLocal: File? = null
                if (destLocation != null) {
                    val desired = remoteVolumes.childPath(destLocation.relativePath, node.name)
                    val isSameLocation = sameRemoteVolume && desired == srcLoc!!.relativePath
                    if (isSameLocation) {
                        if (isMove) {
                            processedFiles += items.size
                            processedBytes += nodeBytes
                            continue
                        }
                        targetRemoteRel = uniqueRemotePath(destClient!!, destLocation.relativePath, node.name, node.isDirectory)
                    } else if (destClient!!.stat(desired) != null) {
                        when (askConflict(node.name)) {
                            ConflictResolution.SKIP -> {
                                processedFiles += items.size
                                processedBytes += nodeBytes
                                continue
                            }
                            ConflictResolution.REPLACE -> {
                                val existing = destClient.stat(desired)
                                if (existing != null) destClient.delete(desired, existing.isDirectory)
                                targetRemoteRel = desired
                            }
                            ConflictResolution.KEEP_BOTH -> {
                                targetRemoteRel = uniqueRemotePath(destClient, destLocation.relativePath, node.name, node.isDirectory)
                            }
                        }
                    } else {
                        targetRemoteRel = desired
                    }
                } else {
                    val desired = File(destLocalDir!!, node.name)
                    targetLocal = if (desired.exists()) {
                        when (askConflict(node.name)) {
                            ConflictResolution.SKIP -> {
                                processedFiles += items.size
                                processedBytes += nodeBytes
                                continue
                            }
                            ConflictResolution.REPLACE -> desired.also { it.deleteRecursively() }
                            ConflictResolution.KEEP_BOTH -> resolveUniqueFile(desired)
                        }
                    } else {
                        desired
                    }
                }

                // 3a. Schneller Pfad: Verschieben innerhalb derselben Freigabe = serverseitiges Umbenennen.
                if (isMove && sameRemoteVolume) {
                    srcClient!!.rename(srcLoc!!.relativePath, targetRemoteRel!!)
                    processedFiles += items.size
                    processedBytes += nodeBytes
                    emit(node.name)
                    srcClient.stat(targetRemoteRel)?.let { resultNodes += remoteVolumes.toVfsNode(destLocation!!.volumeId, it) }
                    continue
                }

                // 3b. Übertragung Element für Element.
                partialCleanup = if (targetRemoteRel != null) {
                    val rel = targetRemoteRel
                    val client = destClient!!
                    val cleanup: () -> Unit = { runCatching { client.delete(rel, node.isDirectory) } }
                    cleanup
                } else {
                    val file = targetLocal!!
                    val cleanup: () -> Unit = { file.deleteRecursively() }
                    cleanup
                }
                val localSourceRoot = if (node.isRemote) null else File(node.path)
                for (item in items) {
                    awaitResumeOrThrow(operationId)
                    val displayName = item.relative.substringAfterLast('/').ifEmpty { node.name }
                    if (item.isDirectory) {
                        if (targetRemoteRel != null) destClient!!.mkdir(joinRemote(targetRemoteRel, item.relative))
                        else File(targetLocal!!, item.relative).mkdirs()
                        processedFiles++
                        continue
                    }
                    when {
                        localSourceRoot != null -> {
                            val source = if (item.relative.isEmpty()) localSourceRoot else File(localSourceRoot, item.relative)
                            FileInputStream(source).use { input ->
                                destClient!!.upload(joinRemote(targetRemoteRel!!, item.relative), input, source.length(), sink(displayName))
                            }
                        }
                        targetLocal != null -> {
                            val target = if (item.relative.isEmpty()) targetLocal else File(targetLocal, item.relative)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                srcClient!!.download(joinRemote(srcLoc!!.relativePath, item.relative), out, sink(displayName))
                            }
                            if (item.lastModifiedMillis > 0L) target.setLastModified(item.lastModifiedMillis)
                        }
                        else -> {
                            val temp = File.createTempFile("transfer", ".part", context.cacheDir)
                            try {
                                FileOutputStream(temp).use { out ->
                                    srcClient!!.download(joinRemote(srcLoc!!.relativePath, item.relative), out, sink(displayName))
                                }
                                FileInputStream(temp).use { input ->
                                    destClient!!.upload(joinRemote(targetRemoteRel!!, item.relative), input, temp.length(), sink(displayName))
                                }
                            } finally {
                                temp.delete()
                            }
                        }
                    }
                    processedFiles++
                    emit(displayName)
                }
                partialCleanup = null

                // 4. Quelle beim Verschieben entfernen.
                if (isMove) {
                    if (srcLoc != null) {
                        srcClient!!.delete(srcLoc.relativePath, node.isDirectory)
                    } else if (!node.isReadOnly) {
                        File(node.path).deleteRecursively()
                        dao.deleteMetadataByPath(node.path)
                    }
                }

                // 5. Ergebnis-Knoten erzeugen.
                if (targetRemoteRel != null) {
                    destClient!!.stat(targetRemoteRel)?.let { resultNodes += remoteVolumes.toVfsNode(destLocation!!.volumeId, it) }
                } else {
                    val target = targetLocal!!
                    val meta = FileMetadataEntity(
                        path = target.absolutePath,
                        name = target.name,
                        volumeId = destinationVolumeId,
                        mimeType = resolveMimeType(target),
                        lastModifiedMillis = target.lastModified(),
                        sizeBytes = if (target.isDirectory) 0L else target.length(),
                        isDirectory = target.isDirectory
                    )
                    dao.upsertMetadata(meta)
                    resultNodes += fileToVfsNode(target, destinationVolumeId, meta)
                }
            }

            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = title,
                    currentFileName = "Abgeschlossen",
                    processedFiles = totalFiles,
                    totalFiles = totalFiles,
                    processedBytes = totalBytes,
                    totalBytes = totalBytes,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = FileOperationStatus.COMPLETED
                )
            )
            resultNodes.toList()
        }

        result.onFailure { err ->
            partialCleanup?.invoke()
            pausedOperationIds.remove(operationId)
            val cancelled = err is CancellationException || operationId in cancelledOperationIds
            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = if (cancelled) "Dateioperation abgebrochen" else "Fehler bei Dateioperation",
                    currentFileName = if (cancelled) "Vom Nutzer abgebrochen" else err.message ?: "Unbekannter Fehler",
                    processedFiles = 0,
                    totalFiles = nodes.size,
                    processedBytes = 0L,
                    totalBytes = 0L,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = if (cancelled) FileOperationStatus.CANCELLED else FileOperationStatus.FAILED,
                    errorMessage = if (cancelled) null else err.message
                )
            )
        }
        clients.values.forEach { runCatching { it.close() } }
        result
    }

    private suspend fun executeSafOrDriveTransfer(
        operationId: String,
        nodes: List<VfsNode>,
        destinationDirPath: String,
        destinationVolumeId: String,
        isMove: Boolean,
        conflictResolution: ConflictResolution? = null
    ): Result<List<VfsNode>> = withContext(Dispatchers.IO) {
        var stickyResolution: ConflictResolution? = conflictResolution
        val opType = if (isMove) FileOperationType.MOVE else FileOperationType.COPY
        val title = if (isMove) "${nodes.size} Element(e) werden verschoben" else "${nodes.size} Element(e) werden kopiert"
        val totalBytes = nodes.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L)
        val totalFiles = nodes.size.coerceAtLeast(1)
        val startMillis = System.currentTimeMillis()
        var processedBytes = 0L
        var processedFiles = 0

        suspend fun askConflict(name: String): ConflictResolution {
            stickyResolution?.let { return it }
            updateOperation(
                currentProgress(operationId).copy(
                    status = FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION,
                    conflictingNodeName = name,
                    speedMegabytesPerSec = 0f
                )
            )
            val deferred = CompletableDeferred<ConflictDecision>()
            pendingConflicts[operationId] = deferred
            val decision = try {
                deferred.await()
            } finally {
                pendingConflicts.remove(operationId)
            }
            if (operationId in cancelledOperationIds) throw CancellationException("Dateioperation vom Nutzer abgebrochen.")
            updateOperation(
                currentProgress(operationId).copy(status = FileOperationStatus.RUNNING, conflictingNodeName = null)
            )
            if (decision.applyToAll) stickyResolution = decision.resolution
            return decision.resolution
        }

        suspend fun listTargetChildren(targetDirPath: String, targetVolId: String): List<VfsNode> = when {
            safVolumes.isSafPath(targetDirPath) -> safVolumes.list(targetDirPath, targetVolId, showHiddenFiles = true)
            driveManager.isDriveApiPath(targetDirPath) -> driveManager.list(targetDirPath, targetVolId, showHiddenFiles = true)
            else -> File(targetDirPath).listFiles().orEmpty().map { fileToVfsNode(it, targetVolId) }
        }

        suspend fun transferSingleOrRecursive(
            node: VfsNode,
            targetParentPath: String,
            targetVolumeId: String
        ): VfsNode? {
            awaitResumeOrThrow(operationId)
            val existingChildren = runCatching { listTargetChildren(targetParentPath, targetVolumeId) }.getOrDefault(emptyList())
            val conflictNode = existingChildren.firstOrNull { it.name == node.name }
            var effectiveName = node.name
            if (conflictNode != null) {
                when (askConflict(node.name)) {
                    ConflictResolution.SKIP -> return null
                    ConflictResolution.REPLACE -> {
                        when {
                            conflictNode.isSaf -> safVolumes.delete(conflictNode)
                            conflictNode.isGoogleDriveApi -> driveManager.delete(conflictNode)
                            !conflictNode.isRemote -> File(conflictNode.path).deleteRecursively()
                        }
                    }
                    ConflictResolution.KEEP_BOTH -> {
                        val base = node.name.substringBeforeLast('.', node.name)
                        val ext = if (!node.isDirectory && node.name.contains('.')) ".${node.name.substringAfterLast('.')}" else ""
                        var counter = 1
                        while (existingChildren.any { it.name == "$base ($counter)$ext" }) counter++
                        effectiveName = "$base ($counter)$ext"
                    }
                }
            }

            if (node.isDirectory) {
                val createdFolder = when {
                    safVolumes.isSafPath(targetParentPath) ->
                        safVolumes.createDirectory(targetParentPath, effectiveName, targetVolumeId)
                    driveManager.isDriveApiPath(targetParentPath) ->
                        driveManager.createFolder(targetParentPath, effectiveName, targetVolumeId)
                    else -> {
                        val dir = resolveUniqueFile(File(File(targetParentPath).apply { mkdirs() }, effectiveName)).apply { mkdirs() }
                        nodeForLocalFile(dir, targetVolumeId)
                    }
                }
                val children = when {
                    node.isSaf -> safVolumes.list(node.path, node.volumeId, showHiddenFiles = true)
                    node.isGoogleDriveApi -> driveManager.list(node.path, node.volumeId, showHiddenFiles = true)
                    else -> File(node.path).listFiles().orEmpty().map { fileToVfsNode(it, node.volumeId) }
                }
                for (child in children) {
                    transferSingleOrRecursive(child, createdFolder.path, targetVolumeId)
                }
                if (isMove && !node.isReadOnly) {
                    when {
                        node.isSaf -> safVolumes.delete(node)
                        node.isGoogleDriveApi -> driveManager.delete(node)
                        !node.isRemote -> {
                            File(node.path).deleteRecursively()
                            dao.deleteMetadataByPath(node.path)
                        }
                    }
                }
                return createdFolder
            }

            val localSource = materialize(node).getOrThrow()
            val sourceWithEffectiveName = if (effectiveName != localSource.name) {
                File(context.cacheDir, "transfer_rename/$effectiveName").apply {
                    parentFile?.mkdirs()
                    localSource.copyTo(this, overwrite = true)
                }
            } else {
                localSource
            }
            val created = try {
                when {
                    safVolumes.isSafPath(targetParentPath) ->
                        safVolumes.copyLocalFileToSaf(sourceWithEffectiveName, targetParentPath, node.mimeType, targetVolumeId) { delta ->
                            processedBytes += delta
                        }
                    driveManager.isDriveApiPath(targetParentPath) -> {
                        val uploaded = driveManager.uploadLocalFile(sourceWithEffectiveName, targetParentPath, node.mimeType, targetVolumeId)
                        processedBytes += sourceWithEffectiveName.length()
                        uploaded
                    }
                    else -> {
                        val destDir = File(targetParentPath).apply { mkdirs() }
                        val target = File(destDir, effectiveName)
                        sourceWithEffectiveName.copyTo(target, overwrite = true)
                        processedBytes += target.length()
                        nodeForLocalFile(target, targetVolumeId)
                    }
                }
            } finally {
                if (sourceWithEffectiveName != localSource) sourceWithEffectiveName.delete()
            }

            if (isMove && !node.isReadOnly) {
                when {
                    node.isSaf -> safVolumes.delete(node)
                    node.isGoogleDriveApi -> driveManager.delete(node)
                    !node.isRemote -> {
                        File(node.path).deleteRecursively()
                        dao.deleteMetadataByPath(node.path)
                    }
                }
            }
            return created
        }

        updateOperation(
            FileOperationProgress(
                operationId = operationId,
                type = opType,
                title = title,
                currentFileName = nodes.firstOrNull()?.name.orEmpty(),
                processedFiles = 0,
                totalFiles = totalFiles,
                processedBytes = 0L,
                totalBytes = totalBytes,
                speedMegabytesPerSec = 0f,
                estimatedRemainingSeconds = 0L,
                status = FileOperationStatus.RUNNING
            )
        )

        runCatching {
            val resultNodes = mutableListOf<VfsNode>()
            for (node in nodes) {
                val created = transferSingleOrRecursive(node, destinationDirPath, destinationVolumeId)
                processedFiles++
                val elapsedSec = ((System.currentTimeMillis() - startMillis) / 1000f).coerceAtLeast(0.05f)
                val speed = (processedBytes / (1024f * 1024f)) / elapsedSec
                updateOperation(
                    FileOperationProgress(
                        operationId = operationId,
                        type = opType,
                        title = title,
                        currentFileName = node.name,
                        processedFiles = processedFiles,
                        totalFiles = totalFiles,
                        processedBytes = processedBytes,
                        totalBytes = totalBytes,
                        speedMegabytesPerSec = speed,
                        estimatedRemainingSeconds = 0L,
                        status = FileOperationStatus.RUNNING
                    )
                )
                if (created != null) resultNodes += created
            }
            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = title,
                    currentFileName = "Abgeschlossen",
                    processedFiles = totalFiles,
                    totalFiles = totalFiles,
                    processedBytes = totalBytes,
                    totalBytes = totalBytes,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = FileOperationStatus.COMPLETED
                )
            )
            resultNodes.toList()
        }.onFailure { err ->
            val cancelled = err is CancellationException || operationId in cancelledOperationIds
            updateOperation(
                FileOperationProgress(
                    operationId = operationId,
                    type = opType,
                    title = if (cancelled) "Dateioperation abgebrochen" else "Fehler bei Dateioperation",
                    currentFileName = err.message ?: "Unbekannter Fehler",
                    processedFiles = processedFiles,
                    totalFiles = totalFiles,
                    processedBytes = processedBytes,
                    totalBytes = totalBytes,
                    speedMegabytesPerSec = 0f,
                    estimatedRemainingSeconds = 0L,
                    status = if (cancelled) FileOperationStatus.CANCELLED else FileOperationStatus.FAILED,
                    errorMessage = err.message
                )
            )
        }
    }

    private suspend fun listRecentNodes(
        showHiddenFiles: Boolean,
        categoryFilter: SearchFileTypeFilter
    ): List<VfsNode> {
        if (!hasAllFilesAccess()) return emptyList()
        val allRoots = buildList {
            add(VOLUME_MY_FILES to myFilesRoot)
            File("/mnt/chromeos/LinuxFiles").takeIf { it.exists() && it.canRead() }?.let {
                add(VOLUME_LINUX to it)
            }
            File("/mnt/chromeos/GoogleDrive").takeIf { it.exists() && it.canRead() }?.let {
                add(VOLUME_DRIVE to it)
            }
        }
        val recentFiles = mutableListOf<Pair<String, File>>()
        for ((volId, root) in allRoots) {
            if (!root.exists() || !root.canRead()) continue
            root.walkTopDown()
                .maxDepth(4)
                .onEnter { dir ->
                    (showHiddenFiles || !dir.name.startsWith(".")) &&
                        !dir.absolutePath.endsWith("/Android/data") &&
                        !dir.absolutePath.endsWith("/Android/obb")
                }
                .filter { it.isFile && (showHiddenFiles || !it.name.startsWith(".")) }
                .forEach { recentFiles.add(volId to it) }
        }
        val sortedRecent = recentFiles.sortedByDescending { it.second.lastModified() }.take(80)
        val metaMap = dao.getMetadataForPaths(sortedRecent.map { it.second.absolutePath }).associateBy { it.path }

        return sortedRecent.map { (volId, file) ->
            fileToVfsNode(file, volId, metaMap[file.absolutePath])
        }.filter { matchesFileTypeFilter(it, categoryFilter) }
    }

    private suspend fun listStarredNodes(showHiddenFiles: Boolean): List<VfsNode> {
        val starredEntities = dao.getStarredFiles()
        return starredEntities.mapNotNull { entity ->
            val file = File(entity.path)
            if (!file.exists()) return@mapNotNull null
            if (!showHiddenFiles && file.name.startsWith(".")) return@mapNotNull null
            fileToVfsNode(file, entity.volumeId, entity)
        }
    }

    private suspend fun listOfflinePinnedNodes(showHiddenFiles: Boolean): List<VfsNode> {
        val pinnedEntities = dao.getOfflinePinnedFiles()
        val combinedPaths = pinnedEntities.map { it.path }.distinct()
        val metaMap = dao.getMetadataForPaths(combinedPaths).associateBy { it.path }

        return combinedPaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            if (!showHiddenFiles && file.name.startsWith(".")) return@mapNotNull null
            val meta = metaMap[path]
            fileToVfsNode(
                file = file,
                volumeId = meta?.volumeId ?: VOLUME_DRIVE,
                metadata = meta?.copy(isOfflinePinned = true)
            ).copy(isOfflinePinned = true)
        }
    }

    private suspend fun listTrashNodes(): List<VfsNode> {
        val entries = dao.getAllTrashEntries().associateBy { it.trashPath }
        val files = trashRoot.listFiles().orEmpty()
        return files.map { file ->
            val entry = entries[file.absolutePath]
            fileToVfsNode(
                file = file,
                volumeId = VOLUME_TRASH,
                metadata = null
            ).copy(
                trashedFromPath = entry?.originalPath ?: File(myFilesRoot, file.name).absolutePath,
                trashedAtMillis = entry?.trashedAtMillis ?: file.lastModified()
            )
        }
    }

    private suspend fun purgeExpiredTrashEntries() {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MILLIS
        val expired = dao.getExpiredTrashEntries(cutoff)
        for (entry in expired) {
            File(entry.trashPath).deleteRecursively()
            dao.deleteTrashEntry(entry.trashPath)
        }
    }

    private fun fileToVfsNode(
        file: File,
        volumeId: String,
        metadata: FileMetadataEntity? = null,
        isReadOnly: Boolean = false
    ): VfsNode {
        val isDir = file.isDirectory
        val ext = if (isDir) "" else file.extension.lowercase()
        val mime = metadata?.mimeType ?: resolveMimeType(file)
        val tagsList = metadata?.tags
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        // Text-/Code-Dateien (< 32 KB), die noch nicht im FTS4-Index liegen, im Hintergrund indizieren.
        if (!isDir && (metadata == null || metadata.contentExcerpt.isEmpty()) &&
            file.length() in 1..32_768 &&
            (mime.startsWith("text/") || ext in VfsNode.CODE_EXTENSIONS || ext == "md" || ext == "txt")
        ) {
            repositoryScope.launch {
                runCatching {
                    val excerpt = file.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        val n = reader.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    if (excerpt.isNotBlank()) {
                        val existing = dao.getMetadataByPath(file.absolutePath)
                        dao.upsertMetadata(
                            (existing ?: FileMetadataEntity(
                                path = file.absolutePath,
                                name = file.name,
                                volumeId = volumeId,
                                mimeType = mime,
                                lastModifiedMillis = file.lastModified(),
                                sizeBytes = file.length(),
                                isDirectory = false
                            )).copy(contentExcerpt = excerpt)
                        )
                    }
                }
            }
        }

        return VfsNode(
            id = file.absolutePath,
            name = file.name,
            path = file.absolutePath,
            parentPath = file.parentFile?.absolutePath.orEmpty(),
            volumeId = volumeId,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else file.length(),
            lastModifiedMillis = file.lastModified(),
            mimeType = mime,
            extension = ext,
            isStarred = metadata?.isStarred ?: false,
            isOfflinePinned = metadata?.isOfflinePinned ?: false,
            isHidden = file.name.startsWith("."),
            isReadOnly = isReadOnly || !file.canWrite(),
            tags = tagsList,
            exifMetadata = null,
            audioMetadata = null,
            childCount = if (isDir) file.list()?.size ?: 0 else null
        )
    }

    /**
     * Lädt EXIF- (Auflösung, Kamera, Blende, ISO, Belichtung, GPS) bzw. ID3-Metadaten
     * für die rechte Info-Spalte. Fehlende Werte bleiben null – es werden keine Werte erfunden.
     */
    suspend fun loadDetailedMetadata(node: VfsNode): VfsNode = withContext(Dispatchers.IO) {
        if (node.isDirectory) return@withContext node
        val file = File(node.path)
        if (!file.exists()) return@withContext node
        when {
            node.isImage -> node.copy(exifMetadata = extractImageExif(file, node.extension))
            node.isAudio -> node.copy(audioMetadata = extractAudioMetadata(file))
            else -> node
        }
    }

    private fun extractImageExif(file: File, ext: String): ExifMetadata? {
        if (ext == "svg") {
            return runCatching {
                val header = file.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    val n = reader.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
                val svgTag = Regex("<svg[^>]*>", RegexOption.IGNORE_CASE).find(header)?.value.orEmpty()
                val w = Regex("""width\s*=\s*"([\d.]+)""").find(svgTag)?.groupValues?.get(1)?.toFloatOrNull()
                val h = Regex("""height\s*=\s*"([\d.]+)""").find(svgTag)?.groupValues?.get(1)?.toFloatOrNull()
                if (w != null && h != null) ExifMetadata(widthPx = w.toInt(), heightPx = h.toInt()) else null
            }.getOrNull()
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            val gps = exif?.latLong?.let { (lat, lon) -> "%.6f, %.6f".format(lat, lon) }
            ExifMetadata(
                widthPx = bounds.outWidth.coerceAtLeast(0),
                heightPx = bounds.outHeight.coerceAtLeast(0),
                cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL),
                aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                iso = exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                exposureTime = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" },
                gpsCoordinates = gps
            )
        }.getOrNull()
    }

    private fun extractAudioMetadata(file: File): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            AudioMetadata(
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()?.div(1000)
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun matchesFileTypeFilter(node: VfsNode, filter: SearchFileTypeFilter): Boolean {
        return when (filter) {
            SearchFileTypeFilter.ALL -> true
            SearchFileTypeFilter.IMAGES -> node.isImage
            SearchFileTypeFilter.VIDEOS -> node.isVideo
            SearchFileTypeFilter.AUDIO -> node.isAudio
            SearchFileTypeFilter.DOCUMENTS -> node.isDocumentOrCode
        }
    }

    private fun sortNodes(nodes: List<VfsNode>, sortSpec: SortSpec): List<VfsNode> {
        val comparator = Comparator<VfsNode> { a, b ->
            if (sortSpec.foldersFirst && a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }
            val rawCompare = when (sortSpec.column) {
                SortColumn.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                SortColumn.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                SortColumn.TYPE -> a.germanTypeLabel.lowercase().compareTo(b.germanTypeLabel.lowercase())
                SortColumn.DATE_MODIFIED -> a.lastModifiedMillis.compareTo(b.lastModifiedMillis)
            }
            if (sortSpec.direction == SortDirection.ASCENDING) rawCompare else -rawCompare
        }
        return nodes.sortedWith(comparator)
    }

    private fun resolveMimeType(file: File): String {
        if (file.isDirectory) return "vnd.android.document/directory"
        val ext = file.extension.lowercase()
        return when (ext) {
            "md" -> "text/markdown"
            "kt", "kts" -> "text/x-kotlin"
            "rs" -> "text/x-rust"
            "json" -> "application/json"
            "yml", "yaml" -> "text/yaml"
            "svg" -> "image/svg+xml"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            "pdf" -> "application/pdf"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
                ?: "application/octet-stream"
        }
    }

    private fun resolveUniqueFile(desiredFile: File): File {
        if (!desiredFile.exists()) return desiredFile
        val parent = desiredFile.parentFile ?: sandboxRootDir
        val baseName = if (desiredFile.isDirectory) {
            desiredFile.name
        } else {
            desiredFile.nameWithoutExtension
        }
        val ext = if (desiredFile.isDirectory || desiredFile.extension.isEmpty()) {
            ""
        } else {
            ".${desiredFile.extension}"
        }
        var counter = 1
        while (true) {
            val candidate = File(parent, "$baseName ($counter)$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun copyRecursiveWithProgress(
        source: File,
        target: File,
        checkpoint: () -> Unit = {},
        onBytesCopied: (Long, String) -> Unit,
        onFileDone: () -> Unit
    ) {
        checkpoint()
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                copyRecursiveWithProgress(
                    source = child,
                    target = File(target, child.name),
                    checkpoint = checkpoint,
                    onBytesCopied = onBytesCopied,
                    onFileDone = onFileDone
                )
            }
            onFileDone()
        } else {
            target.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            checkpoint()
                            output.write(buffer, 0, read)
                            onBytesCopied(read.toLong(), source.name)
                        }
                        read = input.read(buffer)
                    }
                }
            }
            target.setLastModified(source.lastModified())
            onFileDone()
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun calculateDirectorySizeOrFileSize(file: File): Long {
        return if (file.isDirectory) calculateDirectorySize(file) else file.length()
    }

    private fun countFilesRecursively(file: File): Int {
        return if (file.isDirectory) file.walkTopDown().count() else 1
    }

    private fun updateOperation(progress: FileOperationProgress) {
        _operationsFlow.update { current ->
            val index = current.indexOfFirst { it.operationId == progress.operationId }
            if (index >= 0) {
                current.toMutableList().apply { set(index, progress) }
            } else {
                current + progress
            }
        }
    }
}
