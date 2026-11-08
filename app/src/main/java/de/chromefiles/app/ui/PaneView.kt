package de.chromefiles.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * Eine vollständige Dateiansicht (Kopfzeile, Filterchips, Liste/Raster) – ein Tab
 * oder eine Hälfte der Dual-Pane-Ansicht.
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
            onClick = { node, ctrl, shift ->
                viewModel.focusPane(paneId)
                if (controller.current.renamingId != null) requestKeyboardFocus()
                controller.onNodeClicked(node, ctrl, shift)
            },
            onOpen = { node -> viewModel.openNode(paneId, node) },
            onContextMenu = { node -> viewModel.showContextMenu(paneId, node) },
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
                canCreateFolder = pane.isWritableLocation,
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
                    pane.nodes.isEmpty() -> Box(modifier = containerModifier.fillMaxSize()) {
                        EmptyFolderState(isSearch = pane.isShowingSearchResults, isLoading = pane.isLoading)
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
