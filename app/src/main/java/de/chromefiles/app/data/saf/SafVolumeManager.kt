package de.chromefiles.app.data.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Auf ChromeOS werden darüber der echte **Linux-Container (Crostini)**, externe USB-/SD-Medien
 * und Drittanbieter-Cloud-DocumentProvider mit dauerhafter URI-Berechtigung (`takePersistableUriPermission`)
 * eingehängt.
 *
 * Pfad-Schema: `saf://<volumeId>` für den Wurzelknoten bzw. `saf://<volumeId>/<encodedDocumentId>`
 * für beliebige Unterordner und Dateien innerhalb des gewählten Baums.
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
        const val LINUX_SAF_VOLUME_ID = "linux_files_saf"
    }

    fun isSafPath(path: String): Boolean = path.startsWith(SAF_SCHEME)

    fun rootPath(volumeId: String): String = "$SAF_SCHEME$volumeId"

    fun pathFor(volumeId: String, documentId: String): String =
        "$SAF_SCHEME$volumeId/${Uri.encode(documentId)}"

    /**
     * Nimmt einen vom System-Ordner-Picker (`ACTION_OPEN_DOCUMENT_TREE`) gelieferten Baum-URI entgegen,
     * sichert die Lese-/Schreibberechtigung dauerhaft und legt den Mount-Eintrag in Room an.
     */
    suspend fun registerTreeUri(
        treeUri: Uri,
        asLinuxContainer: Boolean = false
    ): Result<VfsVolume> = withContext(Dispatchers.IO) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }

            val docFile = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Ungültiger SAF-Ordner-URI: $treeUri")
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rawTitle = docFile.name?.takeIf { it.isNotBlank() }
                ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Externer Ordner" }

            val volumeId = if (asLinuxContainer) {
                LINUX_SAF_VOLUME_ID
            } else {
                "saf_${UUID.randomUUID().toString().take(8)}"
            }
            val title = if (asLinuxContainer) "Linux-Dateien" else rawTitle
            val protocol = if (asLinuxContainer) PROTOCOL_SAF_LINUX else PROTOCOL_SAF
            val path = pathFor(volumeId, rootDocId)

            dao.upsertMountedConnection(
                MountedConnectionEntity(
                    volumeId = volumeId,
                    title = title,
                    protocol = protocol,
                    hostOrUri = treeUri.toString(),
                    shareOrRemotePath = rootDocId,
                    username = "",
                    localMountPath = path
                )
            )
            toVfsVolume(
                MountedConnectionEntity(
                    volumeId = volumeId,
                    title = title,
                    protocol = protocol,
                    hostOrUri = treeUri.toString(),
                    shareOrRemotePath = rootDocId,
                    username = "",
                    localMountPath = path
                )
            )
        }
    }

    fun toVfsVolume(entity: MountedConnectionEntity): VfsVolume {
        val isLinux = entity.protocol == PROTOCOL_SAF_LINUX || entity.volumeId == LINUX_SAF_VOLUME_ID
        val authority = runCatching { Uri.parse(entity.hostOrUri).authority.orEmpty() }.getOrDefault("")
        return VfsVolume(
            id = if (isLinux) "linux_files" else entity.volumeId,
            title = entity.title,
            subtitle = if (isLinux) {
                "Crostini SAF (${authority.ifBlank { "DocumentProvider" }})"
            } else {
                "SAF • ${entity.shareOrRemotePath}"
            },
            type = if (isLinux) VfsVolumeType.LINUX_CROSTINI else VfsVolumeType.REMOVABLE_MEDIA,
            rootPath = entity.localMountPath,
            isEjectable = true,
            isExpandable = false,
            isReadOnly = false
        )
    }

    suspend fun resolveLocation(safPath: String): SafLocation? = withContext(Dispatchers.IO) {
        if (!isSafPath(safPath)) return@withContext null
        val rest = safPath.removePrefix(SAF_SCHEME)
        val rawVolumeId = rest.substringBefore('/')
        val dbVolumeId = if (rawVolumeId == "linux_files") LINUX_SAF_VOLUME_ID else rawVolumeId
        val conn = dao.getAllMountedConnections().firstOrNull {
            it.volumeId == dbVolumeId || it.volumeId == rawVolumeId
        } ?: return@withContext null
        val treeUri = Uri.parse(conn.hostOrUri)
        val encodedDocId = rest.substringAfter('/', "")
        val docId = if (encodedDocId.isNotEmpty()) {
            Uri.decode(encodedDocId)
        } else {
            conn.shareOrRemotePath.ifBlank { DocumentsContract.getTreeDocumentId(treeUri) }
        }
        SafLocation(volumeId = rawVolumeId, treeUri = treeUri, documentId = docId)
    }

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
            val result = mutableListOf<VfsNode>()
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val flagsIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)

                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idIdx) ?: continue
                        val name = cursor.getString(nameIdx) ?: docId.substringAfterLast('/')
                        if (!showHiddenFiles && name.startsWith(".")) continue
                        val mime = cursor.getString(mimeIdx) ?: "application/octet-stream"
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (isDir || cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                        val modified = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx)
                        val flags = if (cursor.isNull(flagsIdx)) 0 else cursor.getInt(flagsIdx)
                        val writable = isDir ||
                            (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ||
                            (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0 ||
                            (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
                        val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
                        val nodePath = pathFor(loc.volumeId, docId)
                        result += VfsNode(
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
            }
            result
        }

    suspend fun buildBreadcrumbs(safPath: String, volumeId: String, rootTitle: String): List<BreadcrumbSegment> =
        withContext(Dispatchers.IO) {
            val loc = resolveLocation(safPath) ?: return@withContext listOf(
                BreadcrumbSegment(rootTitle, safPath, volumeId)
            )
            val rootDocId = DocumentsContract.getTreeDocumentId(loc.treeUri)
            val segments = mutableListOf(
                BreadcrumbSegment(rootTitle, pathFor(loc.volumeId, rootDocId), volumeId)
            )
            if (loc.documentId != rootDocId) {
                val suffix = loc.documentId.removePrefix(rootDocId).trimStart('/', ':')
                if (suffix.isNotEmpty()) {
                    var running = rootDocId
                    val separator = if (loc.documentId.startsWith("$rootDocId/")) "/" else ":"
                    suffix.split('/').filter { it.isNotEmpty() }.forEachIndexed { idx, part ->
                        running = if (idx == 0 && separator == ":") "$running:$part" else "$running/$part"
                        segments += BreadcrumbSegment(part, pathFor(loc.volumeId, running), volumeId)
                    }
                } else {
                    segments += BreadcrumbSegment(
                        loc.documentId.substringAfterLast('/').substringAfterLast(':'),
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
        val safeRel = loc.documentId.replace(':', '_').replace('\\', '_')
        val cached = File(context.cacheDir, "saf_cache/${loc.volumeId}/$safeRel")
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
        val safeRel = loc.documentId.replace(':', '_').replace('\\', '_')
        val cached = File(context.cacheDir, "saf_cache/${loc.volumeId}/$safeRel")
        if (cached.exists() && cached.absolutePath != localFile.absolutePath) {
            localFile.copyTo(cached, overwrite = true)
        }
        node.copy(sizeBytes = localFile.length(), lastModifiedMillis = System.currentTimeMillis())
    }

    suspend fun disconnect(volumeId: String) = withContext(Dispatchers.IO) {
        val dbId = if (volumeId == "linux_files") LINUX_SAF_VOLUME_ID else volumeId
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
