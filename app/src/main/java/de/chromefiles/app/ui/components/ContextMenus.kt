package de.chromefiles.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.chromefiles.app.data.archive.ArchiveFormat
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_DRIVE
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.viewmodel.ChromeFilesViewModel
import de.chromefiles.app.ui.viewmodel.FileViewMode
import de.chromefiles.app.ui.viewmodel.PaneState

/**
 * Rechtsklick-Kontextmenü wie in der ChromeOS Files App – für Elemente ([target] != null)
 * oder die freie Fläche des Ordners ([target] == null). Wird an der Mausposition verankert.
 */
@Composable
fun FileContextMenu(
    expanded: Boolean,
    pane: PaneState,
    target: VfsNode?,
    viewModel: ChromeFilesViewModel,
    showHiddenFiles: Boolean,
    hasClipboard: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset.Zero
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        fun act(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        val paneId = pane.paneId
        if (target != null) {
            NodeMenuItems(pane, target, viewModel, hasClipboard, ::act)
        } else {
            BackgroundMenuItems(pane, paneId, viewModel, showHiddenFiles, hasClipboard, ::act)
        }
    }
}

@Composable
private fun NodeMenuItems(
    pane: PaneState,
    target: VfsNode,
    vm: ChromeFilesViewModel,
    hasClipboard: Boolean,
    act: (() -> Unit) -> () -> Unit
) {
    val paneId = pane.paneId
    val selection = pane.selectedNodes.ifEmpty { listOf(target) }
    val multi = selection.size > 1
    val editable = !pane.isTrash && selection.none { it.isReadOnly }

    DropdownMenuItem(
        text = {
            Text(
                text = if (multi) "${selection.size} Elemente ausgewählt" else target.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        },
        enabled = false,
        onClick = {}
    )
    HorizontalDivider()

    if (pane.isTrash) {
        MenuEntry("Wiederherstellen", Icons.Outlined.RestoreFromTrash, onClick = act { vm.restoreSelectionFromTrash(paneId) })
        MenuEntry("Endgültig löschen", Icons.Outlined.DeleteForever, "Umschalt + Entf", onClick = act { vm.requestPermanentDelete(paneId) })
        HorizontalDivider()
        MenuEntry("Datei-Info", Icons.Outlined.Info, onClick = act { vm.showDetailsFor(paneId) })
        return
    }

    MenuEntry(
        text = "Öffnen",
        icon = Icons.Outlined.FolderOpen,
        shortcut = "Enter",
        onClick = act { vm.openSelection(paneId) }
    )
    if (!target.isDirectory && !multi) {
        MenuEntry("QuickView", Icons.Outlined.Visibility, "Leertaste", onClick = act { vm.openQuickView(paneId) })
        MenuEntry("Öffnen mit …", Icons.AutoMirrored.Outlined.OpenInNew, onClick = act { vm.openWith(target) })
    }
    if (target.isDirectory && !multi) {
        MenuEntry("In neuem Tab öffnen", Icons.Outlined.Tab, onClick = act { vm.openInNewTab(paneId, target) })
    }
    val allStarred = selection.all { it.isStarred }
    MenuEntry(
        if (allStarred) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
        if (allStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
        onClick = act { vm.toggleStarredSelection(paneId) }
    )
    if (target.isArchive && !multi) {
        HorizontalDivider()
        MenuEntry(
            "Hier entpacken",
            Icons.Outlined.Unarchive,
            enabled = pane.isWritableLocation,
            onClick = act { vm.extractArchive(paneId, target) }
        )
        MenuEntry(
            "Als Laufwerk einhängen",
            Icons.Outlined.FolderOpen,
            onClick = act { vm.mountArchive(paneId, target) }
        )
    }
    HorizontalDivider()
    MenuEntry("Ausschneiden", Icons.Outlined.ContentCut, "Strg + X", enabled = editable, onClick = act { vm.copySelection(paneId, isCut = true) })
    MenuEntry("Kopieren", Icons.Outlined.ContentCopy, "Strg + C", onClick = act { vm.copySelection(paneId, isCut = false) })
    if (target.isDirectory && !multi) {
        MenuEntry(
            "In Ordner einfügen",
            Icons.Outlined.ContentPaste,
            enabled = hasClipboard && !target.isReadOnly,
            onClick = act { vm.paste(paneId, intoFolder = target) }
        )
    }
    HorizontalDivider()
    MenuEntry(
        if (multi) "Mehrfach umbenennen …" else "Umbenennen",
        Icons.Outlined.DriveFileRenameOutline,
        "Strg + Enter",
        enabled = editable,
        onClick = act { vm.startRename(paneId) }
    )
    MenuEntry(
        "Komprimieren … (ZIP / RAR / 7Z / TAR.GZ)",
        Icons.Outlined.Archive,
        enabled = pane.isWritableLocation,
        onClick = act { vm.showCompressDialog(paneId) }
    )
    MenuEntry(
        if (multi) "Als .zip komprimieren" else "ZIP-Archiv erstellen (.zip)",
        Icons.Outlined.Archive,
        enabled = pane.isWritableLocation,
        onClick = act { vm.compressSelection(paneId, ArchiveFormat.ZIP) }
    )
    MenuEntry(
        if (multi) "Als .rar komprimieren" else "RAR-Archiv erstellen (.rar)",
        Icons.Outlined.Archive,
        enabled = pane.isWritableLocation,
        onClick = act { vm.compressSelection(paneId, ArchiveFormat.RAR) }
    )
    MenuEntry(
        "7z-Archiv erstellen (.7z)",
        Icons.Outlined.Archive,
        enabled = pane.isWritableLocation,
        onClick = act { vm.compressSelection(paneId, ArchiveFormat.SEVEN_Z) }
    )
    MenuEntry(
        "TAR.GZ-Archiv erstellen (.tar.gz)",
        Icons.Outlined.Archive,
        enabled = pane.isWritableLocation,
        onClick = act { vm.compressSelection(paneId, ArchiveFormat.TAR_GZ) }
    )
    if (!target.isDirectory && !multi && (target.volumeId == VOLUME_DRIVE || target.isOfflinePinned)) {
        MenuEntry(
            "Offline verfügbar",
            Icons.Outlined.OfflinePin,
            checked = target.isOfflinePinned,
            onClick = act { vm.toggleOfflinePinned(target) }
        )
    }
    HorizontalDivider()
    MenuEntry(
        "In Papierkorb verschieben",
        Icons.Outlined.Delete,
        "Entf",
        enabled = editable,
        onClick = act { vm.trashSelection(paneId) }
    )
    MenuEntry("Endgültig löschen", Icons.Outlined.DeleteForever, "Umschalt + Entf", enabled = editable, onClick = act { vm.requestPermanentDelete(paneId) })
    HorizontalDivider()
    MenuEntry("Datei-Info", Icons.Outlined.Info, onClick = act { vm.showDetailsFor(paneId) })
}

@Composable
private fun BackgroundMenuItems(
    pane: PaneState,
    paneId: String,
    vm: ChromeFilesViewModel,
    showHiddenFiles: Boolean,
    hasClipboard: Boolean,
    act: (() -> Unit) -> () -> Unit
) {
    if (pane.isTrash) {
        MenuEntry("Papierkorb leeren", Icons.Outlined.DeleteForever, enabled = pane.nodes.isNotEmpty(), onClick = act { vm.emptyTrash() })
        MenuEntry("Alles auswählen", Icons.Outlined.SelectAll, "Strg + A", onClick = act { vm.pane(paneId)?.selectAll() })
        return
    }
    MenuEntry(
        "Einfügen",
        Icons.Outlined.ContentPaste,
        "Strg + V",
        enabled = pane.isWritableLocation && hasClipboard,
        onClick = act { vm.paste(paneId) }
    )
    MenuEntry(
        "Neuer Ordner",
        Icons.Outlined.CreateNewFolder,
        "Strg + E",
        enabled = pane.isWritableLocation,
        onClick = act { vm.showNewFolderDialog(paneId) }
    )
    MenuEntry("Alles auswählen", Icons.Outlined.SelectAll, "Strg + A", enabled = pane.nodes.isNotEmpty(), onClick = act { vm.pane(paneId)?.selectAll() })
    HorizontalDivider()
    MenuEntry(
        if (pane.viewMode == FileViewMode.LIST) "Rasteransicht" else "Listenansicht",
        if (pane.viewMode == FileViewMode.LIST) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
        onClick = act { vm.pane(paneId)?.toggleViewMode() }
    )
    MenuEntry(
        "Versteckte Dateien anzeigen",
        null,
        "Strg + .",
        checked = showHiddenFiles,
        onClick = act { vm.toggleShowHiddenFiles() }
    )
    if (pane.currentVolumeId.startsWith("archive_")) {
        HorizontalDivider()
        MenuEntry("Auswerfen", Icons.Outlined.Eject, onClick = act { vm.ejectVolume(pane.currentVolumeId) })
    }
}

@Composable
private fun MenuEntry(
    text: String,
    icon: ImageVector?,
    shortcut: String? = null,
    enabled: Boolean = true,
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text) },
        enabled = enabled,
        onClick = onClick,
        leadingIcon = {
            when {
                checked == true -> Icon(Icons.Filled.Check, contentDescription = null)
                icon != null && checked == null -> Icon(icon, contentDescription = null)
                else -> Spacer(Modifier.size(24.dp))
            }
        },
        trailingIcon = shortcut?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
