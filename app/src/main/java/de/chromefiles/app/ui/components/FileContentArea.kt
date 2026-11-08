package de.chromefiles.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import de.chromefiles.app.model.SortColumn
import de.chromefiles.app.model.SortDirection
import de.chromefiles.app.model.SortSpec
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.dnd.DropPayload
import de.chromefiles.app.ui.dnd.fileDropTarget
import de.chromefiles.app.ui.util.ChromeFormat
import de.chromefiles.app.ui.util.fileItemGestures
import de.chromefiles.app.ui.util.icon
import de.chromefiles.app.ui.util.iconTint
import de.chromefiles.app.ui.util.rememberPdfFirstPage
import de.chromefiles.app.ui.viewmodel.ColumnWidths
import java.io.File
import kotlin.math.abs

/**
 * Geometrie-Zustand des Dateibereichs: Positionen der sichtbaren Elemente (für Gummiband-Auswahl,
 * Treffer-Tests und geometrische Pfeiltasten-Navigation im Raster) sowie das aktuelle Auswahlrechteck.
 */
class FileAreaState {
    internal val itemCoordinates = mutableMapOf<String, LayoutCoordinates>()
    internal var containerCoordinates: LayoutCoordinates? = null
    internal var lastDragPosition: Offset? = null
    internal var marqueeBaseSelection: Set<String> = emptySet()
    internal var scrollByPx: (suspend (Float) -> Float)? = null
    var marqueeRect by mutableStateOf<Rect?>(null)
        internal set

    /** Rechtecke aller sichtbaren Elemente im Koordinatensystem des Containers. */
    fun itemRects(): Map<String, Rect> {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return emptyMap()
        return itemCoordinates
            .filterValues { it.isAttached }
            .mapValues { (_, coords) -> container.localBoundingBoxOf(coords, clipBounds = false) }
    }

    fun hitTest(position: Offset): String? = itemRects().entries.firstOrNull { it.value.contains(position) }?.key

    /** Nächstes Element ober-/unterhalb von [leadId] (Rasteransicht, Pfeil hoch/runter). */
    fun verticalNeighbor(leadId: String, up: Boolean): String? {
        val rects = itemRects()
        val lead = rects[leadId] ?: return null
        val candidates = rects.filter { (id, r) ->
            id != leadId && if (up) r.center.y < lead.top else r.center.y > lead.bottom
        }
        if (candidates.isEmpty()) return null
        val nearestRowY = candidates.values.minOf { abs(it.center.y - lead.center.y) }
        return candidates
            .filter { abs(abs(it.value.center.y - lead.center.y) - nearestRowY) < 1f }
            .minByOrNull { abs(it.value.center.x - lead.center.x) }
            ?.key
    }
}

@Composable
private fun Modifier.trackItem(state: FileAreaState, id: String): Modifier {
    DisposableEffect(state, id) {
        onDispose { state.itemCoordinates.remove(id) }
    }
    return this.onGloballyPositioned { state.itemCoordinates[id] = it }
}

/**
 * Container-Modifier für den Dateibereich:
 * - Maus links auf freier Fläche + Ziehen: Gummiband-Auswahl (mit Strg/Umschalt additiv), Klick: Auswahl aufheben.
 * - Maus rechts auf freier Fläche: Hintergrund-Kontextmenü.
 * - Touch-Tipp auf freier Fläche: Auswahl aufheben (Scrollen bleibt unberührt).
 */
