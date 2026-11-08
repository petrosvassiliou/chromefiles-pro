package de.chromefiles.app.model

import kotlinx.serialization.Serializable

/**
 * Repräsentiert die verschiedenen Volume-Typen exakt nach dem Vorbild der ChromeOS Files App.
 */
enum class VfsVolumeType(val displayTitleDe: String) {
    RECENT("Zuletzt verwendet"),
    STARRED("Favoriten"),
    MY_FILES("Meine Dateien"),
    LINUX_CROSTINI("Linux-Dateien"),
    GOOGLE_DRIVE("Google Drive"),
    MOUNTED_ARCHIVE("Eingehängtes Archiv"),
    REMOVABLE_MEDIA("Wechselmedium"),
    SMB_SHARE("Netzwerkfreigabe (SMB/SFTP)"),
    TRASH("Papierkorb")
}

/**
 * Ein Volume oder ein Unterknoten in der linken ChromeOS-Navigationsleiste.
 */
data class VfsVolume(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val type: VfsVolumeType,
    val rootPath: String,
    val isEjectable: Boolean = false,
    val isExpandable: Boolean = false,
    val isReadOnly: Boolean = false,
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val sourceArchivePath: String? = null,
    val children: List<VfsVolume> = emptyList()
) {
    val freeBytes: Long
        get() = (totalBytes - usedBytes).coerceAtLeast(0L)

    val usageRatio: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class FileColorTag(val id: String, val labelDe: String, val argbHex: Long) {
    RED("rot", "Rot", 0xFFE53935),
    YELLOW("gelb", "Gelb", 0xFFFDD835),
    GREEN("gruen", "Grün", 0xFF43A047),
    BLUE("blau", "Blau", 0xFF1E88E5),
    PURPLE("violett", "Violett", 0xFF8E24AA);

    companion object {
        fun fromId(id: String): FileColorTag? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

/**
 * Einheitlicher Dateisystem-Knoten (Datei oder Ordner) über lokale Pfade,
 * gemountete Archive, SAF-Crostini, Google Drive, SMB und Papierkorb hinweg.
 */
@Serializable
data class VfsNode(
    val id: String,
    val name: String,
    val path: String,
    val parentPath: String,
    val volumeId: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val mimeType: String,
    val extension: String,
    val isStarred: Boolean = false,
    val isOfflinePinned: Boolean = false,
    val isHidden: Boolean = false,
    val isReadOnly: Boolean = false,
    val tags: List<String> = emptyList(),
    val trashedFromPath: String? = null,
    val trashedAtMillis: Long? = null,
    val exifMetadata: ExifMetadata? = null,
    val audioMetadata: AudioMetadata? = null,
    val childCount: Int? = null
) {
    /** Datei/Ordner auf einer verbundenen SMB-/SFTP-/WebDAV-Freigabe, SAF-Wurzel oder Live-Drive-API. */
    val isRemote: Boolean
        get() = path.startsWith(REMOTE_SCHEME) || path.startsWith(SAF_SCHEME) || path.startsWith(GDRIVE_SCHEME)

    val isSaf: Boolean
        get() = path.startsWith(SAF_SCHEME)

    val isGoogleDriveApi: Boolean
        get() = path.startsWith(GDRIVE_SCHEME)

    val isArchive: Boolean
        get() = !isDirectory && extension.lowercase() in ARCHIVE_EXTENSIONS

    val isImage: Boolean
        get() = !isDirectory && (mimeType.startsWith("image/") || extension.lowercase() in IMAGE_EXTENSIONS)

    val isVideo: Boolean
        get() = !isDirectory && (mimeType.startsWith("video/") || extension.lowercase() in VIDEO_EXTENSIONS)

    val isAudio: Boolean
        get() = !isDirectory && (mimeType.startsWith("audio/") || extension.lowercase() in AUDIO_EXTENSIONS)

    val isPdf: Boolean
        get() = !isDirectory && (mimeType == "application/pdf" || extension.equals("pdf", ignoreCase = true))

    val isDocumentOrCode: Boolean
        get() = !isDirectory && (
            isPdf ||
                mimeType.startsWith("text/") ||
                extension.lowercase() in DOCUMENT_AND_CODE_EXTENSIONS
            )

    val germanTypeLabel: String
        get() = when {
            isDirectory -> "Ordner"
            isArchive -> "${extension.uppercase()}-Archiv"
            isPdf -> "PDF-Dokument"
            isImage -> "${extension.uppercase().ifBlank { "Bild" }}-Bild"
            isVideo -> "${extension.uppercase().ifBlank { "Video" }}-Video"
            isAudio -> "${extension.uppercase().ifBlank { "Audio" }}-Audio"
            extension.lowercase() in CODE_EXTENSIONS -> "${extension.uppercase()}-Quellcode"
            extension.equals("md", ignoreCase = true) -> "Markdown-Dokument"
            extension.equals("txt", ignoreCase = true) -> "Textdokument"
            extension.isNotBlank() -> "${extension.uppercase()}-Datei"
            else -> "Datei"
        }

    companion object {
        const val REMOTE_SCHEME = "remote://"
        const val SAF_SCHEME = "saf://"
        const val GDRIVE_SCHEME = "gdrive://"
        val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "7z", "rar")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "svg", "bmp", "heic")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi")
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac")
        val CODE_EXTENSIONS = setOf("kt", "kts", "java", "py", "js", "ts", "rs", "go", "c", "cpp", "h", "sh", "json", "xml", "yml", "yaml", "html", "css", "sql")
        val DOCUMENT_AND_CODE_EXTENSIONS = CODE_EXTENSIONS + setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "rtf")
    }
}

