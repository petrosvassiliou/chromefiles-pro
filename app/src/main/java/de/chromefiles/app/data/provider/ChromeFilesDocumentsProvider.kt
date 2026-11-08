package de.chromefiles.app.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Stellt die Dateisystem-Wurzeln von ChromeFiles Pro („Meine Dateien“ & „Linux-Dateien“)
 * für das Android-/ChromeOS-Storage-Access-Framework (SAF) bereit, sodass andere Apps
 * über den System-Dateidialog direkt auf Dateien in ChromeFiles Pro zugreifen können.
 */
class ChromeFilesDocumentsProvider : DocumentsProvider() {

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean = true

    private fun baseSandboxRoot(): File {
        val ctx = context ?: throw IllegalStateException("Context nicht verfügbar")
        return File(ctx.filesDir, "chromeos_vfs").apply { mkdirs() }
    }

    private fun myFilesDir(): File = File(baseSandboxRoot(), "MyFiles").apply { mkdirs() }
    private fun linuxFilesDir(): File = File(baseSandboxRoot(), "LinuxFiles").apply { mkdirs() }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultRootProjection)
        val myFiles = myFilesDir()
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID_MY_FILES)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(Root.COLUMN_ICON, android.R.drawable.sym_def_app_icon)
            add(Root.COLUMN_TITLE, "ChromeFiles Pro – Meine Dateien")
            add(Root.COLUMN_SUMMARY, "Lokale Googlebook-Dateien")
            add(Root.COLUMN_DOCUMENT_ID, myFiles.absolutePath)
            add(Root.COLUMN_AVAILABLE_BYTES, myFiles.usableSpace)
        }

        val linuxFiles = linuxFilesDir()
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID_LINUX_FILES)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(Root.COLUMN_ICON, android.R.drawable.sym_def_app_icon)
            add(Root.COLUMN_TITLE, "ChromeFiles Pro – Linux-Dateien")
            add(Root.COLUMN_SUMMARY, "Crostini Linux-Container")
            add(Root.COLUMN_DOCUMENT_ID, linuxFiles.absolutePath)
            add(Root.COLUMN_AVAILABLE_BYTES, linuxFiles.usableSpace)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        includeFile(result, fileForDocId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        val parent = fileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { child ->
            includeFile(result, child)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileForDocId(parentDocumentId)
        val target = File(parent, displayName)
        if (Document.MIME_TYPE_DIR == mimeType) {
            if (!target.mkdirs() && !target.exists()) {
                throw FileNotFoundException("Ordner konnte nicht erstellt werden: $displayName")
            }
        } else {
            if (!target.createNewFile() && !target.exists()) {
                throw FileNotFoundException("Datei konnte nicht erstellt werden: $displayName")
            }
        }
        return target.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocId(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Dokument konnte nicht gelöscht werden: $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = fileForDocId(documentId)
        val target = File(source.parentFile, displayName)
        if (!source.renameTo(target)) {
            throw FileNotFoundException("Umbenennen fehlgeschlagen: $displayName")
        }
        return target.absolutePath
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileForDocId(parentDocumentId).canonicalPath }.getOrNull() ?: return false
        val child = runCatching { fileForDocId(documentId).canonicalPath }.getOrNull() ?: return false
        return child.startsWith(parent)
    }

    private fun fileForDocId(documentId: String): File {
        val file = File(documentId)
        if (!file.exists()) throw FileNotFoundException("Datei nicht gefunden: $documentId")
        return file
    }

    private fun includeFile(result: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val mimeType = if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            val ext = file.extension.lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
        }
    }

    companion object {
        const val ROOT_ID_MY_FILES = "chromefiles_my_files"
        const val ROOT_ID_LINUX_FILES = "chromefiles_linux_files"
    }
}