@Composable
fun Modifier.fileAreaPointerHandling(
    state: FileAreaState,
    selectedIds: () -> Set<String>,
    onPointerDown: () -> Unit,
    onMarqueeSelection: (base: Set<String>, hits: Set<String>) -> Unit,
    onBackgroundClick: () -> Unit,
    onBackgroundContextMenu: () -> Unit
): Modifier {
    val currentSelected by rememberUpdatedState(selectedIds)
    val currentOnPointerDown by rememberUpdatedState(onPointerDown)
    val currentOnMarquee by rememberUpdatedState(onMarqueeSelection)
    val currentOnBackgroundClick by rememberUpdatedState(onBackgroundClick)
    val currentOnBackgroundMenu by rememberUpdatedState(onBackgroundContextMenu)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 44.dp.toPx() }
    val maxScrollStepPx = with(density) { 18.dp.toPx() }

    LaunchedEffect(state.marqueeRect) {
        while (state.marqueeRect != null) {
            val pos = state.lastDragPosition
            val height = state.containerCoordinates?.takeIf { it.isAttached }?.size?.height?.toFloat() ?: 0f
            val scroller = state.scrollByPx
            if (pos != null && height > edgeThresholdPx * 2f && scroller != null) {
                val delta = when {
                    pos.y < edgeThresholdPx -> {
                        val ratio = ((edgeThresholdPx - pos.y) / edgeThresholdPx).coerceIn(0.15f, 1.5f)
                        -maxScrollStepPx * ratio
                    }
                    pos.y > height - edgeThresholdPx -> {
                        val ratio = ((pos.y - (height - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0.15f, 1.5f)
                        maxScrollStepPx * ratio
                    }
                    else -> 0f
                }
                if (delta != 0f) {
                    val consumed = scroller(delta)
                    if (consumed != 0f) {
                        state.marqueeRect?.let { rect ->
                            val hits = state.itemRects().filterValues { it.overlaps(rect) }.keys
                            currentOnMarquee(state.marqueeBaseSelection, hits)
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(16L)
        }
    }

    return this
        .onGloballyPositioned { state.containerCoordinates = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnPointerDown()
                if (state.hitTest(down.position) != null) return@awaitEachGesture
                val pressEvent = currentEvent

                if (down.type == PointerType.Mouse) {
                    if (pressEvent.buttons.isSecondaryPressed) {
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            if (!event.buttons.isSecondaryPressed && event.changes.none { it.pressed }) {
                                break
                            }
                        }
                        currentOnBackgroundMenu()
                        return@awaitEachGesture
                    }
                    if (!pressEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val mods = pressEvent.keyboardModifiers
                    val additive = mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed
                    val base = if (additive) currentSelected() else emptySet()
                    state.marqueeBaseSelection = base
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragging && !additive) currentOnBackgroundClick()
                                break
                            }
                            if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                state.lastDragPosition = change.position
                                val rect = Rect(
                                    left = minOf(down.position.x, change.position.x),
                                    top = minOf(down.position.y, change.position.y),
                                    right = maxOf(down.position.x, change.position.x),
                                    bottom = maxOf(down.position.y, change.position.y)
                                )
                                state.marqueeRect = rect
                                val hits = state.itemRects().filterValues { it.overlaps(rect) }.keys
                                currentOnMarquee(base, hits)
                            }
                        }
                    } finally {
                        state.marqueeRect = null
                        state.lastDragPosition = null
                    }
                } else {
                    val up = waitForUpOrCancellation(PointerEventPass.Final)
                    if (up != null) currentOnBackgroundClick()
                }
            }
        }
        .drawWithContent {
            drawContent()
            state.marqueeRect?.let { r ->
                drawRect(color = colors.primary.copy(alpha = 0.14f), topLeft = r.topLeft, size = r.size)
                drawRect(color = colors.primary, topLeft = r.topLeft, size = r.size, style = Stroke(width = 1.dp.toPx()))
            }
        }
}

/** Callbacks pro Element – gemeinsam für Listen- und Rasteransicht. */
class FileItemCallbacks(
    val onClick: (VfsNode, ctrl: Boolean, shift: Boolean, isTouch: Boolean) -> Unit,
    val onOpen: (VfsNode) -> Unit,
    val onContextMenu: (VfsNode) -> Unit,
    val onToggleStarred: (VfsNode) -> Unit = {},
    val onPrepareDrag: (VfsNode) -> DragAndDropTransferData?,
    val canDropOnFolder: (VfsNode) -> Boolean,
    val onDropOnFolder: (VfsNode, DropPayload) -> Boolean,
    val onRenameCommit: (VfsNode, String) -> Unit,
    val onRenameCancel: () -> Unit
)

@Composable
private fun Modifier.fileItem(
    node: VfsNode,
    isRenaming: Boolean,
    callbacks: FileItemCallbacks,
    areaState: FileAreaState,
    shape: RoundedCornerShape
): Modifier = this
    .trackItem(areaState, node.id)
    .clip(shape)
    .fileDropTarget(enabled = node.isDirectory && callbacks.canDropOnFolder(node), shape = shape) { payload ->
        callbacks.onDropOnFolder(node, payload)
    }
    .fileItemGestures(
        key = node.id,
        enabled = !isRenaming,
        onClick = { ctrl, shift, isTouch -> callbacks.onClick(node, ctrl, shift, isTouch) },
        onDoubleClick = { callbacks.onOpen(node) },
        onContextMenu = { callbacks.onContextMenu(node) },
        onPrepareDrag = { callbacks.onPrepareDrag(node) },
        decorationColor = MaterialTheme.colorScheme.primaryContainer
    )

