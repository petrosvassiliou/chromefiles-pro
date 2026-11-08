package de.chromefiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chromefiles.app.model.VfsVolume
import de.chromefiles.app.model.VfsVolumeType
import de.chromefiles.app.ui.dnd.DropPayload
import de.chromefiles.app.ui.dnd.fileDropTarget
import de.chromefiles.app.ui.util.ChromeFormat
import de.chromefiles.app.ui.util.icon
import de.chromefiles.app.ui.viewmodel.SidebarTreeNode

/**
 * Linke ChromeOS-Navigationsleiste: Baum mit Auf-/Zuklappen, Eject-Buttons für Archive,
 * Wechselmedien & Netzwerkfreigaben sowie Speicherplatz-Indikator unten links.
 */
@Composable
fun NavigationSidebar(
    nodes: List<SidebarTreeNode>,
    volumes: List<VfsVolume>,
    currentPath: String,
    currentVolumeId: String,
    onNodeClick: (SidebarTreeNode) -> Unit,
    onToggleExpand: (SidebarTreeNode) -> Unit,
    onEject: (String) -> Unit,
    modifier: Modifier = Modifier,
    canDrop: (SidebarTreeNode) -> Boolean = { false },
    onDrop: (SidebarTreeNode, DropPayload) -> Boolean = { _, _ -> false },
    onAddNetwork: (() -> Unit)? = null,
    onAddSafFolder: (() -> Unit)? = null,
    onConnectDrive: (() -> Unit)? = null,
    isDriveConnected: Boolean = false
) {
    Column(modifier = modifier.fillMaxHeight()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            items(nodes, key = { it.key + "@" + it.depth }) { node ->
                if (node.depth == 0 && needsDividerBefore(node, nodes)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                SidebarRow(
                    node = node,
                    isSelected = isNodeSelected(node, currentPath, currentVolumeId),
                    onClick = { onNodeClick(node) },
                    onToggleExpand = { onToggleExpand(node) },
                    onEject = { onEject(node.volumeId) },
                    dropEnabled = canDrop(node),
                    onDrop = { onDrop(node, it) }
                )
            }
        }
        if (onAddSafFolder != null) {
            TextButton(
                onClick = onAddSafFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(Icons.Outlined.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Linux-/USB-Ordner (SAF) einbinden", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onConnectDrive != null && !isDriveConnected) {
            TextButton(
                onClick = onConnectDrive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(Icons.Outlined.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Google Drive (OAuth) verbinden", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onAddNetwork != null) {
            TextButton(
                onClick = onAddNetwork,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(Icons.Outlined.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Netzwerkfreigabe hinzufügen", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        StorageIndicator(volumes = volumes, isDriveConnected = isDriveConnected)
    }
}

private fun needsDividerBefore(node: SidebarTreeNode, nodes: List<SidebarTreeNode>): Boolean {
    // Trennlinien wie in ChromeOS: vor "Meine Dateien", vor eingehängten Medien und vor dem Papierkorb.
    val firstMounted = nodes.firstOrNull {
        it.depth == 0 && it.volumeType in setOf(
            VfsVolumeType.MOUNTED_ARCHIVE, VfsVolumeType.REMOVABLE_MEDIA, VfsVolumeType.SMB_SHARE
        )
    }
    return node.volumeType == VfsVolumeType.MY_FILES ||
        node.volumeType == VfsVolumeType.TRASH ||
        node.key == firstMounted?.key
}

private fun isNodeSelected(node: SidebarTreeNode, currentPath: String, currentVolumeId: String): Boolean {
    return when (node.volumeType) {
        VfsVolumeType.RECENT, VfsVolumeType.STARRED, VfsVolumeType.TRASH -> node.volumeId == currentVolumeId
        else -> if (node.volumeId == "google_drive_offline") {
            currentVolumeId == node.volumeId
        } else {
            node.path == currentPath && currentVolumeId !in setOf("recent", "starred", "trash", "google_drive_offline")
        }
    }
}

@Composable
private fun SidebarRow(
    node: SidebarTreeNode,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
    onEject: () -> Unit,
    dropEnabled: Boolean,
    onDrop: (DropPayload) -> Boolean
) {
    val colors = MaterialTheme.colorScheme
    val background = if (isSelected) colors.secondaryContainer else colors.surfaceContainerLow
    val contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .fileDropTarget(enabled = dropEnabled, shape = RoundedCornerShape(20.dp), onDrop = onDrop)
            .clickable(onClick = onClick)
            .padding(start = (4 + node.depth * 16).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (node.isExpandable) {
                Icon(
                    imageVector = if (node.isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (node.isExpanded) "Zuklappen" else "Aufklappen",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggleExpand)
                )
            }
        }
        Icon(
            imageVector = node.icon(),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = node.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (node.isEjectable) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Auswerfen") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onEject, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Eject,
                        contentDescription = "„${node.title}“ auswerfen",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageIndicator(volumes: List<VfsVolume>, isDriveConnected: Boolean = false) {
    val myFiles = volumes.firstOrNull { it.type == VfsVolumeType.MY_FILES && it.totalBytes > 0 }
    val drive = volumes.firstOrNull { it.type == VfsVolumeType.GOOGLE_DRIVE && it.totalBytes > 0 }
    if (myFiles == null && drive == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        myFiles?.let { StorageBar(label = "Googlebook-Speicher", volume = it) }
        drive?.let {
            Spacer(Modifier.height(12.dp))
            StorageBar(
                label = if (isDriveConnected) "Google Drive" else "Google Drive (Demo-Kontingent)",
                volume = it
            )
        }
    }
}

@Composable
private fun StorageBar(label: String, volume: VfsVolume) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { volume.usageRatio },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = if (volume.usageRatio > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {}
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${ChromeFormat.bytes(volume.usedBytes)} von ${ChromeFormat.bytes(volume.totalBytes)} belegt · " +
            "${ChromeFormat.bytes(volume.freeBytes)} frei",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Vertikaler Splitter zum Verstellen der Breite benachbarter Spalten per Maus.
 */
@Composable
fun PaneResizeHandle(
    onDragDp: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx -> onDragDp(with(density) { deltaPx.toDp().value }) }
            )
    )
}