@Serializable
data class ExifMetadata(
    val widthPx: Int,
    val heightPx: Int,
    val cameraModel: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val exposureTime: String? = null,
    val gpsCoordinates: String? = null
)

@Serializable
data class AudioMetadata(
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val durationMillis: Long = 0L,
    val bitrateKbps: Int? = null
)

/**
 * ChromeOS Suchfilter: Ort (Location)
 */
enum class SearchLocation(val labelDe: String) {
    EVERYWHERE("Überall"),
    CURRENT_FOLDER("Aktueller Ordner"),
    MY_FILES("Meine Dateien"),
    GOOGLE_DRIVE("Google Drive")
}

/**
 * ChromeOS Suchfilter: Änderungsdatum (Modified Date)
 */
enum class SearchModifiedFilter(val labelDe: String, val maxAgeMillis: Long?) {
    ANYTIME("Beliebig", null),
    TODAY("Heute", 24L * 60 * 60 * 1000),
    YESTERDAY("Gestern", 48L * 60 * 60 * 1000),
    LAST_7_DAYS("Letzte 7 Tage", 7L * 24 * 60 * 60 * 1000),
    LAST_30_DAYS("Letzte 30 Tage", 30L * 24 * 60 * 60 * 1000),
    THIS_YEAR("Dieses Jahr", 365L * 24 * 60 * 60 * 1000)
}

/**
 * ChromeOS Suchfilter & "Zuletzt verwendet"-Filter: Dateityp
 */
enum class SearchFileTypeFilter(val labelDe: String) {
    ALL("Alle Typen"),
    IMAGES("Bilder"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Dokumente")
}

data class SearchFilterState(
    val query: String = "",
    val location: SearchLocation = SearchLocation.EVERYWHERE,
    val modifiedFilter: SearchModifiedFilter = SearchModifiedFilter.ANYTIME,
    val fileTypeFilter: SearchFileTypeFilter = SearchFileTypeFilter.ALL
) {
    val isActive: Boolean
        get() = query.isNotBlank() ||
            location != SearchLocation.EVERYWHERE ||
            modifiedFilter != SearchModifiedFilter.ANYTIME ||
            fileTypeFilter != SearchFileTypeFilter.ALL
}

/**
 * Sortierspalten exakt wie in der ChromeOS Files Tabellenansicht.
 */
enum class SortColumn(val labelDe: String) {
    NAME("Name"),
    SIZE("Größe"),
    TYPE("Typ"),
    DATE_MODIFIED("Änderungsdatum")
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}

data class SortSpec(
    val column: SortColumn = SortColumn.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true
)

/**
 * Konfliktauflösung bei Dateioperationen im ChromeOS Operations-Center.
 */
enum class ConflictResolution(val labelDe: String) {
    REPLACE("Ersetzen"),
    KEEP_BOTH("Beide behalten"),
    SKIP("Überspringen")
}

enum class FileOperationType(val labelDe: String) {
    COPY("Kopieren"),
    MOVE("Verschieben"),
    COMPRESS("Komprimieren (ZIP)"),
    EXTRACT("Entpacken"),
    TRASH("In Papierkorb verschieben"),
    RESTORE("Aus Papierkorb wiederherstellen"),
    DELETE_PERMANENTLY("Endgültig löschen")
}

enum class FileOperationStatus {
    RUNNING,
    PAUSED,
    WAITING_FOR_CONFLICT_RESOLUTION,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class FileOperationProgress(
    val operationId: String,
    val type: FileOperationType,
    val title: String,
    val currentFileName: String,
    val processedFiles: Int,
    val totalFiles: Int,
    val processedBytes: Long,
    val totalBytes: Long,
    val speedMegabytesPerSec: Float,
    val estimatedRemainingSeconds: Long,
    val status: FileOperationStatus,
    val conflictingNodeName: String? = null,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = when {
            totalBytes > 0L -> (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (processedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * Ein Segment der interaktiven ChromeOS-Breadcrumb-Pfadleiste.
 */
data class BreadcrumbSegment(
    val title: String,
    val path: String,
    val volumeId: String
)
