package de.chromefiles.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.chromefiles.app.model.FileOperationStatus
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolumeType
import de.chromefiles.app.ui.components.BatchRenameDialog
import de.chromefiles.app.ui.components.ConflictDialog
import de.chromefiles.app.ui.components.DetailsPanel
import de.chromefiles.app.ui.components.FileAreaState
import de.chromefiles.app.ui.components.FileContextMenu
import de.chromefiles.app.ui.components.GoogleDriveAuthDialog
import de.chromefiles.app.ui.components.NavigationSidebar
import de.chromefiles.app.ui.components.NetworkConnectDialog
import de.chromefiles.app.ui.components.OperationsCenter
import de.chromefiles.app.ui.components.PaneResizeHandle
import de.chromefiles.app.ui.components.TabInfo
import de.chromefiles.app.ui.components.TabStrip
import de.chromefiles.app.ui.dnd.handleDrop
import de.chromefiles.app.ui.quickview.QuickViewOverlay
import de.chromefiles.app.ui.viewmodel.ChromeFilesViewModel
import de.chromefiles.app.ui.viewmodel.DropDestination
import de.chromefiles.app.ui.viewmodel.FileViewMode
import de.chromefiles.app.ui.viewmodel.PaneState
import java.io.File
import kotlin.math.roundToInt

private val COMPACT_WINDOW_WIDTH = 840.dp
private val DETAILS_PANEL_WIDTH = 320.dp

/**
 * 3-Spalten-Layout der ChromeOS Files App (Seitenleiste | Tabs + 1–2 Dateiansichten | Info-Spalte)
 * mit globaler Tastatursteuerung, Kontextmenü an der Mausposition und Dialogen.
 */
