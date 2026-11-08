package de.chromefiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chromefiles.app.model.VfsNode

/**
 * Reine Berechnungslogik für das Mehrfach-Umbenennen (Batch Rename).
 * Unterstützt Suchen & Ersetzen (Text oder Regex) sowie Namensmuster mit Platzhaltern:
 * - `{name}`: ursprünglicher Dateiname ohne Dateiendung (nach Suchen & Ersetzen)
 * - `{ext}`: ursprüngliche Dateiendung
 * - `{#}`, `{##}`, `{###}`, `{####}` oder `{n}`: fortlaufender Zähler mit führenden Nullen
 */
object BatchRenameEngine {

    data class RenamePreviewItem(
        val node: VfsNode,
        val originalName: String,
        val newName: String
    ) {
        val isChanged: Boolean get() = originalName != newName && newName.isNotBlank()
    }

    fun computePreview(
        nodes: List<VfsNode>,
        searchText: String,
        replaceText: String,
        useRegex: Boolean = false,
        pattern: String = "{name}",
        startNumber: Int = 1,
        stepNumber: Int = 1
    ): List<RenamePreviewItem> {
        val effectivePattern = pattern.ifBlank { "{name}" }
        val hashRegex = Regex("\\{(#+|n)\\}")
        return nodes.mapIndexed { index, node ->
            val rawName = node.name
            val dotIndex = rawName.lastIndexOf('.')
            val hasExt = !node.isDirectory && dotIndex > 0
            val baseOrig = if (hasExt) rawName.substring(0, dotIndex) else rawName
            val extOrig = if (hasExt) rawName.substring(dotIndex + 1) else ""

            val replacedBase = if (searchText.isNotEmpty()) {
                if (useRegex) {
                    runCatching { Regex(searchText).replace(baseOrig, replaceText) }.getOrDefault(baseOrig)
                } else {
                    baseOrig.replace(searchText, replaceText)
                }
            } else {
                baseOrig
            }

            val numberValue = startNumber + index * stepNumber.coerceAtLeast(1)
            var formatted = effectivePattern
                .replace("{name}", replacedBase)
                .replace("{ext}", extOrig)

            formatted = hashRegex.replace(formatted) { matchResult ->
                val token = matchResult.groupValues[1]
                val width = if (token == "n") 1 else token.length
                numberValue.toString().padStart(width, '0')
            }

            val finalName = if (hasExt && !effectivePattern.contains("{ext}")) {
                "$formatted.$extOrig"
            } else {
                formatted
            }.trim().replace("/", "_")

            RenamePreviewItem(
                node = node,
                originalName = rawName,
                newName = finalName.ifEmpty { rawName }
            )
        }
    }
}

@Composable
fun BatchRenameDialog(
    nodes: List<VfsNode>,
    onConfirm: (List<Pair<VfsNode, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("{name}_{###}") }
    var startNumberText by remember { mutableStateOf("1") }

    val startNumber = startNumberText.toIntOrNull() ?: 1
    val previews = remember(nodes, searchText, replaceText, useRegex, pattern, startNumber) {
        BatchRenameEngine.computePreview(
            nodes = nodes,
            searchText = searchText,
            replaceText = replaceText,
            useRegex = useRegex,
            pattern = pattern,
            startNumber = startNumber
        )
    }
    val changedCount = previews.count { it.isChanged }
    val hasDuplicateTargets = previews.map { it.newName.lowercase() }.distinct().size != previews.size

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
        title = { Text("${nodes.size} Elemente mehrfach umbenennen") },
        text = {
            Column(
                modifier = Modifier.width(520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Platzhalter im Muster: `{name}` = Basisname, `{###}` = Zähler (001, 002 …), `{ext}` = Dateiendung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        singleLine = true,
                        label = { Text("Namensmuster") },
                        placeholder = { Text("{name}_{###}") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startNumberText,
                        onValueChange = { startNumberText = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        label = { Text("Start-Nr.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(105.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        label = { Text("Suchen im Namen") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = replaceText,
                        onValueChange = { replaceText = it },
                        singleLine = true,
                        label = { Text("Ersetzen durch") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRegex, onCheckedChange = { useRegex = it })
                    Text("Regulären Ausdruck (Regex) verwenden", style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                Text(
                    "Live-Vorschau ($changedCount von ${previews.size} geändert)",
                    style = MaterialTheme.typography.labelLarge
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(previews, key = { it.node.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.originalName,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = item.newName,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (item.isChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (hasDuplicateTargets) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Warnung: Das aktuelle Muster erzeugt doppelte Zielnamen. Bitte `{###}` oder `{name}` ergänzen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pairs = previews.filter { it.isChanged }.map { it.node to it.newName }
                    onConfirm(pairs)
                },
                enabled = changedCount > 0 && !hasDuplicateTargets
            ) {
                Text("Umbenennen ($changedCount)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
