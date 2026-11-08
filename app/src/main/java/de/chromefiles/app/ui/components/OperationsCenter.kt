package de.chromefiles.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chromefiles.app.model.ConflictResolution
import de.chromefiles.app.model.FileOperationProgress
import de.chromefiles.app.model.FileOperationStatus
import de.chromefiles.app.model.FileOperationType
import de.chromefiles.app.ui.util.ChromeFormat
import kotlinx.coroutines.delay
import java.util.Locale

private val ACTIVE_STATES = setOf(
    FileOperationStatus.RUNNING,
    FileOperationStatus.PAUSED,
    FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION
)

/**
 * Schwebendes Operations-Center (unten rechts, wie die ChromeOS-Fortschrittsanzeige):
 * Fortschritt, MB/s, Restzeit, Pause/Fortsetzen und Abbrechen je Vorgang; einklappbar.
 * Abgeschlossene Vorgänge verschwinden nach einigen Sekunden automatisch.
 */
@Composable
fun OperationsCenter(
    operations: List<FileOperationProgress>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismissCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val active = operations.count { it.status in ACTIVE_STATES }

    LaunchedEffect(active, operations.size) {
        if (operations.isNotEmpty() && active == 0) {
            delay(AUTO_DISMISS_MS)
            onDismissCompleted()
        }
    }

    AnimatedVisibility(
        visible = operations.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.width(380.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            active == 0 -> "Alle Vorgänge abgeschlossen"
                            active == 1 -> "1 Vorgang läuft"
                            else -> "$active Vorgänge laufen"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (expanded) "Einklappen" else "Ausklappen"
                        )
                    }
                    IconButton(onClick = onDismissCompleted, enabled = active < operations.size) {
                        Icon(Icons.Outlined.Close, contentDescription = "Abgeschlossene ausblenden")
                    }
                }
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(operations, key = { it.operationId }) { op ->
                            OperationRow(op, onPause, onResume, onCancel)
                        }
                    }
                } else {
                    val running = operations.firstOrNull { it.status in ACTIVE_STATES }
                    if (running != null) {
                        LinearProgressIndicator(
                            progress = { running.progressFraction },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            drawStopIndicator = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(
    op: FileOperationProgress,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (op.status) {
                FileOperationStatus.COMPLETED -> Icons.Filled.CheckCircle to Color(0xFF1E8E3E)
                FileOperationStatus.FAILED -> Icons.Filled.ErrorOutline to colors.error
                FileOperationStatus.CANCELLED -> Icons.Outlined.Cancel to colors.onSurfaceVariant
                FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION -> Icons.Outlined.Warning to Color(0xFFF9AB00)
                else -> (if (op.type == FileOperationType.MOVE) Icons.Outlined.DriveFileMove else Icons.Outlined.ContentCopy) to colors.primary
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(op.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = statusLine(op),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (op.status == FileOperationStatus.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (op.status) {
                FileOperationStatus.RUNNING -> IconButton(onClick = { onPause(op.operationId) }) {
                    Icon(Icons.Outlined.Pause, contentDescription = "Pausieren")
                }
                FileOperationStatus.PAUSED -> IconButton(onClick = { onResume(op.operationId) }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Fortsetzen")
                }
                else -> Unit
            }
            if (op.status in ACTIVE_STATES) {
                IconButton(onClick = { onCancel(op.operationId) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Abbrechen")
                }
            }
        }
        if (op.status in ACTIVE_STATES) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { op.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (op.status == FileOperationStatus.PAUSED) colors.outline else colors.primary,
                drawStopIndicator = {}
            )
        }
    }
}

private fun statusLine(op: FileOperationProgress): String = when (op.status) {
    FileOperationStatus.RUNNING -> buildString {
        append(op.currentFileName)
        append(" · ")
        append(ChromeFormat.bytes(op.processedBytes)).append(" von ").append(ChromeFormat.bytes(op.totalBytes))
        if (op.speedMegabytesPerSec > 0f) {
            append(" · ").append(String.format(Locale.GERMANY, "%.1f MB/s", op.speedMegabytesPerSec))
            append(" · noch ").append(ChromeFormat.duration(op.estimatedRemainingSeconds * 1000))
        }
    }
    FileOperationStatus.PAUSED -> "Pausiert · ${(op.progressFraction * 100).toInt()} %"
    FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION -> "Wartet auf Entscheidung: „${op.conflictingNodeName.orEmpty()}“ existiert bereits"
    FileOperationStatus.COMPLETED -> "Fertig · ${ChromeFormat.itemCount(op.totalFiles)}"
    FileOperationStatus.CANCELLED -> "Abgebrochen"
    FileOperationStatus.FAILED -> op.errorMessage ?: op.currentFileName
}

/**
 * Konfliktdialog wie in ChromeOS: „Ersetzen“, „Beide behalten“, „Überspringen“ – optional für alle weiteren Konflikte.
 */
@Composable
fun ConflictDialog(
    operation: FileOperationProgress,
    onResolve: (ConflictResolution, applyToAll: Boolean) -> Unit,
    onCancelOperation: () -> Unit
) {
    var applyToAll by remember(operation.operationId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
        title = { Text("Datei existiert bereits") },
        text = {
            Column {
                Text("Am Zielort gibt es bereits ein Element namens „${operation.conflictingNodeName.orEmpty()}“. Was möchtest du tun?")
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { applyToAll = !applyToAll }
                        .padding(end = 8.dp)
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Für alle weiteren Konflikte übernehmen")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(ConflictResolution.SKIP, applyToAll) }) { Text(ConflictResolution.SKIP.labelDe) }
                TextButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH, applyToAll) }) { Text(ConflictResolution.KEEP_BOTH.labelDe) }
                TextButton(onClick = { onResolve(ConflictResolution.REPLACE, applyToAll) }) { Text(ConflictResolution.REPLACE.labelDe) }
            }
        },
        dismissButton = { TextButton(onClick = onCancelOperation) { Text("Vorgang abbrechen") } }
    )
}

private const val AUTO_DISMISS_MS = 5_000L
