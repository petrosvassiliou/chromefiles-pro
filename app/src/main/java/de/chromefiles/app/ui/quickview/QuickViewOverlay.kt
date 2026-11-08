package de.chromefiles.app.ui.quickview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.util.ChromeFormat
import de.chromefiles.app.ui.util.icon
import de.chromefiles.app.ui.util.iconTint
import de.chromefiles.app.ui.viewmodel.ChromeFilesViewModel
import de.chromefiles.app.ui.viewmodel.QuickViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class PreviewKind { IMAGE, PDF, VIDEO, AUDIO, TEXT, NONE }

private fun VfsNode.previewKind(): PreviewKind = when {
    isImage -> PreviewKind.IMAGE
    isPdf -> PreviewKind.PDF
    isVideo -> PreviewKind.VIDEO
    isAudio -> PreviewKind.AUDIO
    extension.lowercase() in TEXT_PREVIEW_EXTENSIONS || mimeType.startsWith("text/") ||
        name.lowercase() in setOf("dockerfile", "makefile", "readme", "license") -> PreviewKind.TEXT
    else -> PreviewKind.NONE
}

/**
 * ChromeOS-QuickView (Leertaste): schwebendes Vorschaufenster über der Dateiliste.
 * Tastatur (Leertaste/Esc schließen, ←/→ blättern) wird im Root-Screen behandelt, damit der Fokus dort bleibt.
 */
