package de.chromefiles.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.UNCONNECTED_DRIVE_PATH
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.UNCONNECTED_LINUX_PATH
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_DRIVE
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_LINUX
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_MY_FILES
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_RECENT
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_STARRED
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_TRASH
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.components.ChromeOsTopBar
import de.chromefiles.app.ui.components.EmptyFolderState
import de.chromefiles.app.ui.components.FileAreaState
import de.chromefiles.app.ui.components.FileGridView
import de.chromefiles.app.ui.components.FileItemCallbacks
import de.chromefiles.app.ui.components.FileListView
import de.chromefiles.app.ui.components.RecentFilterChipRow
import de.chromefiles.app.ui.components.SearchFilterChipRow
import de.chromefiles.app.ui.components.fileAreaPointerHandling
import de.chromefiles.app.ui.dnd.ActiveDragState
import de.chromefiles.app.ui.dnd.DropPayload
import de.chromefiles.app.ui.dnd.buildDragTransferData
import de.chromefiles.app.ui.dnd.fileDropTarget
import de.chromefiles.app.ui.dnd.handleDrop
import de.chromefiles.app.ui.viewmodel.ChromeFilesUiState
import de.chromefiles.app.ui.viewmodel.ChromeFilesViewModel
import de.chromefiles.app.ui.viewmodel.DropDestination
import de.chromefiles.app.ui.viewmodel.FileViewMode
import de.chromefiles.app.ui.viewmodel.PaneController

/**
 * Eine vollständige Dateiansicht (Kopfzeile, Filterchips, Schnell-Drop-Leiste, Auswahl-Aktionsleiste,
 * Liste/Raster) – ein Tab oder eine Hälfte der Dual-Pane-Ansicht.
 */
