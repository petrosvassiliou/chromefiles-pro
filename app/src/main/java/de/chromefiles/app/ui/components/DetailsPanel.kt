package de.chromefiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.chromefiles.app.data.media.ChecksumCalculator
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository
import de.chromefiles.app.model.FileColorTag
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.util.ChromeFormat
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rechte Info- & Metadaten-Spalte (per „i“-Button umschaltbar).
 */
@Composable
fun DetailsPanel(
    detailedNode: VfsNode?,
    selectedNodes: List<VfsNode>,
    currentFolderTitle: String,
    totalItemsInFolder: Int,
    onClose: () -> Unit,
    onToggleOffline: (VfsNode) -> Unit,
    onToggleStarred: (VfsNode) -> Unit,
    onToggleColorTag: (VfsNode, String) -> Unit = { _, _ -> },
    onLoadLocalFile: suspend (VfsNode) -> Result<File> = { Result.failure(UnsupportedOperationException()) },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Datei-Info",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Datei-Info schließen")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                selectedNodes.size > 1 -> MultiSelectionSummary(selectedNodes)
                detailedNode != null -> SingleNodeDetails(
                    node = detailedNode,
                    onToggleOffline = onToggleOffline,
                    onToggleStarred = onToggleStarred,
                    onToggleColorTag = onToggleColorTag,
                    onLoadLocalFile = onLoadLocalFile
                )
                else -> FolderSummary(currentFolderTitle, totalItemsInFolder)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleNodeDetails(
    node: VfsNode,
    onToggleOffline: (VfsNode) -> Unit,
    onToggleStarred: (VfsNode) -> Unit,
    onToggleColorTag: (VfsNode, String) -> Unit,
    onLoadLocalFile: suspend (VfsNode) -> Result<File>
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var hashes by remember(node.id, node.lastModifiedMillis) { mutableStateOf<ChecksumCalculator.FileHashes?>(null) }
    var isComputingHashes by remember(node.id) { mutableStateOf(false) }
    var hashError by remember(node.id) { mutableStateOf<String?>(null) }
    var expectedHash by remember(node.id) { mutableStateOf("") }

    LaunchedEffect(node.id) {
        hashes = null
        hashError = null
        expectedHash = ""
    }

    FileThumbnail(
        node = node,
        renderWidth = 560.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            node.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (node.volumeId != VirtualFileSystemRepository.VOLUME_TRASH) {
            IconButton(onClick = { onToggleStarred(node) }) {
                Icon(
                    imageVector = if (node.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (node.isStarred) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                    tint = if (node.isStarred) Color(0xFFF9AB00) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (node.volumeId != VirtualFileSystemRepository.VOLUME_TRASH && !node.isRemote) {
        SectionTitle("Farb-Labels (Tags)")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FileColorTag.entries.forEach { tag ->
                val selected = node.tags.any { it.equals(tag.id, ignoreCase = true) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(tag.argbHex))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onToggleColorTag(node, tag.id) }
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = tag.labelDe,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }

    SectionTitle("Allgemein")
    DetailRow("Typ", node.germanTypeLabel)
    if (node.isDirectory) {
        node.childCount?.let { DetailRow("Inhalt", ChromeFormat.itemCount(it)) }
    } else {
        DetailRow("Größe", "${ChromeFormat.bytes(node.sizeBytes)} (${"%,d".format(node.sizeBytes).replace(',', '.')} Byte)")
    }
    DetailRow("Geändert", ChromeFormat.date(node.lastModifiedMillis))
    DetailRow("Speicherort", node.parentPath, selectable = true)
    if (node.isReadOnly) DetailRow("Zugriff", "Schreibgeschützt")

    if (node.trashedAtMillis != null) {
        SectionTitle("Papierkorb")
        node.trashedFromPath?.let { DetailRow("Ursprünglicher Ort", it, selectable = true) }
        DetailRow("Gelöscht", ChromeFormat.date(node.trashedAtMillis))
        val remainingDays = 30 - ((System.currentTimeMillis() - node.trashedAtMillis) / 86_400_000L)
        DetailRow("Endgültige Löschung", "in ${remainingDays.coerceAtLeast(0)} Tag(en)")
    }

    node.exifMetadata?.let { exif ->
        SectionTitle("Bild")
        if (exif.widthPx > 0 && exif.heightPx > 0) DetailRow("Abmessungen", "${exif.widthPx} × ${exif.heightPx} Pixel")
        exif.cameraModel?.let { DetailRow("Kamera", it) }
        exif.aperture?.let { DetailRow("Blende", it) }
        exif.exposureTime?.let { DetailRow("Belichtung", it) }
        exif.iso?.let { DetailRow("ISO", it) }
        exif.gpsCoordinates?.let { DetailRow("GPS", it, selectable = true) }
    }

    node.audioMetadata?.let { audio ->
        SectionTitle("Audio")
        audio.title?.let { DetailRow("Titel", it) }
        audio.artist?.let { DetailRow("Interpret", it) }
        audio.album?.let { DetailRow("Album", it) }
        if (audio.durationMillis > 0) DetailRow("Dauer", ChromeFormat.duration(audio.durationMillis))
        audio.bitrateKbps?.let { DetailRow("Bitrate", "$it kbit/s") }
    }

    if (!node.isDirectory) {
        SectionTitle("Prüfsummen (MD5 / SHA-256)")
        if (hashes == null) {
            OutlinedButton(
                onClick = {
                    isComputingHashes = true
                    hashError = null
                    scope.launch {
                        onLoadLocalFile(node)
                            .mapCatching { file -> ChecksumCalculator.computeHashes(file).getOrThrow() }
                            .onSuccess { hashes = it }
                            .onFailure { hashError = it.message ?: "Prüfsumme konnte nicht berechnet werden." }
                        isComputingHashes = false
                    }
                },
                enabled = !isComputingHashes,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isComputingHashes) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Wird berechnet …")
                } else {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("MD5 & SHA-256 berechnen")
                }
            }
            hashError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        } else {
            val h = hashes!!
            HashDisplayRow("MD5", h.md5, onCopy = { clipboard.setText(AnnotatedString(h.md5)) })
            HashDisplayRow("SHA-256", h.sha256, onCopy = { clipboard.setText(AnnotatedString(h.sha256)) })
            OutlinedTextField(
                value = expectedHash,
                onValueChange = { expectedHash = it.trim() },
                singleLine = true,
                label = { Text("Erwarteten Hash vergleichen") },
                placeholder = { Text("MD5 oder SHA-256 einfügen …") },
                modifier = Modifier.fillMaxWidth()
            )
            if (expectedHash.isNotEmpty()) {
                val matchesMd5 = expectedHash.equals(h.md5, ignoreCase = true)
                val matchesSha256 = expectedHash.equals(h.sha256, ignoreCase = true)
                val msg = when {
                    matchesSha256 -> "Stimmt exakt mit SHA-256 überein"
                    matchesMd5 -> "Stimmt exakt mit MD5 überein"
                    else -> "Keine Übereinstimmung mit MD5 oder SHA-256"
                }
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (matchesMd5 || matchesSha256) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }
        }
    }

    val isDriveItem = node.volumeId == VirtualFileSystemRepository.VOLUME_DRIVE ||
        node.volumeId == VirtualFileSystemRepository.VOLUME_DRIVE_OFFLINE
    if (isDriveItem && !node.isDirectory) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline verfügbar", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Datei lokal auf dem Googlebook vorhalten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = node.isOfflinePinned, onCheckedChange = { onToggleOffline(node) })
        }
    }
}

@Composable
private fun HashDisplayRow(label: String, hash: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "$label kopieren",
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        SelectionContainer {
            Text(
                hash,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

@Composable
private fun MultiSelectionSummary(nodes: List<VfsNode>) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
    }
    Text("${nodes.size} Elemente ausgewählt", style = MaterialTheme.typography.titleMedium)
    val folders = nodes.count { it.isDirectory }
    val files = nodes.size - folders
    DetailRow("Ordner", folders.toString())
    DetailRow("Dateien", files.toString())
    DetailRow("Gesamtgröße (Dateien)", ChromeFormat.bytes(nodes.filterNot { it.isDirectory }.sumOf { it.sizeBytes }))
}

@Composable
private fun FolderSummary(title: String, count: Int) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    DetailRow("Inhalt", ChromeFormat.itemCount(count))
    Text(
        "Wählen Sie eine Datei aus, um Details, Vorschau und Metadaten anzuzeigen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DetailRow(label: String, value: String, selectable: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        if (selectable) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
