package de.chromefiles.app.data.saf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.chromefiles.app.data.local.ChromeFilesDao
import de.chromefiles.app.data.local.MountedConnectionEntity
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolume
import de.chromefiles.app.model.VfsVolumeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bindet das Android Storage Access Framework (`DocumentsContract` / `ACTION_OPEN_DOCUMENT_TREE`)
 * an das virtuelle Dateisystem an.
 *
 * Unterstützt lokale Ordner, SD-Karten/USB-Medien, den ChromeOS **Linux-Container (Crostini)**
 * sowie Cloud-DocumentProvider wie **Google Drive (`com.google.android.apps.docs.storage`)**
 * inklusive asynchronem Nachladen (`DocumentsContract.EXTRA_LOADING`) und `DocumentFile`-Fallback.
 *
 * Pfad-Schema: `saf://<volumeId>/<encodedDocumentId>`
 */
@Singleton
class SafVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ChromeFilesDao
) {
    data class SafLocation(
        val volumeId: String,
        val treeUri: Uri,
        val documentId: String
    )

    companion object {
        const val SAF_SCHEME = "saf://"
        const val PROTOCOL_SAF = "SAF"
        const val PROTOCOL_SAF_LINUX = "SAF_LINUX"
        const val PROTOCOL_SAF_DRIVE = "SAF_DRIVE"
        const val LINUX_SAF_VOLUME_ID = "linux_files_saf"
        const val DRIVE_SAF_VOLUME_ID = "google_drive_saf"
    }

    fun isSafPath(path: String): Boolean = path.startsWith(SAF_SCHEME)

    fun rootPath(volumeId: String): String = "$SAF_SCHEME$volumeId"

    fun pathFor(volumeId: String, documentId: String): String =
        "$SAF_SCHEME$volumeId/${Uri.encode(documentId)}"

    /**
     * Nimmt einen vom System-Ordner-Picker (`ACTION_OPEN_DOCUMENT_TREE`) gelieferten Baum-URI entgegen,
     * sichert die Lese-/Schreibberechtigung dauerhaft (mit Read-Only-Fallback für Cloud-Ordner)
     * und legt den Mount-Eintrag in Room an.
     */
    suspend fun registerTreeUri(
        treeUri: Uri,
        asLinuxContainer: Boolean = false,
        asGoogleDrive: Boolean = false
    ): Result<VfsVolume> = withContext(Dispatchers.IO) {
        runCatching {
            val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, rwFlags)
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }

            val rootDocId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                runCatching { DocumentsContract.getDocumentId(treeUri) }
                    .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
            } else {
                DocumentsContract.getTreeDocumentId(treeUri)
            }

            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val docFile = DocumentFile.fromTreeUri(context, docUri)
                ?: DocumentFile.fromTreeUri(context, treeUri)

            val rawTitle = docFile?.name?.takeIf { it.isNotBlank() }
                ?: queryDocumentDisplayName(docUri)
                ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Eingebundener Ordner" }

            val isDriveUri = asGoogleDrive || treeUri.authority?.contains("com.google.android.apps.docs") == true
            val volumeId = when {
                isDriveUri -> DRIVE_SAF_VOLUME_ID
                asLinuxContainer -> LINUX_SAF_VOLUME_ID
                else -> "saf_${UUID.randomUUID().toString().take(8)}"
            }
            val title = when {
                isDriveUri -> if (rawTitle.equals("Google Drive", ignoreCase = true)) "Google Drive" else "Google Drive ($rawTitle)"
                asLinuxContainer -> "Linux-Dateien"
                else -> rawTitle
            }
            val protocol = when {
                isDriveUri -> PROTOCOL_SAF_DRIVE
                asLinuxContainer -> PROTOCOL_SAF_LINUX
                else -> PROTOCOL_SAF
            }
            val path = pathFor(volumeId, rootDocId)

            val entity = MountedConnectionEntity(
                volumeId = volumeId,
                title = title,
                protocol = protocol,
                hostOrUri = treeUri.toString(),
                shareOrRemotePath = rootDocId,
                username = "",
                localMountPath = path
            )
            dao.upsertMountedConnection(entity)
            toVfsVolume(entity)
        }
    }

    fun toVfsVolume(entity: MountedConnectionEntity): VfsVolume {
        val isLinux = entity.protocol == PROTOCOL_SAF_LINUX || entity.volumeId == LINUX_SAF_VOLUME_ID
        val isDrive = entity.protocol == PROTOCOL_SAF_DRIVE || entity.volumeId == DRIVE_SAF_VOLUME_ID
        val authority = runCatching { Uri.parse(entity.hostOrUri).authority.orEmpty() }.getOrDefault("")
        return VfsVolume(
            id = when {
                isDrive -> "google_drive"
                isLinux -> "linux_files"
                else -> entity.volumeId
            },
            title = if (isDrive) "Google Drive" else entity.title,
            subtitle = when {
                isDrive -> "Verbunden (${entity.title})"
                isLinux -> "Crostini SAF (${authority.ifBlank { "DocumentProvider" }})"
                else -> "Eingebundener Ordner • ${entity.shareOrRemotePath.substringAfterLast(':')}"
            },
            type = when {
                isDrive -> VfsVolumeType.GOOGLE_DRIVE
                isLinux -> VfsVolumeType.LINUX_CROSTINI
                else -> VfsVolumeType.REMOVABLE_MEDIA
            },
            rootPath = entity.localMountPath,
            isEjectable = true,
            isExpandable = true,
            isReadOnly = false
        )
    }

    suspend fun resolveLocation(safPath: String): SafLocation? = withContext(Dispatchers.IO) {
        if (!isSafPath(safPath)) return@withContext null
        val rest = safPath.removePrefix(SAF_SCHEME)
        val rawVolumeId = rest.substringBefore('/')
        val dbVolumeId = when (rawVolumeId) {
            "linux_files" -> LINUX_SAF_VOLUME_ID
            "google_drive" -> DRIVE_SAF_VOLUME_ID
            else -> rawVolumeId
        }
        val allConnections = dao.getAllMountedConnections()
        val conn = allConnections.firstOrNull {
            it.volumeId == dbVolumeId || it.volumeId == rawVolumeId
        } ?: allConnections.firstOrNull {
            // Fallback falls Google Drive oder Linux über einen allgemeinen SAF-Mount geöffnet wurde
            (rawVolumeId == "google_drive" && it.protocol == PROTOCOL_SAF_DRIVE) ||
                (rawVolumeId == "linux_files" && it.protocol == PROTOCOL_SAF_LINUX)
        } ?: return@withContext null

        val treeUri = Uri.parse(conn.hostOrUri)
        val encodedDocId = rest.substringAfter('/', "")
        val docId = if (encodedDocId.isNotEmpty()) {
            Uri.decode(encodedDocId)
        } else {
            conn.shareOrRemotePath.ifBlank {
                if (DocumentsContract.isDocumentUri(context, treeUri)) {
                    runCatching { DocumentsContract.getDocumentId(treeUri) }
                        .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
                } else {
                    DocumentsContract.getTreeDocumentId(treeUri)
                }
            }
        }
        SafLocation(volumeId = conn.volumeId, treeUri = treeUri, documentId = docId)
    }

    /**
     * Listet alle Dateien und Unterordner eines SAF-/Google-Drive-Ordners auf.
     * Unterstützt asynchron ladende Cloud-Provider (`DocumentsContract.EXTRA_LOADING`),
     * sichere Spaltenabfragen ohne `getColumnIndexOrThrow`, einen `DocumentFile`-Fallback
     * sowie direkten Dateisystem-Fallback für `com.android.externalstorage.documents`.
     */
    suspend fun list(safPath: String, volumeId: String, showHiddenFiles: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            val loc = resolveLocation(safPath) ?: return@withContext emptyList()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(loc.treeUri, loc.documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
            )

            val rawItems = mutableListOf<VfsNode>()

            fun readCursorInto(cursor: Cursor) {
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val flagsIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0 && !cursor.isNull(idIdx)) cursor.getString(idIdx) else null
                    if (docId.isNullOrBlank()) continue
                    val rawName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                    val name = rawName?.takeIf { it.isNotBlank() }
                        ?: docId.substringAfterLast('/').substringAfterLast(':').ifBlank { docId }
                    if (!showHiddenFiles && name.startsWith(".")) continue

                    val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                        cursor.getString(mimeIdx) ?: "application/octet-stream"
                    } else {
                        "application/octet-stream"
                    }
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                        mime.equals("vnd.android.document/directory", ignoreCase = true) ||
                        mime.equals("application/vnd.google-apps.folder", ignoreCase = true)
                    val size = if (isDir || sizeIdx < 0 || cursor.isNull(sizeIdx)) 0L else runCatching { cursor.getLong(sizeIdx) }.getOrDefault(0L)
                    val modified = if (modIdx < 0 || cursor.isNull(modIdx)) 0L else runCatching { cursor.getLong(modIdx) }.getOrDefault(0L)
                    val flags = if (flagsIdx < 0 || cursor.isNull(flagsIdx)) 0 else runCatching { cursor.getInt(flagsIdx) }.getOrDefault(0)
                    val writable = isDir ||
                        flags == 0 ||
                        (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ||
                        (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0 ||
                        (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
                    val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
                    val nodePath = pathFor(loc.volumeId, docId)
                    rawItems += VfsNode(
                        id = nodePath,
                        name = name,
                        path = nodePath,
                        parentPath = safPath,
                        volumeId = volumeId,
                        isDirectory = isDir,
                        sizeBytes = size,
                        lastModifiedMillis = modified,
                        mimeType = if (isDir) "vnd.android.document/directory" else mime,
                        extension = ext,
                        isHidden = name.startsWith("."),
                        isReadOnly = !writable
                    )
                }
            }

            // Layer 1: ContentResolver.query mit Unterstützung für EXTRA_LOADING (Google Drive / Cloud)
            var attempt = 0
            while (attempt < 20) {
                rawItems.clear()
                var stillLoading = false
                val querySucceeded = runCatching {
                    val activeProjection = if (attempt == 0) projection else null
                    context.contentResolver.query(childrenUri, activeProjection, null, null, null)?.use { cursor ->
                        stillLoading = cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                        readCursorInto(cursor)
                    } != null
                }.getOrDefault(false)

                if (rawItems.isNotEmpty() || (!stillLoading && querySucceeded && attempt >= 1)) {
                    break
                }
                if (!stillLoading && querySucceeded && rawItems.isEmpty() && attempt == 0) {
                    // Einmal mit null-Projection gegenprüfen, falls der Provider Spalten abgelehnt hat
                    attempt++
                    continue
                }
                if (stillLoading) {
                    delay(250L)
                    attempt++
                } else {
                    break
                }
            }

            // Layer 2: DocumentFile.fromTreeUri Fallback (falls der Provider nur Einzel-Abfragen erlaubt)
            if (rawItems.isEmpty()) {
                runCatching {
                    val targetUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
                    val dirDoc = DocumentFile.fromTreeUri(context, targetUri)
                        ?: DocumentFile.fromTreeUri(context, loc.treeUri)
                    dirDoc?.listFiles()?.forEach { doc ->
                        val docUri = doc.uri
                        val childDocId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull() ?: return@forEach
                        val name = doc.name?.takeIf { it.isNotBlank() }
                            ?: childDocId.substringAfterLast('/').substringAfterLast(':')
                        if (!showHiddenFiles && name.startsWith(".")) return@forEach
                        val isDir = doc.isDirectory
                        val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
                        val nodePath = pathFor(loc.volumeId, childDocId)
                        rawItems += VfsNode(
                            id = nodePath,
                            name = name,
                            path = nodePath,
                            parentPath = safPath,
                            volumeId = volumeId,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else doc.length(),
                            lastModifiedMillis = doc.lastModified(),
                            mimeType = if (isDir) "vnd.android.document/directory" else (doc.type ?: "application/octet-stream"),
                            extension = ext,
                            isHidden = name.startsWith("."),
                            isReadOnly = !doc.canWrite()
                        )
                    }
                }
            }

            // Layer 3: Direkter Dateisystem-Fallback für lokale ExternalStorage-URIs (primary:...)
            if (rawItems.isEmpty() && loc.treeUri.authority == "com.android.externalstorage.documents") {
                runCatching {
                    val relPath = loc.documentId.substringAfter(':', "")
                    val baseDir = Environment.getExternalStorageDirectory()
                    val localDir = if (relPath.isEmpty()) baseDir else File(baseDir, relPath)
                    if (localDir.exists() && localDir.isDirectory && localDir.canRead()) {
                        localDir.listFiles()?.forEach { child ->
                            if (!showHiddenFiles && child.name.startsWith(".")) return@forEach
                            val childDocId = if (relPath.isEmpty()) {
                                "primary:${child.name}"
                            } else {
                                "primary:$relPath/${child.name}"
                            }
                            val isDir = child.isDirectory
                            val ext = if (isDir) "" else child.extension.lowercase()
                            val nodePath = pathFor(loc.volumeId, childDocId)
                            rawItems += VfsNode(
                                id = nodePath,
                                name = child.name,
                                path = nodePath,
                                parentPath = safPath,
                                volumeId = volumeId,
                                isDirectory = isDir,
                                sizeBytes = if (isDir) 0L else child.length(),
                                lastModifiedMillis = child.lastModified(),
                                mimeType = if (isDir) "vnd.android.document/directory" else "application/octet-stream",
                                extension = ext,
                                isHidden = child.name.startsWith("."),
                                isReadOnly = !child.canWrite()
                            )
                        }
                    }
                }
            }

            // Favoriten- (Starred) und Tag-Metadaten aus Room anreichern
            if (rawItems.isEmpty()) return@withContext emptyList()
            val metaMap = runCatching {
                dao.getMetadataForPaths(rawItems.map { it.path }).associateBy { it.path }
            }.getOrDefault(emptyMap())

            rawItems.map { item ->
                val meta = metaMap[item.path]
                if (meta == null) {
                    item
                } else {
                    val tags = meta.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    item.copy(
                        isStarred = meta.isStarred,
                        isOfflinePinned = meta.isOfflinePinned,
                        tags = tags
                    )
                }
            }
        }

    /**
     * Liefert die direkten Unterordner eines SAF-Pfads für die linke Navigationsleiste.
     */
    suspend fun listSubdirectories(safPath: String, volumeId: String, showHiddenFiles: Boolean): List<VfsNode> =
        list(safPath, volumeId, showHiddenFiles).filter { it.isDirectory }.sortedBy { it.name.lowercase() }

    private fun queryDocumentDisplayName(docUri: Uri): String? = runCatching {
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
        }
    }.getOrNull()

    suspend fun buildBreadcrumbs(safPath: String, volumeId: String, rootTitle: String): List<BreadcrumbSegment> =
        withContext(Dispatchers.IO) {
            val loc = resolveLocation(safPath) ?: return@withContext listOf(
                BreadcrumbSegment(rootTitle, safPath, volumeId)
            )
            val conn = dao.getAllMountedConnections().firstOrNull { it.volumeId == loc.volumeId }
            val rootDocId = conn?.shareOrRemotePath?.takeIf { it.isNotBlank() }
                ?: DocumentsContract.getTreeDocumentId(loc.treeUri)
            val segments = mutableListOf(
                BreadcrumbSegment(rootTitle, pathFor(loc.volumeId, rootDocId), volumeId)
            )
            if (loc.documentId != rootDocId) {
                val suffix = loc.documentId.removePrefix(rootDocId).trimStart('/', ':')
                if (suffix.isNotEmpty() && loc.documentId.startsWith(rootDocId)) {
                    var running = rootDocId
                    val separator = if (loc.documentId.startsWith("$rootDocId/")) "/" else ":"
                    suffix.split('/').filter { it.isNotEmpty() }.forEachIndexed { idx, part ->
                        running = if (idx == 0 && separator == ":") "$running:$part" else "$running/$part"
                        segments += BreadcrumbSegment(part, pathFor(loc.volumeId, running), volumeId)
                    }
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
                    val displayTitle = queryDocumentDisplayName(docUri)
                        ?: loc.documentId.substringAfterLast('/').substringAfterLast(':')
                    segments += BreadcrumbSegment(
                        displayTitle.ifBlank { "Unterordner" },
                        safPath,
                        volumeId
                    )
                }
            }
            segments
        }

    suspend fun createDirectory(parentSafPath: String, folderName: String, volumeId: String): VfsNode =
        withContext(Dispatchers.IO) {
            val loc = resolveLocation(parentSafPath)
                ?: throw IllegalStateException("SAF-Zielordner nicht gefunden.")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
            ) ?: throw IllegalStateException("SAF-Ordner konnte nicht erstellt werden.")
            val newDocId = DocumentsContract.getDocumentId(createdUri)
            val nodePath = pathFor(loc.volumeId, newDocId)
            VfsNode(
                id = nodePath,
                name = folderName,
                path = nodePath,
                parentPath = parentSafPath,
                volumeId = volumeId,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMillis = System.currentTimeMillis(),
                mimeType = "vnd.android.document/directory",
                extension = ""
            )
        }

    suspend fun rename(node: VfsNode, newName: String): VfsNode = withContext(Dispatchers.IO) {
        val loc = resolveLocation(node.path)
            ?: throw IllegalStateException("SAF-Element nicht gefunden.")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
        val renamedUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
            ?: throw IllegalStateException("SAF-Element konnte nicht umbenannt werden.")
        val newDocId = DocumentsContract.getDocumentId(renamedUri)
        val newPath = pathFor(loc.volumeId, newDocId)
        val ext = if (node.isDirectory) "" else newName.substringAfterLast('.', "").lowercase()
        node.copy(id = newPath, name = newName, path = newPath, extension = ext)
    }

    suspend fun delete(node: VfsNode): Boolean = withContext(Dispatchers.IO) {
        val loc = resolveLocation(node.path) ?: return@withContext false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }.getOrDefault(false)
    }

    suspend fun materializeToCache(node: VfsNode): File = withContext(Dispatchers.IO) {
        val loc = resolveLocation(node.path)
            ?: throw IllegalStateException("SAF-Datei nicht verfügbar: ${node.name}")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
        val safeRel = loc.documentId.replace(':', '_').replace('\\', '_').replace('/', '_')
        val cached = File(context.cacheDir, "saf_cache/${loc.volumeId}/${safeRel}_${node.name}")
        if (cached.exists() && node.sizeBytes > 0L && cached.length() == node.sizeBytes &&
            (node.lastModifiedMillis <= 0L || cached.lastModified() == node.lastModifiedMillis)
        ) {
            return@withContext cached
        }
        cached.parentFile?.mkdirs()
        context.contentResolver.openInputStream(docUri)?.use { input ->
            FileOutputStream(cached).use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw IllegalStateException("SAF-Stream konnte nicht geöffnet werden: ${node.name}")
        if (node.lastModifiedMillis > 0L) cached.setLastModified(node.lastModifiedMillis)
        cached
    }

    suspend fun copyLocalFileToSaf(
        sourceFile: File,
        parentSafPath: String,
        mimeType: String,
        volumeId: String,
        onBytes: (Long) -> Unit = {}
    ): VfsNode = withContext(Dispatchers.IO) {
        val loc = resolveLocation(parentSafPath)
            ?: throw IllegalStateException("SAF-Zielordner nicht verfügbar.")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
        val createdUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            mimeType.ifBlank { "application/octet-stream" },
            sourceFile.name
        ) ?: throw IllegalStateException("Zieldatei konnte im SAF-Ordner nicht angelegt werden.")
        context.contentResolver.openOutputStream(createdUri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                val buf = ByteArray(64 * 1024)
                var read = input.read(buf)
                while (read >= 0) {
                    if (read > 0) {
                        output.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                    read = input.read(buf)
                }
            }
        } ?: throw IllegalStateException("SAF-Ausgabestream konnte nicht geöffnet werden.")
        val newDocId = DocumentsContract.getDocumentId(createdUri)
        val nodePath = pathFor(loc.volumeId, newDocId)
        VfsNode(
            id = nodePath,
            name = sourceFile.name,
            path = nodePath,
            parentPath = parentSafPath,
            volumeId = volumeId,
            isDirectory = false,
            sizeBytes = sourceFile.length(),
            lastModifiedMillis = System.currentTimeMillis(),
            mimeType = mimeType,
            extension = sourceFile.extension.lowercase()
        )
    }

    suspend fun writeFileContent(node: VfsNode, localFile: File): VfsNode = withContext(Dispatchers.IO) {
        val loc = resolveLocation(node.path)
            ?: throw IllegalStateException("SAF-Datei nicht verfügbar: ${node.name}")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
        context.contentResolver.openOutputStream(docUri, "wt")?.use { output ->
            FileInputStream(localFile).use { input -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("SAF-Datei konnte nicht zum Schreiben geöffnet werden.")
        val safeRel = loc.documentId.replace(':', '_').replace('\\', '_').replace('/', '_')
        val cached = File(context.cacheDir, "saf_cache/${loc.volumeId}/${safeRel}_${node.name}")
        if (cached.exists() && cached.absolutePath != localFile.absolutePath) {
            localFile.copyTo(cached, overwrite = true)
        }
        node.copy(sizeBytes = localFile.length(), lastModifiedMillis = System.currentTimeMillis())
    }

    suspend fun disconnect(volumeId: String) = withContext(Dispatchers.IO) {
        val dbId = when (volumeId) {
            "linux_files" -> LINUX_SAF_VOLUME_ID
            "google_drive" -> DRIVE_SAF_VOLUME_ID
            else -> volumeId
        }
        val conn = dao.getAllMountedConnections().firstOrNull { it.volumeId == dbId || it.volumeId == volumeId }
        if (conn != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(conn.hostOrUri), flags) }
            dao.deleteMountedConnection(conn.volumeId)
        }
        File(context.cacheDir, "saf_cache/$volumeId").deleteRecursively()
        File(context.cacheDir, "saf_cache/$dbId").deleteRecursively()
    }
}