@Composable
fun ChromeFilesScreen(
    incomingUri: Uri? = null,
    onConsumeIncomingUri: () -> Unit = {},
    viewModel: ChromeFilesViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val rootFocus = remember { FocusRequester() }
    var rootIsFocused by remember { mutableStateOf(false) }
    var lastPressPosition by remember { mutableStateOf(Offset.Zero) }
    val areaStates = remember { mutableMapOf<String, FileAreaState>() }
    fun areaState(paneId: String) = areaStates.getOrPut(paneId) { FileAreaState() }

    LaunchedEffect(incomingUri, ui.focusedPaneId) {
        if (incomingUri != null && ui.focusedPaneId.isNotEmpty()) {
            viewModel.handleIncomingIntentUri(incomingUri)
            onConsumeIncomingUri()
        }
    }

    var pendingSafAsLinux by remember { mutableStateOf(false) }
    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onSafTreeSelected(treeUri, pendingSafAsLinux)
        }
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onAllFilesAccessResult()
    }
    fun launchAllFilesAccessSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { allFilesAccessLauncher.launch(intent) }.onFailure {
            runCatching {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
    fun launchSafFolderPicker(asLinuxContainer: Boolean) {
        pendingSafAsLinux = asLinuxContainer
        runCatching { safTreeLauncher.launch(null) }
    }

    val focusedController = viewModel.pane(ui.focusedPaneId)
    val focusedPane: PaneState? = key(ui.focusedPaneId) {
        focusedController?.state?.collectAsStateWithLifecycle()?.value
    }

    LaunchedEffect(Unit) {
        viewModel.openFileEvents.collect { node -> openWithExternalApp(context, node) }
    }
    LaunchedEffect(ui.userMessage) {
        ui.userMessage?.let {
            viewModel.consumeUserMessage()
            snackbarHostState.showSnackbar(it)
        }
    }
    // Tastaturfokus zurück auf die Dateiansicht, sobald Suche, Umbenennen, Dialoge oder Menüs geschlossen sind.
    val textInputOrOverlayActive = focusedPane?.isSearchOpen == true ||
        focusedPane?.renamingId != null ||
        ui.newFolderPaneId != null ||
        ui.pendingPermanentDelete != null ||
        ui.pendingArchivePasswordNode != null ||
        ui.pendingBatchRenameNodes != null ||
        ui.networkDialog != null ||
        ui.driveAuthDialog != null ||
        ui.contextMenu != null
    LaunchedEffect(textInputOrOverlayActive, ui.focusedPaneId) {
        if (!textInputOrOverlayActive) runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(ui.tabPaneIds, ui.splitPaneId) {
        val alive = ui.tabPaneIds.toSet() + listOfNotNull(ui.splitPaneId)
        areaStates.keys.retainAll(alive)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val state = viewModel.uiState.value
        val paneId = state.focusedPaneId
        val controller = viewModel.pane(paneId) ?: return false
        val pane = controller.current
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed

        // QuickView offen: Leertaste/Esc schließen, Pfeiltasten blättern, alles andere wird abgefangen.
        if (state.quickView != null) {
            when (event.key) {
                Key.Spacebar, Key.Escape -> viewModel.closeQuickView()
                Key.DirectionLeft, Key.DirectionUp -> viewModel.quickViewStep(-1)
                Key.DirectionRight, Key.DirectionDown -> viewModel.quickViewStep(1)
                Key.Enter, Key.NumPadEnter -> state.quickView.current.let { viewModel.openWith(it) }
                else -> Unit
            }
            return true
        }

        // Fenster-/Tab-Kürzel funktionieren auch, während ein Textfeld den Fokus hat.
        if (ctrl) {
            when (event.key) {
                Key.T -> { viewModel.newTab(); return true }
                Key.N -> {
                    if (shift) viewModel.showNewFolderDialog(paneId) else viewModel.toggleSplitView()
                    return true
                }
                Key.W -> { viewModel.closePane(paneId); return true }
                Key.Tab -> {
                    val tabs = state.tabPaneIds
                    if (tabs.size > 1) {
                        val index = tabs.indexOf(state.activeTabPaneId)
                        val next = (index + if (shift) -1 else 1).mod(tabs.size)
                        viewModel.activateTab(tabs[next])
                    }
                    return true
                }
                Key.F -> { controller.openSearch(); return true }
                Key.Period -> { viewModel.toggleShowHiddenFiles(); return true }
                else -> Unit
            }
        }
        if (!rootIsFocused) return false

        if (ctrl) {
            return when (event.key) {
                Key.A -> { controller.selectAll(); true }
                Key.C -> { viewModel.copySelection(paneId, isCut = false); true }
                Key.X -> { viewModel.copySelection(paneId, isCut = true); true }
                Key.V -> { viewModel.paste(paneId); true }
                Key.E -> { viewModel.showNewFolderDialog(paneId); true }
                Key.Enter, Key.NumPadEnter -> { viewModel.startRename(paneId); true }
                Key.R -> { controller.reload(); true }
                else -> false
            }
        }
        if (alt) {
            return when (event.key) {
                Key.DirectionLeft -> { controller.goBack(); true }
                Key.DirectionRight -> { controller.goForward(); true }
                Key.DirectionUp -> { controller.goUp(); true }
                Key.Backspace -> { viewModel.trashSelection(paneId); true }
                else -> false
            }
        }

        val isGrid = pane.viewMode == FileViewMode.GRID
        return when (event.key) {
            Key.Delete -> {
                if (shift) viewModel.requestPermanentDelete(paneId) else viewModel.trashSelection(paneId)
                true
            }
            Key.Enter, Key.NumPadEnter -> { viewModel.openSelection(paneId); true }
            Key.Spacebar -> { viewModel.openQuickView(paneId); true }
            Key.Escape -> {
                if (pane.isSearchOpen) controller.closeSearch() else controller.clearSelection()
                true
            }
            Key.F5 -> { controller.reload(); true }
            Key.F10 -> {
                if (shift) { viewModel.showContextMenu(paneId, pane.leadNode); true } else false
            }
            Key.Menu -> { viewModel.showContextMenu(paneId, pane.leadNode); true }
            Key.DirectionDown, Key.DirectionUp -> {
                val up = event.key == Key.DirectionUp
                val lead = pane.leadSelectionId
                val target = if (isGrid && lead != null) areaState(paneId).verticalNeighbor(lead, up) else null
                when {
                    target != null -> controller.moveSelectionTo(target, shift)
                    isGrid && lead != null -> Unit
                    else -> controller.moveSelectionBy(if (up) -1 else 1, shift)
                }
                true
            }
            Key.DirectionLeft, Key.DirectionRight -> {
                if (isGrid) {
                    controller.moveSelectionBy(if (event.key == Key.DirectionLeft) -1 else 1, shift)
                    true
                } else {
                    false
                }
            }
            Key.MoveHome -> { pane.nodes.firstOrNull()?.let { controller.moveSelectionTo(it.id, shift) }; true }
            Key.MoveEnd -> { pane.nodes.lastOrNull()?.let { controller.moveSelectionTo(it.id, shift) }; true }
            else -> false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { lastPressPosition = it.position }
                        }
                    }
                }
            }
            .focusRequester(rootFocus)
            .onFocusChanged { rootIsFocused = it.isFocused }
            .onKeyEvent(::handleKey)
            .focusable()
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) { padding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val isCompact = maxWidth < COMPACT_WINDOW_WIDTH
                LaunchedEffect(isCompact) { viewModel.setSidebarCollapsed(isCompact) }

                Row(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = !ui.isSidebarCollapsed,
                        enter = expandHorizontally(),
                        exit = shrinkHorizontally()
                    ) {
                        Row {
                            NavigationSidebar(
                                nodes = ui.sidebarNodes,
                                volumes = ui.volumes,
                                currentPath = focusedPane?.currentPath.orEmpty(),
                                currentVolumeId = focusedPane?.currentVolumeId.orEmpty(),
                                onNodeClick = viewModel::onSidebarNodeClicked,
                                onToggleExpand = viewModel::toggleTreeNode,
                                onEject = viewModel::ejectVolume,
                                modifier = Modifier.width(ui.sidebarWidthDp.dp),
                                canDrop = { node ->
                                    node.volumeType != VfsVolumeType.MOUNTED_ARCHIVE &&
                                        viewModel.canDropOn(DropDestination(node.path, node.volumeId))
                                },
                                onDrop = { node, payload ->
                                    viewModel.handleDrop(payload, DropDestination(node.path, node.volumeId))
                                },
                                onAddNetwork = viewModel::showNetworkDialog,
                                onAddSafFolder = { launchSafFolderPicker(asLinuxContainer = false) },
                                onConnectDrive = viewModel::showDriveAuthDialog,
                                isDriveConnected = ui.isDriveConnected
                            )
                            PaneResizeHandle(onDragDp = viewModel::resizeSidebarBy)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 8.dp, bottom = 8.dp, end = if (ui.isDetailsPanelVisible) 0.dp else 8.dp)
                    ) {
                        if (ui.tabPaneIds.size > 1) {
                            val tabs = ui.tabPaneIds.map { id ->
                                val title = key(id) { viewModel.pane(id)?.state?.collectAsStateWithLifecycle()?.value?.title }
                                TabInfo(id, title.orEmpty())
                            }
                            TabStrip(
                                tabs = tabs,
                                activePaneId = ui.activeTabPaneId,
                                onSelect = viewModel::activateTab,
                                onClose = viewModel::closePane,
                                onNewTab = { viewModel.newTab() }
                            )
                        }
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val visiblePaneIds = listOfNotNull(ui.activeTabPaneId.takeIf { it.isNotEmpty() }, ui.splitPaneId)
                            visiblePaneIds.forEachIndexed { index, id ->
                                val controller = viewModel.pane(id) ?: return@forEachIndexed
                                if (index > 0) Spacer(Modifier.width(8.dp))
                                key(id) {
                                    PaneView(
                                        viewModel = viewModel,
                                        controller = controller,
                                        ui = ui,
                                        areaState = areaState(id),
                                        showFocusBorder = ui.splitPaneId != null,
                                        onRequestKeyboardFocus = { runCatching { rootFocus.requestFocus() } },
                                        onRequestAllFilesAccess = ::launchAllFilesAccessSettings,
                                        onPickSafFolder = ::launchSafFolderPicker,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = ui.isDetailsPanelVisible,
                        enter = expandHorizontally(expandFrom = Alignment.Start),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start)
                    ) {
                        DetailsPanel(
                            detailedNode = ui.detailedNode,
                            selectedNodes = focusedPane?.selectedNodes.orEmpty(),
                            currentFolderTitle = focusedPane?.title.orEmpty(),
                            totalItemsInFolder = focusedPane?.nodes?.size ?: 0,
                            onClose = viewModel::toggleDetailsPanel,
                            onToggleOffline = viewModel::toggleOfflinePinned,
                            onToggleStarred = viewModel::toggleStarred,
                            onToggleColorTag = viewModel::toggleColorTag,
                            onLoadLocalFile = viewModel::localFileFor,
                            modifier = Modifier.width(DETAILS_PANEL_WIDTH)
                        )
                    }
                }
            }
        }

        // Kontextmenü an der letzten Zeigerposition (Rechtsklick / Zwei-Finger-Tipp / langes Drücken).
        val menu = ui.contextMenu
        val menuPane = menu?.let { viewModel.pane(it.paneId)?.current }
        Box(
            modifier = Modifier
                .offset { IntOffset(lastPressPosition.x.roundToInt(), lastPressPosition.y.roundToInt()) }
                .size(1.dp)
        ) {
            if (menu != null && menuPane != null) {
                FileContextMenu(
                    expanded = true,
                    pane = menuPane,
                    target = menu.target,
                    viewModel = viewModel,
                    showHiddenFiles = ui.showHiddenFiles,
                    hasClipboard = ui.clipboard != null,
                    onDismiss = viewModel::dismissContextMenu
                )
            }
        }

        // Schwebendes Operations-Center unten rechts (Kopieren/Verschieben/ZIP mit MB/s, Restzeit, Pause, Abbrechen).
        OperationsCenter(
            operations = operations,
            onPause = viewModel::pauseOperation,
            onResume = viewModel::resumeOperation,
            onCancel = viewModel::cancelOperation,
            onDismissCompleted = viewModel::dismissCompletedOperations,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
        )

        ui.quickView?.let { qv ->
            QuickViewOverlay(
                state = qv,
                viewModel = viewModel,
                isEditRunning = ui.isImageEditRunning,
                onClose = viewModel::closeQuickView
            )
        }
    }

    val conflict = operations.firstOrNull { it.status == FileOperationStatus.WAITING_FOR_CONFLICT_RESOLUTION }
    if (conflict != null) {
        ConflictDialog(
            operation = conflict,
            onResolve = { resolution, applyToAll -> viewModel.resolveConflict(conflict.operationId, resolution, applyToAll) },
            onCancelOperation = { viewModel.cancelOperation(conflict.operationId) }
        )
    }
    ui.networkDialog?.let { dialogState ->
        NetworkConnectDialog(
            state = dialogState,
            onConnect = viewModel::connectNetworkShare,
            onDismiss = viewModel::dismissNetworkDialog
        )
    }
    ui.driveAuthDialog?.let { driveState ->
        GoogleDriveAuthDialog(
            state = driveState,
            initialClientId = viewModel.savedDriveClientId(),
            onBuildPkceUrl = viewModel::buildDrivePkceUrl,
            onConnectWithCode = viewModel::connectDriveWithCode,
            onConnectWithToken = viewModel::connectDriveWithToken,
            onDisconnect = viewModel::disconnectDrive,
            onDismiss = viewModel::dismissDriveAuthDialog
        )
    }
    ui.pendingBatchRenameNodes?.let { (_, nodes) ->
        BatchRenameDialog(
            nodes = nodes,
            onConfirm = viewModel::confirmBatchRename,
            onDismiss = viewModel::dismissBatchRename
        )
    }
    ui.pendingArchivePasswordNode?.let { (paneId, archiveNode) ->
        ArchivePasswordDialog(
            archiveName = archiveNode.name,
            onConfirm = { password -> viewModel.mountArchive(paneId, archiveNode, password) },
            onDismiss = viewModel::dismissArchivePasswordDialog
        )
    }

    if (ui.newFolderPaneId != null) {
        NewFolderDialog(
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissNewFolderDialog
        )
    }
    ui.pendingPermanentDelete?.let { nodes ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPermanentDelete,
            title = { Text("Endgültig löschen?") },
            text = {
                Text(
                    if (nodes.size == 1) "„${nodes.first().name}“ wird endgültig gelöscht. Dies kann nicht rückgängig gemacht werden."
                    else "${nodes.size} Elemente werden endgültig gelöscht. Dies kann nicht rückgängig gemacht werden."
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmPermanentDelete) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPermanentDelete) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun ArchivePasswordDialog(
    archiveName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passwortgeschütztes Archiv") },
        text = {
            Column {
                Text(
                    "„$archiveName“ ist verschlüsselt. Bitte geben Sie das Passwort zum Einhängen ein:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Archiv-Passwort") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onConfirm(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text("Einhängen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun NewFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val defaultName = "Neuer Ordner"
    var value by remember { mutableStateOf(TextFieldValue(defaultName, TextRange(0, defaultName.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Ordner") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Ordnername") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onConfirm(value.text) }),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

private fun openWithExternalApp(context: Context, node: VfsNode) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(node.path))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, node.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Öffnen mit …"))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "Keine App zum Öffnen von „${node.name}“ gefunden.", android.widget.Toast.LENGTH_LONG).show()
    }
}