/**
 * Detail-Listenansicht mit sortierbaren, per Maus in der Breite verstellbaren Spaltenköpfen.
 */
@Composable
fun FileListView(
    nodes: List<VfsNode>,
    selectedIds: Set<String>,
    leadSelectionId: String?,
    renamingId: String?,
    sortSpec: SortSpec,
    columnWidths: ColumnWidths,
    areaState: FileAreaState,
    callbacks: FileItemCallbacks,
    onSortColumn: (SortColumn) -> Unit,
    onResizeColumn: (SortColumn, Float) -> Unit,
    containerModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    DisposableEffect(areaState, listState) {
        areaState.scrollByPx = { delta -> listState.scrollBy(delta) }
        onDispose { areaState.scrollByPx = null }
    }
    LaunchedEffect(leadSelectionId, nodes) {
        val index = nodes.indexOfFirst { it.id == leadSelectionId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.index == index }
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        if (info == null || info.offset < 0 || info.offset + info.size > viewportEnd) {
            listState.animateScrollToItem(index)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompactList = maxWidth < 640.dp
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell(SortColumn.NAME, sortSpec, onSortColumn, Modifier.weight(1f))
                if (!isCompactList) {
                    PaneResizeHandle(onDragDp = { onResizeColumn(SortColumn.SIZE, it) })
                    HeaderCell(SortColumn.SIZE, sortSpec, onSortColumn, Modifier.width(columnWidths.size.dp))
                    PaneResizeHandle(onDragDp = { onResizeColumn(SortColumn.TYPE, it) })
                    HeaderCell(SortColumn.TYPE, sortSpec, onSortColumn, Modifier.width(columnWidths.type.dp))
                    PaneResizeHandle(onDragDp = { onResizeColumn(SortColumn.DATE_MODIFIED, it) })
                    HeaderCell(SortColumn.DATE_MODIFIED, sortSpec, onSortColumn, Modifier.width(columnWidths.date.dp))
                    Spacer(Modifier.width(72.dp))
                } else {
                    HeaderCell(SortColumn.DATE_MODIFIED, sortSpec, onSortColumn, Modifier.width(120.dp))
                    Spacer(Modifier.width(68.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = containerModifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(nodes, key = { it.id }) { node ->
                        FileListRow(
                            node = node,
                            isSelected = node.id in selectedIds,
                            isRenaming = node.id == renamingId,
                            isCompact = isCompactList,
                            columnWidths = columnWidths,
                            areaState = areaState,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(
    column: SortColumn,
    sortSpec: SortSpec,
    onSortColumn: (SortColumn) -> Unit,
    modifier: Modifier
) {
    val active = sortSpec.column == column
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onSortColumn(column) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = column.labelDe,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (active) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (sortSpec.direction == SortDirection.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (sortSpec.direction == SortDirection.ASCENDING) "Aufsteigend" else "Absteigend",
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun FileListRow(
    node: VfsNode,
    isSelected: Boolean,
    isRenaming: Boolean,
    isCompact: Boolean,
    columnWidths: ColumnWidths,
    areaState: FileAreaState,
    callbacks: FileItemCallbacks
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 52.dp else 42.dp)
            .fileItem(node, isRenaming, callbacks, areaState, shape)
            .background(if (isSelected) colors.primaryContainer else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        if (isRenaming) {
            InlineRenameField(
                node = node,
                textStyle = MaterialTheme.typography.bodyMedium,
                onCommit = { callbacks.onRenameCommit(node, it) },
                onCancel = callbacks.onRenameCancel,
                modifier = Modifier.weight(1f)
            )
        } else if (isCompact) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (node.isDirectory) FontWeight.Medium else FontWeight.Normal,
                        color = if (node.isHidden) colors.onSurfaceVariant else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    NodeBadges(node)
                }
                val subtitle = if (node.isDirectory) {
                    "${node.germanTypeLabel} • ${ChromeFormat.date(node.lastModifiedMillis)}"
                } else {
                    "${ChromeFormat.bytes(node.sizeBytes)} • ${ChromeFormat.date(node.lastModifiedMillis)}"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (node.isHidden) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                NodeBadges(node)
            }
            Spacer(Modifier.width(6.dp))
            CellText(if (node.isDirectory) "—" else ChromeFormat.bytes(node.sizeBytes), columnWidths.size.dp)
            Spacer(Modifier.width(6.dp))
            CellText(node.germanTypeLabel, columnWidths.type.dp)
            Spacer(Modifier.width(6.dp))
            CellText(ChromeFormat.date(node.lastModifiedMillis), columnWidths.date.dp)
        }
        IconButton(
            onClick = { callbacks.onToggleStarred(node) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = if (node.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (node.isStarred) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                tint = if (node.isStarred) Color(0xFFF9AB00) else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = { callbacks.onContextMenu(node) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "Kontextmenü öffnen",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CellText(text: String, width: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp)
    )
}

@Composable
private fun NodeBadges(node: VfsNode) {
    node.tags.mapNotNull { de.chromefiles.app.model.FileColorTag.fromId(it) }.forEach { tag ->
        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(tag.argbHex))
        )
    }
    if (node.isStarred) {
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Star, contentDescription = "Favorit", tint = Color(0xFFF9AB00), modifier = Modifier.size(14.dp))
    }
    if (node.isOfflinePinned) {
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Outlined.OfflinePin,
            contentDescription = "Offline verfügbar",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Inline-Umbenennen wie in ChromeOS: Der Name ohne Dateiendung ist vorausgewählt,
 * Enter übernimmt, Esc bricht ab, Fokusverlust übernimmt ebenfalls.
 */
@Composable
private fun InlineRenameField(
    node: VfsNode,
    textStyle: TextStyle,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var value by remember(node.id) {
        val dot = node.name.lastIndexOf('.')
        val end = if (!node.isDirectory && dot > 0) dot else node.name.length
        mutableStateOf(TextFieldValue(node.name, TextRange(0, end)))
    }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember(node.id) { mutableStateOf(false) }
    var finished by remember(node.id) { mutableStateOf(false) }

    fun commit() {
        if (finished) return
        finished = true
        onCommit(value.text)
    }

    LaunchedEffect(node.id) { focusRequester.requestFocus() }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = textStyle.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .border(2.dp, colors.primary, RoundedCornerShape(4.dp))
            .background(colors.surface, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focus ->
                if (focus.isFocused) hadFocus = true
                else if (hadFocus) commit()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        commit()
                        true
                    }
                    Key.Escape -> {
                        if (!finished) {
                            finished = true
                            onCancel()
                        }
                        true
                    }
                    else -> false
                }
            }
    )
}

/**
 * Thumbnail-Rasteransicht: Ordner oben als kompakte Kacheln, darunter Dateien mit Live-Vorschau.
 */
@Composable
fun FileGridView(
    nodes: List<VfsNode>,
    selectedIds: Set<String>,
    leadSelectionId: String?,
    renamingId: String?,
    areaState: FileAreaState,
    callbacks: FileItemCallbacks,
    containerModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val folders = nodes.filter { it.isDirectory }
    val files = nodes.filterNot { it.isDirectory }
    val gridState = rememberLazyGridState()
    DisposableEffect(areaState, gridState) {
        areaState.scrollByPx = { delta -> gridState.scrollBy(delta) }
        onDispose { areaState.scrollByPx = null }
    }

    LaunchedEffect(leadSelectionId, nodes) {
        val folderIndex = folders.indexOfFirst { it.id == leadSelectionId }
        val fileIndex = files.indexOfFirst { it.id == leadSelectionId }
        val gridIndex = when {
            folderIndex >= 0 -> 1 + folderIndex
            fileIndex >= 0 -> (if (folders.isNotEmpty()) 1 + folders.size else 0) + 1 + fileIndex
            else -> return@LaunchedEffect
        }
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == gridIndex }
        val viewportEnd = gridState.layoutInfo.viewportEndOffset
        if (info == null || info.offset.y < 0 || info.offset.y + info.size.height > viewportEnd) {
            gridState.animateScrollToItem(gridIndex)
        }
    }

    Box(modifier = containerModifier.then(modifier).fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 172.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header_folders") { GridSectionHeader("Ordner") }
                items(folders, key = { it.id }) { node ->
                    FolderTile(
                        node = node,
                        isSelected = node.id in selectedIds,
                        isRenaming = node.id == renamingId,
                        areaState = areaState,
                        callbacks = callbacks
                    )
                }
            }
            if (files.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header_files") { GridSectionHeader("Dateien") }
                items(files, key = { it.id }) { node ->
                    FileTile(
                        node = node,
                        isSelected = node.id in selectedIds,
                        isRenaming = node.id == renamingId,
                        areaState = areaState,
                        callbacks = callbacks
                    )
                }
            }
        }
    }
}

@Composable
private fun GridSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun FolderTile(
    node: VfsNode,
    isSelected: Boolean,
    isRenaming: Boolean,
    areaState: FileAreaState,
    callbacks: FileItemCallbacks
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .fileItem(node, isRenaming, callbacks, areaState, shape)
            .background(if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        if (isRenaming) {
            InlineRenameField(
                node = node,
                textStyle = MaterialTheme.typography.bodyMedium,
                onCommit = { callbacks.onRenameCommit(node, it) },
                onCancel = callbacks.onRenameCancel,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            NodeBadges(node)
        }
        IconButton(
            onClick = { callbacks.onToggleStarred(node) },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = if (node.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (node.isStarred) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                tint = if (node.isStarred) Color(0xFFF9AB00) else colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = { callbacks.onContextMenu(node) },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "Kontextmenü öffnen",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun FileTile(
    node: VfsNode,
    isSelected: Boolean,
    isRenaming: Boolean,
    areaState: FileAreaState,
    callbacks: FileItemCallbacks
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fileItem(node, isRenaming, callbacks, areaState, shape)
            .background(if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh)
            .padding(8.dp)
    ) {
        Box {
            FileThumbnail(
                node = node,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.82f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { callbacks.onToggleStarred(node) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (node.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (node.isStarred) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                        tint = if (node.isStarred) Color(0xFFF9AB00) else colors.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(
                    onClick = { callbacks.onContextMenu(node) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Kontextmenü öffnen",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            if (isRenaming) {
                InlineRenameField(
                    node = node,
                    textStyle = MaterialTheme.typography.bodySmall,
                    onCommit = { callbacks.onRenameCommit(node, it) },
                    onCancel = callbacks.onRenameCancel,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                NodeBadges(node)
            }
        }
    }
}

/**
 * Live-Vorschau: Bilder & SVGs (Coil), Video-Einzelbild (Coil Video), erste PDF-Seite (PdfRenderer),
 * sonst großes Typ-Icon. Schlägt das Dekodieren fehl, bleibt das Icon darunter sichtbar.
 */
@Composable
fun FileThumbnail(node: VfsNode, modifier: Modifier = Modifier, renderWidth: Dp = 320.dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.background(colors.surfaceContainerLowest),
        contentAlignment = Alignment.Center
    ) {
        Icon(node.icon(), contentDescription = null, tint = node.iconTint(), modifier = Modifier.size(48.dp))
        when {
            node.isRemote || node.isSaf || node.isGoogleDriveApi -> Unit
            node.isImage || node.isVideo -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(File(node.path))
                        .build(),
                    contentDescription = node.name,
                    contentScale = if (node.extension == "svg") ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (node.isVideo) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                }
            }
            node.isPdf -> {
                val widthPx = with(LocalDensity.current) { renderWidth.roundToPx() }
                val page = rememberPdfFirstPage(node.path, widthPx)
                if (page != null) {
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = node.name,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyFolderState(
    isSearch: Boolean,
    isLoading: Boolean,
    isStarred: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when {
                        isSearch -> Icons.Outlined.SearchOff
                        isStarred -> Icons.Outlined.StarOutline
                        else -> Icons.Outlined.FolderOpen
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        isSearch -> "Keine passenden Ergebnisse"
                        isStarred -> "Noch keine Favoriten vorhanden"
                        else -> "Dieser Ordner ist leer"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (isStarred) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tippe bei einem Ordner oder einer Datei auf das Stern-Symbol (☆), öffne das Kontextmenü (⋮ / Rechtsklick / langes Drücken) oder ziehe einen Ordner per Drag & Drop auf „Favoriten“.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