@Composable
fun QuickViewOverlay(
    state: QuickViewState,
    viewModel: ChromeFilesViewModel,
    isEditRunning: Boolean,
    onClose: () -> Unit
) {
    val node = state.current
    val kind = node.previewKind()
    var cropRect by remember(node.id, state.version) { mutableStateOf<Rect?>(null) }
    var showScaleDialog by remember(node.id) { mutableStateOf(false) }
    var isEditingText by remember(node.id, state.version) { mutableStateOf(false) }
    var loadedOriginalText by remember(node.id, state.version) { mutableStateOf("") }
    var editedText by remember(node.id, state.version) { mutableStateOf("") }

    val canEdit = kind == PreviewKind.IMAGE && viewModel.canEditImage(node)
    val canEditText = kind == PreviewKind.TEXT && !node.isReadOnly
    val imageSize by produceState<Pair<Int, Int>?>(null, node.id, state.version) {
        value = if (canEdit) withContext(Dispatchers.IO) { viewModel.imageSizeFor(node) } else null
    }
    val fileResult by produceState<Result<File>?>(null, node.id, state.version) {
        value = null
        value = viewModel.localFileFor(node)
    }
    val noRipple = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------------------------------------------------------- Kopfzeile
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sizeLabel = imageSize?.let { (w, h) -> " • $w × $h px" }.orEmpty()
                        Text(
                            "${state.index + 1} von ${state.items.size} • ${ChromeFormat.bytes(node.sizeBytes)} • ${node.germanTypeLabel}$sizeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (canEditText) {
                        if (isEditingText) {
                            TextButton(
                                onClick = {
                                    editedText = loadedOriginalText
                                    isEditingText = false
                                },
                                enabled = !isEditRunning
                            ) {
                                Text("Abbrechen")
                            }
                            Button(
                                onClick = {
                                    viewModel.saveQuickViewText(editedText)
                                    isEditingText = false
                                },
                                enabled = !isEditRunning
                            ) {
                                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Speichern")
                            }
                            Spacer(Modifier.width(8.dp))
                        } else {
                            HeaderAction(Icons.Outlined.Edit, "Text/Code bearbeiten", enabled = !isEditRunning) {
                                editedText = loadedOriginalText
                                isEditingText = true
                            }
                        }
                    }
                    if (canEdit) {
                        if (cropRect != null) {
                            TextButton(onClick = { cropRect = null }, enabled = !isEditRunning) { Text("Abbrechen") }
                            Button(
                                onClick = {
                                    val rect = cropRect
                                    if (rect != null && !rect.isFullImage()) viewModel.cropQuickViewImage(rect.toAndroidRectF())
                                    cropRect = null
                                },
                                enabled = !isEditRunning && cropRect?.isFullImage() == false
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Zuschneiden")
                            }
                            Spacer(Modifier.width(8.dp))
                        } else {
                            HeaderAction(Icons.Outlined.Rotate90DegreesCcw, "Nach links drehen", enabled = !isEditRunning) {
                                viewModel.rotateQuickViewImage(clockwise = false)
                            }
                            HeaderAction(Icons.Outlined.Rotate90DegreesCw, "Nach rechts drehen", enabled = !isEditRunning) {
                                viewModel.rotateQuickViewImage(clockwise = true)
                            }
                            HeaderAction(Icons.Outlined.Crop, "Zuschneiden", enabled = !isEditRunning && imageSize != null) {
                                cropRect = Rect(0.1f, 0.1f, 0.9f, 0.9f)
                            }
                            HeaderAction(Icons.Outlined.PhotoSizeSelectLarge, "Größe ändern", enabled = !isEditRunning && imageSize != null) {
                                showScaleDialog = true
                            }
                        }
                    }
                    HeaderAction(Icons.AutoMirrored.Outlined.OpenInNew, "Öffnen mit …", enabled = true) { viewModel.openWith(node) }
                    HeaderAction(Icons.Outlined.Close, "Schließen (Leertaste / Esc)", enabled = true, onClick = onClose)
                }
                if (isEditRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                // ---------------------------------------------------------- Inhalt
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))) {
                    val result = fileResult
                    when {
                        result == null -> LoadingContent(node.isRemote)
                        result.isFailure -> PreviewMessage("Vorschau nicht möglich: ${result.exceptionOrNull()?.message}")
                        else -> {
                            val file = result.getOrThrow()
                            val cacheKey = "${file.absolutePath}#${file.lastModified()}#${state.version}"
                            val rect = cropRect
                            val size = imageSize
                            when (kind) {
                                PreviewKind.IMAGE ->
                                    if (rect != null && size != null) {
                                        CropEditor(file, cacheKey, size, rect, onCropRectChange = { cropRect = it }, modifier = Modifier.fillMaxSize().padding(16.dp))
                                    } else {
                                        ZoomableImage(file, cacheKey, node.name, Modifier.fillMaxSize())
                                    }
                                PreviewKind.PDF -> PdfViewer(file, Modifier.fillMaxSize())
                                PreviewKind.VIDEO -> VideoPlayer(file, Modifier.fillMaxSize())
                                PreviewKind.AUDIO -> AudioPlayer(file, Modifier.fillMaxSize())
                                PreviewKind.TEXT -> CodeViewer(
                                    file = file,
                                    extension = node.extension,
                                    isEditing = isEditingText,
                                    editedText = editedText,
                                    onEditedTextChange = { editedText = it },
                                    onInitialTextLoaded = { txt ->
                                        loadedOriginalText = txt
                                        if (!isEditingText) editedText = txt
                                    },
                                    onSaveShortcut = {
                                        if (isEditingText && !isEditRunning) {
                                            viewModel.saveQuickViewText(editedText)
                                            isEditingText = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                PreviewKind.NONE -> NoPreview(node, onOpenWith = { viewModel.openWith(node) })
                            }
                        }
                    }

                    if (cropRect == null && !isEditingText) {
                        NavArrow(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            description = "Vorherige Datei (←)",
                            enabled = state.index > 0,
                            onClick = { viewModel.quickViewStep(-1) },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                        )
                        NavArrow(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            description = "Nächste Datei (→)",
                            enabled = state.index < state.items.lastIndex,
                            onClick = { viewModel.quickViewStep(1) },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                        )
                    }
                }
            }
        }
    }

    val size = imageSize
    if (showScaleDialog && size != null) {
        ScaleImageDialog(
            originalSize = size,
            onConfirm = { w, h ->
                showScaleDialog = false
                viewModel.scaleQuickViewImage(w, h)
            },
            onDismiss = { showScaleDialog = false }
        )
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun NavArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (!enabled) return
    FilledTonalIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
        ),
        modifier = modifier.size(44.dp).pointerHoverIcon(PointerIcon.Hand)
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun LoadingContent(isRemote: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        if (isRemote) {
            Spacer(Modifier.height(12.dp))
            Text("Wird vom Server geladen …", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoPreview(node: VfsNode, onOpenWith: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(node.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${node.germanTypeLabel} • ${ChromeFormat.bytes(node.sizeBytes)} • ${ChromeFormat.date(node.lastModifiedMillis)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text("Für diesen Dateityp ist keine Vorschau verfügbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenWith) { Text("Öffnen mit …") }
    }
}
