package de.chromefiles.app.ui.dnd

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.DragAndDropPermissions
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.viewmodel.ChromeFilesViewModel
import de.chromefiles.app.ui.viewmodel.DropDestination
import java.io.File

/** Lokaler Zustand eines Drags innerhalb von ChromeFiles Pro (nur in derselben Activity sichtbar). */
class InternalDragPayload(val nodes: List<VfsNode>)

sealed interface DropPayload {
    /** Elemente aus ChromeFiles Pro selbst (beliebiger Tab / Pane). */
    data class Internal(val nodes: List<VfsNode>) : DropPayload

    /** content://-URIs aus einer anderen App, z. B. der ChromeOS Files App. */
    class External(val uris: List<Uri>, val permissions: DragAndDropPermissions?) : DropPayload
}

private const val DRAG_LABEL = "ChromeFilesPro-Drag"

/**
 * Baut die Drag-Daten: content://-URIs (FileProvider) für andere Apps + lokaler Payload für interne Ziele.
 */
fun buildDragTransferData(context: Context, nodes: List<VfsNode>): DragAndDropTransferData {
    val uris = nodes.filterNot { it.isDirectory }.mapNotNull { node ->
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(node.path))
        }.getOrNull()
    }
    val clip = if (uris.isEmpty()) {
        ClipData.newPlainText(DRAG_LABEL, nodes.joinToString("\n") { it.name })
    } else {
        ClipData.newUri(context.contentResolver, DRAG_LABEL, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }
    return DragAndDropTransferData(
        clipData = clip,
        localState = InternalDragPayload(nodes),
        flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
    )
}

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Macht ein Element zum Drop-Ziel (Ordnerzeile, Seitenleiste, Breadcrumb, Pane-Hintergrund)
 * und hebt es beim Darüberziehen hervor.
 */
@Composable
fun Modifier.fileDropTarget(
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(10.dp),
    onDrop: (DropPayload) -> Boolean
): Modifier {
    val context = LocalContext.current
    var hovering by remember { mutableStateOf(false) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentEnabled by rememberUpdatedState(enabled)

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                if (!currentEnabled) return false
                val dragEvent = event.toAndroidDragEvent()
                val local = dragEvent.localState as? InternalDragPayload
                val payload: DropPayload = if (local != null) {
                    DropPayload.Internal(local.nodes)
                } else {
                    val clip = dragEvent.clipData ?: return false
                    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    if (uris.isEmpty()) return false
                    DropPayload.External(uris, context.findActivity()?.requestDragAndDropPermissions(dragEvent))
                }
                return currentOnDrop(payload)
            }

            override fun onEntered(event: DragAndDropEvent) {
                hovering = currentEnabled
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    return this
        .dragAndDropTarget(shouldStartDragAndDrop = { currentEnabled }, target = target)
        .then(
            if (hovering) {
                Modifier
                    .border(2.dp, colors.primary, shape)
                    .background(colors.primary.copy(alpha = 0.12f), shape)
            } else {
                Modifier
            }
        )
}

/** Gemeinsame Ablage-Logik für alle Drop-Ziele. */
fun ChromeFilesViewModel.handleDrop(payload: DropPayload, destination: DropDestination): Boolean =
    when (payload) {
        is DropPayload.Internal -> dropNodes(payload.nodes, destination)
        is DropPayload.External -> {
            importExternal(payload.uris, destination) { payload.permissions?.release() }
            true
        }
    }
