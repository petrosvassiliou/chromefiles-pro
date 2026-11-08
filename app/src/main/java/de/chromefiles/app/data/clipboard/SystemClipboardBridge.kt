package de.chromefiles.app.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import de.chromefiles.app.model.VfsNode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brücke zur System-Zwischenablage: Kopierte Dateien werden als content://-URIs abgelegt,
 * damit sie in anderen ChromeOS-/Android-Apps eingefügt werden können. Umgekehrt können
 * URIs aus anderen Apps (z. B. der ChromeOS Files App) hier eingefügt werden.
 */
@Singleton
class SystemClipboardBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val clipboard: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun writeFiles(nodes: List<VfsNode>) {
        val uris = nodes.filterNot { it.isDirectory }.mapNotNull { node ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(node.path))
            }.getOrNull()
        }
        val clip = if (uris.isEmpty()) {
            // Nur Ordner: Pfade als Text, damit die eigene Markierung trotzdem gesetzt ist.
            ClipData.newPlainText(CLIP_LABEL, nodes.joinToString("\n") { it.path })
        } else {
            ClipData.newUri(context.contentResolver, CLIP_LABEL, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    /** true, solange die zuletzt von ChromeFiles Pro kopierten Dateien noch in der Zwischenablage liegen. */
    fun isOwnClipCurrent(): Boolean =
        clipboard.primaryClipDescription?.label?.toString() == CLIP_LABEL

    /** URIs, die eine andere App in die Zwischenablage gelegt hat. */
    fun readExternalUris(): List<Uri> {
        if (isOwnClipCurrent()) return emptyList()
        val clip = clipboard.primaryClip ?: return emptyList()
        val description: ClipDescription = clip.description
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && (0 until clip.itemCount).all { clip.getItemAt(it).uri == null }) {
            return emptyList()
        }
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { it.scheme == "content" }
    }

    companion object {
        const val CLIP_LABEL = "ChromeFilesPro-Dateien"
    }
}