@Composable
fun PaneView(
    viewModel: ChromeFilesViewModel,
    controller: PaneController,
    ui: ChromeFilesUiState,
    areaState: FileAreaState,
    showFocusBorder: Boolean,
    onRequestKeyboardFocus: () -> Unit,
    onRequestAllFilesAccess: () -> Unit = {},
    onPickSafFolder: (asLinuxContainer: Boolean) -> Unit = {},
    onPickGoogleDriveSaf: () -> Unit = {},
    onPickSystemGoogleAccount: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pane by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paneId = controller.paneId
    val isFocused = ui.focusedPaneId == paneId

    fun destinationFor(node: VfsNode) = DropDestination(
        path = node.path,
        volumeId = if (node.volumeId in ChromeFilesViewModel.VIRTUAL_IDS) controller.resolveVolumeIdForPath(node.path) else node.volumeId
    )

    val requestKeyboardFocus by rememberUpdatedState(onRequestKeyboardFocus)
    val callbacks = remember(controller, viewModel) {
        FileItemCallbacks(
            onClick = { node, ctrl, shift, isTouch ->
                viewModel.focusPane(paneId)
                if (controller.current.renamingId != null) requestKeyboardFocus()
                if (isTouch && !ctrl && !shift) {
                    if (controller.current.selectedIds.isNotEmpty()) {
                        controller.onNodeClicked(node, ctrl = true, shift = false)
                    } else if (node.isDirectory) {
                        viewModel.openNode(paneId, node)
                    } else {
                        controller.onNodeClicked(node, ctrl = false, shift = false)
                    }
                } else {
                    controller.onNodeClicked(node, ctrl, shift)
                }
            },
            onOpen = { node -> viewModel.openNode(paneId, node) },
            onContextMenu = { node -> viewModel.showContextMenu(paneId, node) },
            onToggleStarred = { node -> viewModel.toggleStarred(node) },
            onPrepareDrag = { node ->
                if (controller.current.isTrash) {
                    null
                } else {
                    controller.ensureSelected(node)
                    val nodes = controller.current.selectedNodes.ifEmpty { listOf(node) }
                    buildDragTransferData(context, nodes)
                }
            },
            canDropOnFolder = { node ->
                !controller.current.isTrash && !node.isReadOnly && viewModel.canDropOn(destinationFor(node))
            },
            onDropOnFolder = { node, payload ->
                val containsTarget = payload is DropPayload.Internal && payload.nodes.any {
                    it.path == node.path || node.path.startsWith(it.path + "/")
                }
                if (containsTarget) false else viewModel.handleDrop(payload, destinationFor(node))
            },
            onRenameCommit = { node, name -> controller.commitRename(node, name) },
            onRenameCancel = { controller.cancelRename() }
        )
    }

    val currentDestination = DropDestination(pane.currentPath, pane.currentVolumeId)
    val containerModifier = Modifier
        .fileDropTarget(
            enabled = !pane.isShowingSearchResults && viewModel.canDropOn(currentDestination),
            shape = RoundedCornerShape(12.dp)
        ) { payload -> viewModel.handleDrop(payload, currentDestination) }
        .fileAreaPointerHandling(
            state = areaState,
            selectedIds = { controller.current.selectedIds },
            onPointerDown = {
                viewModel.focusPane(paneId)
                if (controller.current.renamingId == null) onRequestKeyboardFocus()
            },
            onMarqueeSelection = { base, hits -> controller.setMarqueeSelection(base, hits) },
            onBackgroundClick = {
                if (controller.current.renamingId != null) {
                    onRequestKeyboardFocus()
                } else {
                    controller.clearSelection()
                }
            },
            onBackgroundContextMenu = { viewModel.showContextMenu(paneId, null) }
        )

    val needsLocalPermissionPrompt = !ui.hasAllFilesAccess &&
        (pane.currentVolumeId == VOLUME_MY_FILES || pane.currentVolumeId == VOLUME_RECENT)
    val needsDriveConnectPrompt = pane.currentVolumeId == VOLUME_DRIVE &&
        (!ui.isDriveConnected || pane.currentPath == UNCONNECTED_DRIVE_PATH)
    val needsLinuxConnectPrompt = pane.currentVolumeId == VOLUME_LINUX &&
        pane.currentPath == UNCONNECTED_LINUX_PATH

    Surface(
        modifier = modifier
            .border(
                width = 2.dp,
                color = if (showFocusBorder && isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .pointerInput(paneId) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) viewModel.focusPane(paneId)
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            ChromeOsTopBar(
                breadcrumbs = pane.breadcrumbs,
                canGoBack = pane.canGoBack,
                canGoForward = pane.canGoForward,
                isSearchOpen = pane.isSearchOpen,
                searchQuery = pane.searchFilter.query,
                viewMode = pane.viewMode,
                sortSpec = pane.sortSpec,
                showHiddenFiles = ui.showHiddenFiles,
                isDetailsPanelVisible = ui.isDetailsPanelVisible,
                canCreateFolder = pane.isWritableLocation && !needsLocalPermissionPrompt && !needsDriveConnectPrompt && !needsLinuxConnectPrompt,
                isTrash = pane.isTrash,
                hasSelection = pane.selectedIds.isNotEmpty(),
                onToggleSidebar = viewModel::toggleSidebar,
                onBack = controller::goBack,
                onForward = controller::goForward,
                onBreadcrumbClick = { viewModel.navigatePane(paneId, it.path, it.volumeId) },
                onOpenSearch = controller::openSearch,
                onCloseSearch = controller::closeSearch,
                onSearchQueryChange = controller::onSearchQueryChanged,
                onToggleViewMode = controller::toggleViewMode,
                onSortColumn = controller::onSortColumnClicked,
                onSortDirection = controller::setSortDirection,
                onToggleDetails = viewModel::toggleDetailsPanel,
                onNewFolder = { viewModel.showNewFolderDialog(paneId) },
                onToggleHiddenFiles = viewModel::toggleShowHiddenFiles,
                onRefresh = controller::reload,
                onRestoreFromTrash = { viewModel.restoreSelectionFromTrash(paneId) },
                onEmptyTrash = viewModel::emptyTrash,
                showSidebarToggle = paneId != ui.splitPaneId,
                hasAllFilesAccess = ui.hasAllFilesAccess,
                isUsingDeviceStorage = ui.isUsingDeviceStorage,
                isDriveConnected = ui.isDriveConnected,
                onRequestAllFilesAccess = onRequestAllFilesAccess,
                onToggleDeviceStorage = viewModel::toggleDeviceStorageMode,
                onPickSafFolder = onPickSafFolder,
                onOpenDriveAuth = viewModel::showDriveAuthDialog,
                canDropOnBreadcrumb = { segment ->
                    segment.path != pane.currentPath && viewModel.canDropOn(DropDestination(segment.path, segment.volumeId))
                },
                onBreadcrumbDrop = { segment, payload ->
                    viewModel.handleDrop(payload, DropDestination(segment.path, segment.volumeId))
                }
            )

            // Drag & Drop Schnell-Ablageleiste (immer sichtbar während eines Drags – auch auf Smartphones!)
            AnimatedVisibility(visible = ActiveDragState.nodes.isNotEmpty()) {
                val parentBreadcrumb = pane.breadcrumbs.getOrNull(pane.breadcrumbs.lastIndex - 1)
                QuickDropZonesBar(
                    parentSegment = parentBreadcrumb,
                    onDropToStarred = { payload ->
                        viewModel.handleDrop(payload, DropDestination("chromeos://starred", VOLUME_STARRED))
                    },
                    onDropToParent = { segment, payload ->
                        viewModel.handleDrop(payload, DropDestination(segment.path, segment.volumeId))
                    },
                    onDropToTrash = { payload ->
                        viewModel.handleDrop(payload, DropDestination("chromeos://trash", VOLUME_TRASH))
                    }
                )
            }

            // Kontext-Aktionsleiste bei ausgewählten Elementen (1-Tipp für Favorit, Kopieren, Komprimieren, Entpacken, Menü)
            AnimatedVisibility(visible = pane.selectedIds.isNotEmpty() && !pane.isTrash) {
                val selectedNodes = pane.selectedNodes
                val allStarred = selectedNodes.isNotEmpty() && selectedNodes.all { it.isStarred }
                val singleArchive = selectedNodes.singleOrNull()?.takeIf { it.isArchive }
                SelectionActionBar(
                    selectedCount = pane.selectedIds.size,
                    allStarred = allStarred,
                    canWriteHere = pane.isWritableLocation,
                    hasClipboard = ui.clipboard != null,
                    singleArchiveNode = singleArchive,
                    onToggleStarred = { viewModel.toggleStarredSelection(paneId) },
                    onCopy = { viewModel.copySelection(paneId, isCut = false) },
                    onCut = { viewModel.copySelection(paneId, isCut = true) },
                    onPaste = { viewModel.paste(paneId) },
                    onCompress = { viewModel.showCompressDialog(paneId) },
                    onExtractArchive = { archive -> viewModel.extractArchive(paneId, archive) },
                    onRename = { viewModel.startRename(paneId) },
                    onDelete = { viewModel.trashSelection(paneId) },
                    onOpenContextMenu = {
                        viewModel.showContextMenu(paneId, pane.leadNode ?: selectedNodes.firstOrNull())
                    },
                    onClearSelection = controller::clearSelection
                )
            }

            // Zwischenablage-Einfügeleiste, wenn Elemente kopiert/ausgeschnitten wurden
            AnimatedVisibility(
                visible = ui.clipboard != null && pane.selectedIds.isEmpty() && pane.isWritableLocation && !pane.isTrash
            ) {
                ui.clipboard?.let { clip ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (clip.isCut) "${clip.nodes.size} Element(e) zum Verschieben bereit"
                            else "${clip.nodes.size} Element(e) in der Zwischenablage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.paste(paneId) }) {
                            Text("Hier einfügen")
                        }
                    }
                }
            }

            if (pane.isSearchOpen) {
                SearchFilterChipRow(
                    filter = pane.searchFilter,
                    onLocation = controller::onSearchLocationChanged,
                    onModified = controller::onSearchModifiedChanged,
                    onFileType = controller::onSearchFileTypeChanged
                )
            } else if (pane.isRecent) {
                RecentFilterChipRow(
                    selected = pane.recentFilter,
                    onSelect = controller::onRecentFilterChanged
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    needsLocalPermissionPrompt -> Box(modifier = Modifier.fillMaxSize()) {
                        StoragePermissionOnboardingCard(
                            onGrantAllFilesAccess = onRequestAllFilesAccess,
                            onPickSafFolder = { onPickSafFolder(false) }
                        )
                    }
                    needsDriveConnectPrompt -> Box(modifier = Modifier.fillMaxSize()) {
                        GoogleDriveOnboardingCard(
                            onPickSystemGoogleAccount = onPickSystemGoogleAccount,
                            onPickGoogleDriveSaf = onPickGoogleDriveSaf,
                            onOpenDriveAuthDialog = viewModel::showDriveAuthDialog
                        )
                    }
                    needsLinuxConnectPrompt -> Box(modifier = Modifier.fillMaxSize()) {
                        LinuxOnboardingCard(
                            onPickLinuxSafFolder = { onPickSafFolder(true) }
                        )
                    }
                    pane.nodes.isEmpty() -> Box(modifier = containerModifier.fillMaxSize()) {
                        EmptyFolderState(
                            isSearch = pane.isShowingSearchResults,
                            isLoading = pane.isLoading,
                            isStarred = pane.currentVolumeId == VOLUME_STARRED
                        )
                    }
                    pane.viewMode == FileViewMode.LIST -> FileListView(
                        nodes = pane.nodes,
                        selectedIds = pane.selectedIds,
                        leadSelectionId = pane.leadSelectionId,
                        renamingId = pane.renamingId,
                        sortSpec = pane.sortSpec,
                        columnWidths = pane.columnWidths,
                        areaState = areaState,
                        callbacks = callbacks,
                        onSortColumn = controller::onSortColumnClicked,
                        onResizeColumn = controller::resizeColumnBy,
                        containerModifier = containerModifier
                    )
                    else -> FileGridView(
                        nodes = pane.nodes,
                        selectedIds = pane.selectedIds,
                        leadSelectionId = pane.leadSelectionId,
                        renamingId = pane.renamingId,
                        areaState = areaState,
                        callbacks = callbacks,
                        containerModifier = containerModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickDropZonesBar(
    parentSegment: BreadcrumbSegment?,
    onDropToStarred: (DropPayload) -> Boolean,
    onDropToParent: (BreadcrumbSegment, DropPayload) -> Boolean,
    onDropToTrash: (DropPayload) -> Boolean
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val chipShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(chipShape)
                .fileDropTarget(enabled = true, shape = chipShape, onDrop = onDropToStarred)
                .border(1.dp, colors.primary.copy(alpha = 0.5f), chipShape)
                .background(colors.primaryContainer.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFF9AB00), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Zu Favoriten (Drop)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        if (parentSegment != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(chipShape)
                    .fileDropTarget(
                        enabled = true,
                        shape = chipShape,
                        onDrop = { payload -> onDropToParent(parentSegment, payload) }
                    )
                    .border(1.dp, colors.secondary.copy(alpha = 0.5f), chipShape)
                    .background(colors.secondaryContainer.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Nach „${parentSegment.title}“",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(chipShape)
                .fileDropTarget(enabled = true, shape = chipShape, onDrop = onDropToTrash)
                .border(1.dp, colors.error.copy(alpha = 0.5f), chipShape)
                .background(colors.errorContainer.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Papierkorb (Drop)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    allStarred: Boolean,
    canWriteHere: Boolean,
    hasClipboard: Boolean,
    singleArchiveNode: VfsNode?,
    onToggleStarred: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onCompress: () -> Unit,
    onExtractArchive: (VfsNode) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenContextMenu: () -> Unit,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IconButton(onClick = onClearSelection, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Auswahl aufheben", modifier = Modifier.size(18.dp))
        }
        Text(
            text = "$selectedCount ausgewählt",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 4.dp)
        )
        AssistChip(
            onClick = onToggleStarred,
            label = { Text(if (allStarred) "Favorit entfernen" else "Als Favorit") },
            leadingIcon = {
                Icon(
                    imageVector = if (allStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = if (allStarred) Color(0xFFF9AB00) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        if (singleArchiveNode != null && canWriteHere) {
            AssistChip(
                onClick = { onExtractArchive(singleArchiveNode) },
                label = { Text("Hier entpacken") },
                leadingIcon = {
                    Icon(Icons.Outlined.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        if (canWriteHere) {
            AssistChip(
                onClick = onCompress,
                label = { Text("Komprimieren (ZIP/RAR)") },
                leadingIcon = {
                    Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        AssistChip(
            onClick = onCopy,
            label = { Text("Kopieren") },
            leadingIcon = {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        AssistChip(
            onClick = onCut,
            label = { Text("Ausschneiden") },
            leadingIcon = {
                Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        if (hasClipboard && canWriteHere) {
            AssistChip(
                onClick = onPaste,
                label = { Text("Einfügen") },
                leadingIcon = {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        AssistChip(
            onClick = onRename,
            label = { Text("Umbenennen") },
            leadingIcon = {
                Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        AssistChip(
            onClick = onDelete,
            label = { Text("Löschen") },
            leadingIcon = {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        AssistChip(
            onClick = onOpenContextMenu,
            label = { Text("Mehr") },
            leadingIcon = {
                Icon(Icons.Outlined.MoreVert, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
    }
}

@Composable
private fun StoragePermissionOnboardingCard(
    onGrantAllFilesAccess: () -> Unit,
    onPickSafFolder: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "Lokalen Gerätespeicher freigeben",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Damit Files Pro Ihre echten lokalen Ordner und Dateien (/storage/emulated/0 – Download, DCIM, Documents, Pictures, Music usw.) anzeigen und verwalten kann, aktivieren Sie bitte die Android-Berechtigung „Zugriff auf alle Dateien“.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onGrantAllFilesAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zugriff auf alle Dateien jetzt aktivieren")
            }
            OutlinedButton(
                onClick = onPickSafFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Einzelnen Ordner über System-Picker öffnen")
            }
        }
    }
}

@Composable
private fun GoogleDriveOnboardingCard(
    onPickSystemGoogleAccount: () -> Unit,
    onPickGoogleDriveSaf: () -> Unit,
    onOpenDriveAuthDialog: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "Mit Ihrem Google Drive verbinden",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Verbinden Sie Ihr Google-Konto per 1-Klick oder binden Sie Ihr Google Drive direkt über das Android-Dateisystem ein, um sofort auf Ihre Ordner und Dateien zuzugreifen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onPickSystemGoogleAccount,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Google-Konto vom Gerät wählen (1-Klick)")
            }
            FilledTonalButton(
                onClick = onPickGoogleDriveSaf,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Google Drive über Android-Dateisystem einbinden")
            }
            OutlinedButton(
                onClick = onOpenDriveAuthDialog,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mit OAuth 2.0 / Browser-Token anmelden")
            }
        }
    }
}

@Composable
private fun LinuxOnboardingCard(
    onPickLinuxSafFolder: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "Linux-Dateien (Crostini / Termux) einbinden",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Wählen Sie Ihren ChromeOS Linux-Container („Linux-Dateien“) oder Ihr Termux-Verzeichnis im System-Auswahldialog aus, um es dauerhaft einzubinden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onPickLinuxSafFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Linux-Ordner jetzt auswählen")
            }
        }
    }
}


