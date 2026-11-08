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

    private val displayNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val displayNameByDocId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val parentPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val parentDocIdMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val treeUriByVolume = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    private fun extractDocId(safPath: String): String =
        Uri.decode(safPath.removePrefix(SAF_SCHEME).substringAfter('/', ""))

    private fun cacheDisplayName(safPath: String, docId: String, name: String) {
        if (isCrypticSafId(name)) return
        val clean = name.trim()
        displayNameCache[safPath] = clean
        if (docId.isNotBlank()) {
            displayNameByDocId[docId] = clean
        }
    }

    fun isCrypticSafId(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val v = value.trim()
        return v.startsWith("acc=") ||
            v.contains("doc=encoded=") ||
            v.contains(";doc=") ||
            v.startsWith("encoded=") ||
            v.startsWith("saf://")
    }

    /**
     * Merkt sich den echten Anzeigenamen und Elternpfad eines SAF-/Google-Drive-Ordners
     * sofort beim Anklicken oder Auflisten, damit Breadcrumbs und Tabs immer den Klarnamen zeigen.
     */
    fun rememberFolderNode(path: String, name: String, parentPath: String? = null) {
        if (!isSafPath(path)) return
        val docId = extractDocId(path)
        cacheDisplayName(path, docId, name)
        if (!parentPath.isNullOrBlank() && isSafPath(parentPath) && parentPath != path) {
            parentPathCache[path] = parentPath
            val parentDocId = extractDocId(parentPath)
            if (docId.isNotBlank() && parentDocId.isNotBlank() && docId != parentDocId) {
                parentDocIdMap[docId] = parentDocId
            }
        }
    }

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

            val isDriveUri = asGoogleDrive || treeUri.authority?.contains("com.google.android.apps.docs") == true
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val resolvedName = queryDocumentDisplayNameWithRetry(docUri, treeUri)
            val fallbackLastPart = rootDocId.substringAfterLast(':').substringAfterLast('/')
                .takeUnless { isCrypticSafId(it) }

            val rawTitle = resolvedName
                ?: fallbackLastPart
                ?: when {
                    isDriveUri -> "Meine Ablage"
                    asLinuxContainer -> "Linux-Dateien"
                    else -> "Eingebundener Ordner"
                }

            val volumeId = when {
                isDriveUri -> DRIVE_SAF_VOLUME_ID
                asLinuxContainer -> LINUX_SAF_VOLUME_ID
                else -> "saf_${UUID.randomUUID().toString().take(8)}"
            }
            val title = when {
                isDriveUri -> rawTitle
                asLinuxContainer -> "Linux-Dateien"
                else -> rawTitle
            }
            val protocol = when {
                isDriveUri -> PROTOCOL_SAF_DRIVE
                asLinuxContainer -> PROTOCOL_SAF_LINUX
                else -> PROTOCOL_SAF
            }
            val path = pathFor(volumeId, rootDocId)
            treeUriByVolume[volumeId] = treeUri
            if (!isCrypticSafId(rawTitle)) {
                cacheDisplayName(path, rootDocId, rawTitle)
            }

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
        val isDrive = entity.protocol == PROTOCOL_SAF_DRIVE ||
            entity.volumeId == DRIVE_SAF_VOLUME_ID ||
            entity.hostOrUri.contains("com.google.android.apps.docs")
        val parsedTreeUri = runCatching { Uri.parse(entity.hostOrUri) }.getOrNull()
        if (parsedTreeUri != null) {
            treeUriByVolume[entity.volumeId] = parsedTreeUri
            if (isDrive) treeUriByVolume["google_drive"] = parsedTreeUri
            if (isLinux) treeUriByVolume["linux_files"] = parsedTreeUri
        }
        val authority = parsedTreeUri?.authority.orEmpty()
        val rootDocId = entity.shareOrRemotePath.ifBlank { extractDocId(entity.localMountPath) }

        val cleanedEntityTitle = entity.title
            .removePrefix("Google Drive (")
            .removeSuffix(")")
            .trim()
            .takeUnless { isCrypticSafId(it) }
            ?: displayNameCache[entity.localMountPath]?.takeUnless { isCrypticSafId(it) }
            ?: displayNameByDocId[rootDocId]?.takeUnless { isCrypticSafId(it) }
            ?: if (parsedTreeUri != null && rootDocId.isNotBlank()) {
                runCatching {
                    queryDocumentDisplayNameImmediate(
                        DocumentsContract.buildDocumentUriUsingTree(parsedTreeUri, rootDocId)
                    )
                }.getOrNull()
            } else {
                null
            }

        if (cleanedEntityTitle != null && !isCrypticSafId(cleanedEntityTitle)) {
            cacheDisplayName(entity.localMountPath, rootDocId, cleanedEntityTitle)
        }

        val effectiveFolderLabel = cleanedEntityTitle ?: when {
            isDrive -> "Meine Ablage"
            isLinux -> "Linux-Dateien"
            else -> "Eingebundener Ordner"
        }

        return VfsVolume(
            id = when {
                isDrive -> "google_drive"
                isLinux -> "linux_files"
                else -> entity.volumeId
            },
            title = when {
                isDrive -> "Google Drive"
                isLinux -> "Linux-Dateien"
                else -> effectiveFolderLabel
            },
            subtitle = when {
                isDrive -> if (effectiveFolderLabel.equals("Google Drive", ignoreCase = true)) {
                    "Verbunden • Meine Ablage"
                } else {
                    "Verbunden • $effectiveFolderLabel"
                }
                isLinux -> "Crostini SAF (${authority.ifBlank { "DocumentProvider" }})"
                else -> "Eingebundener Ordner"
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
            (rawVolumeId == "google_drive" && (it.protocol == PROTOCOL_SAF_DRIVE || it.hostOrUri.contains("com.google.android.apps.docs"))) ||
                (rawVolumeId == "linux_files" && it.protocol == PROTOCOL_SAF_LINUX)
        } ?: return@withContext null

        val treeUri = Uri.parse(conn.hostOrUri)
        treeUriByVolume[conn.volumeId] = treeUri
        treeUriByVolume[rawVolumeId] = treeUri
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
            val canonicalCurrentPath = pathFor(loc.volumeId, loc.documentId)

            // Eigenen Ordnernamen auflösen und cachen, falls noch nicht bekannt (wichtig für Google Drive Breadcrumbs!)
            if (displayNameByDocId[loc.documentId].let { isCrypticSafId(it) } ||
                displayNameCache[canonicalCurrentPath].let { isCrypticSafId(it) }
            ) {
                val selfDocUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, loc.documentId)
                }.getOrNull()
                val resolvedSelfName = if (selfDocUri != null) {
                    queryDocumentDisplayNameWithRetry(selfDocUri, loc.treeUri)
                } else {
                    null
                }
                if (!resolvedSelfName.isNullOrBlank() && !isCrypticSafId(resolvedSelfName)) {
                    cacheDisplayName(safPath, loc.documentId, resolvedSelfName)
                    cacheDisplayName(canonicalCurrentPath, loc.documentId, resolvedSelfName)
                    // Falls der Mount-Eintrag in Room noch einen kryptischen Namen hatte, direkt aktualisieren
                    runCatching {
                        val conn = dao.getAllMountedConnections().firstOrNull { it.volumeId == loc.volumeId }
                        if (conn != null && (conn.localMountPath == canonicalCurrentPath || conn.shareOrRemotePath == loc.documentId) && isCrypticSafId(conn.title)) {
                            dao.upsertMountedConnection(conn.copy(title = resolvedSelfName))
                        }
                    }
                }
            }

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
                    val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                        cursor.getString(mimeIdx) ?: "application/octet-stream"
                    } else {
                        "application/octet-stream"
                    }
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                        mime.equals("vnd.android.document/directory", ignoreCase = true) ||
                        mime.equals("application/vnd.google-apps.folder", ignoreCase = true)
                    val fallbackName = docId.substringAfterLast('/').substringAfterLast(':')
                        .takeUnless { isCrypticSafId(it) }
                        ?: if (isDir) "Ordner" else "Datei"
                    val name = rawName?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
                        ?: queryDocumentDisplayNameImmediate(
                            DocumentsContract.buildDocumentUriUsingTree(loc.treeUri, docId)
                        )
                        ?: fallbackName
                    if (!showHiddenFiles && name.startsWith(".")) continue

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
                    cacheDisplayName(nodePath, docId, name)
                    if (nodePath != canonicalCurrentPath) {
                        parentPathCache[nodePath] = canonicalCurrentPath
                        if (docId != loc.documentId) {
                            parentDocIdMap[docId] = loc.documentId
                        }
                    }
                    rawItems += VfsNode(
                        id = nodePath,
                        name = name,
                        path = nodePath,
                        parentPath = canonicalCurrentPath,
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
                        val isDir = doc.isDirectory
                        val name = doc.name?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
                            ?: queryDocumentDisplayNameImmediate(docUri)
                            ?: childDocId.substringAfterLast('/').substringAfterLast(':')
                                .takeUnless { isCrypticSafId(it) }
                            ?: if (isDir) "Ordner" else "Datei"
                        if (!showHiddenFiles && name.startsWith(".")) return@forEach
                        val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
                        val nodePath = pathFor(loc.volumeId, childDocId)
                        cacheDisplayName(nodePath, childDocId, name)
                        if (nodePath != canonicalCurrentPath) {
                            parentPathCache[nodePath] = canonicalCurrentPath
                            if (childDocId != loc.documentId) {
                                parentDocIdMap[childDocId] = loc.documentId
                            }
                        }
                        rawItems += VfsNode(
                            id = nodePath,
                            name = name,
                            path = nodePath,
                            parentPath = canonicalCurrentPath,
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
                            cacheDisplayName(nodePath, childDocId, child.name)
                            parentPathCache[nodePath] = canonicalCurrentPath
                            parentDocIdMap[childDocId] = loc.documentId
                            rawItems += VfsNode(
                                id = nodePath,
                                name = child.name,
                                path = nodePath,
                                parentPath = canonicalCurrentPath,
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

    private fun queryDocumentDisplayNameImmediate(docUri: Uri): String? {
        for (proj in listOf(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null)) {
            val found = runCatching {
                context.contentResolver.query(docUri, proj, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                        c.getString(idx)?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (found != null) return found
        }
        return runCatching {
            DocumentFile.fromSingleUri(context, docUri)?.name?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
        }.getOrNull()
    }

    private suspend fun queryDocumentDisplayNameWithRetry(docUri: Uri, treeUri: Uri? = null): String? {
        var attempt = 0
        while (attempt < 10) {
            var stillLoading = false
            for (proj in listOf(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null)) {
                val name = runCatching {
                    context.contentResolver.query(docUri, proj, null, null, null)?.use { c ->
                        stillLoading = stillLoading || (c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true)
                        val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                            c.getString(idx)?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                if (name != null) return name
            }
            val fromDocFile = runCatching {
                (DocumentFile.fromTreeUri(context, docUri)
                    ?: treeUri?.let { DocumentFile.fromTreeUri(context, it) }
                    ?: DocumentFile.fromSingleUri(context, docUri))
                    ?.name
                    ?.takeIf { it.isNotBlank() && !isCrypticSafId(it) }
            }.getOrNull()
            if (fromDocFile != null) return fromDocFile

            if (stillLoading) {
                delay(200L)
                attempt++
            } else {
                break
            }
        }
        return null
    }

    /**
     * Erzeugt die Breadcrumb-Segmente für SAF- und Google-Drive-Ordner mit echten Klarnamen
     * (niemals kryptische `acc=2;doc=encoded=...`-IDs).
     */
    fun buildBreadcrumbsSync(
        safPath: String,
        volumeId: String,
        rootTitle: String,
        rootPath: String
    ): List<BreadcrumbSegment> {
        val segments = mutableListOf<BreadcrumbSegment>()
        val effectiveRootPath = rootPath.takeIf { isSafPath(it) } ?: safPath
        val rootBody = effectiveRootPath.removePrefix(SAF_SCHEME)
        val curBody = safPath.removePrefix(SAF_SCHEME)
        val volPrefix = curBody.substringBefore('/')
        val rootDocId = Uri.decode(rootBody.substringAfter('/', ""))
        val curDocId = Uri.decode(curBody.substringAfter('/', ""))
        val treeUri = treeUriByVolume[volPrefix] ?: treeUriByVolume[volumeId]

        fun resolveNameFor(stepPath: String, docId: String): String? {
            displayNameCache[stepPath]?.takeUnless { isCrypticSafId(it) }?.let { return it }
            if (docId.isNotBlank()) {
                displayNameByDocId[docId]?.takeUnless { isCrypticSafId(it) }?.let { return it }
                if (treeUri != null) {
                    val queried = runCatching {
                        queryDocumentDisplayNameImmediate(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        )
                    }.getOrNull()
                    if (queried != null && !isCrypticSafId(queried)) {
                        cacheDisplayName(stepPath, docId, queried)
                        return queried
                    }
                }
            }
            return null
        }

        segments += BreadcrumbSegment(rootTitle, effectiveRootPath, volumeId)

        // Falls der eingebundene Wurzelordner selbst ein konkreter Unterordner in Google Drive ist
        // (z. B. "Impfung" oder "Dokumente"), zeigen wir seinen echten Ordnernamen bereits als Segment an.
        val rootFolderDisplayName = resolveNameFor(effectiveRootPath, rootDocId)
            ?.takeIf { !it.equals(rootTitle, ignoreCase = true) }

        if (safPath == effectiveRootPath || curDocId == rootDocId) {
            if (rootFolderDisplayName != null) {
                segments += BreadcrumbSegment(rootFolderDisplayName, effectiveRootPath, volumeId)
            }
            return segments
        }

        if (rootFolderDisplayName != null) {
            segments += BreadcrumbSegment(rootFolderDisplayName, effectiveRootPath, volumeId)
        }

        // Fall 1: Hierarchische Document-IDs (z. B. primary:Download/Unterordner oder Crostini)
        if (rootDocId.isNotEmpty() && curDocId.startsWith(rootDocId) && curDocId != rootDocId) {
            val suffix = curDocId.removePrefix(rootDocId).trimStart('/', ':')
            val sep = if (curDocId.startsWith("$rootDocId/")) "/" else ":"
            var running = rootDocId
            suffix.split('/').filter { it.isNotEmpty() }.forEachIndexed { idx, part ->
                running = if (idx == 0 && sep == ":") "$running:$part" else "$running/$part"
                val stepPath = "$SAF_SCHEME$volPrefix/${Uri.encode(running)}"
                val cleanPart = resolveNameFor(stepPath, running)
                    ?: part.takeUnless { isCrypticSafId(it) }
                    ?: "Ordner"
                segments += BreadcrumbSegment(cleanPart, stepPath, volumeId)
            }
            return segments
        }

        // Fall 2: Opake Cloud-Document-IDs (Google Drive `acc=2;doc=encoded=...`):
        // Elternkette über parentDocIdMap / parentPathCache vom aktuellen Ordner bis zum Wurzelordner rekonstruieren.
        val docChain = mutableListOf<String>()
        var cursorDoc: String? = curDocId
        val visitedDocs = mutableSetOf<String>()
        while (!cursorDoc.isNullOrBlank() && cursorDoc != rootDocId && visitedDocs.add(cursorDoc)) {
            docChain.add(0, cursorDoc)
            cursorDoc = parentDocIdMap[cursorDoc]
        }

        for (stepDocId in docChain) {
            val stepPath = "$SAF_SCHEME$volPrefix/${Uri.encode(stepDocId)}"
            val label = resolveNameFor(stepPath, stepDocId)
                ?: stepDocId.substringAfterLast('/').substringAfterLast(':').takeUnless { isCrypticSafId(it) }
                ?: "Meine Ablage"
            if (segments.lastOrNull()?.path != stepPath && segments.lastOrNull()?.title != label) {
                segments += BreadcrumbSegment(label, stepPath, volumeId)
            }
        }
        return segments
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
