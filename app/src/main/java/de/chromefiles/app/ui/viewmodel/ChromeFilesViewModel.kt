package de.chromefiles.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.chromefiles.app.data.clipboard.SystemClipboardBridge
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_DRIVE
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_DRIVE_OFFLINE
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_LINUX
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_MY_FILES
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_RECENT
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_STARRED
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_TRASH
import android.graphics.RectF
import de.chromefiles.app.data.archive.ArchiveFormat
import de.chromefiles.app.data.archive.ArchivePasswordRequiredException
import de.chromefiles.app.data.media.ImageEditor
import de.chromefiles.app.data.remote.RemoteConnectionConfig
import de.chromefiles.app.model.ConflictResolution
import de.chromefiles.app.model.FileOperationProgress
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolume
import de.chromefiles.app.model.VfsVolumeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class FileViewMode { LIST, GRID }

/** Ein sichtbarer Eintrag im (abgeflachten) Navigationsbaum der linken Seitenleiste. */
data class SidebarTreeNode(
    val key: String,
    val title: String,
    val subtitle: String?,
    val path: String,
    val volumeId: String,
    val volumeType: VfsVolumeType,
    val depth: Int,
    val isExpandable: Boolean,
    val isExpanded: Boolean,
    val isEjectable: Boolean
)

/** Breiten (dp) der per Maus verstellbaren Tabellenspalten. Die Namensspalte füllt den Rest. */
data class ColumnWidths(
    val size: Float = 110f,
    val type: Float = 170f,
    val date: Float = 190f
)

data class ClipboardState(val nodes: List<VfsNode>, val isCut: Boolean)

/** Offenes Rechtsklick-Menü; [target] == null bedeutet Klick auf freie Fläche. */
data class ContextMenuState(val paneId: String, val target: VfsNode?)

/** Dialog zum Komprimieren ausgewählter Elemente in ZIP / RAR / 7Z / TAR.GZ. */
data class CompressDialogState(val paneId: String, val nodes: List<VfsNode>)

/** Ablageziel für Drag & Drop (Ordnerzeile, Seitenleiste, Breadcrumb oder Pane-Hintergrund). */
data class DropDestination(val path: String, val volumeId: String)

/** Offenes QuickView-Overlay (Leertaste): blättert mit ←/→ durch alle Dateien des Ordners. */
data class QuickViewState(val paneId: String, val items: List<VfsNode>, val index: Int, val version: Int = 0) {
    val current: VfsNode get() = items[index]
}

/** Dialog „Netzwerkfreigabe verbinden“. */
data class NetworkDialogState(val isConnecting: Boolean = false, val error: String? = null)

/** Dialog „Google Drive verbinden (REST API v3)“. */
data class DriveAuthDialogState(
    val isConnecting: Boolean = false,
    val connectedEmail: String? = null,
    val error: String? = null
)

data class ChromeFilesUiState(
    val volumes: List<VfsVolume> = emptyList(),
    val sidebarNodes: List<SidebarTreeNode> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val isUsingDeviceStorage: Boolean = false,
    val isDriveConnected: Boolean = false,
    val isDetailsPanelVisible: Boolean = false,
    val isSidebarCollapsed: Boolean = false,
    val sidebarWidthDp: Float = 264f,
    val tabPaneIds: List<String> = emptyList(),
    val activeTabPaneId: String = "",
    val splitPaneId: String? = null,
    val focusedPaneId: String = "",
    val detailedNode: VfsNode? = null,
    val clipboard: ClipboardState? = null,
    val contextMenu: ContextMenuState? = null,
    val compressDialog: CompressDialogState? = null,
    val newFolderPaneId: String? = null,
    val pendingPermanentDelete: List<VfsNode>? = null,
    val pendingArchivePasswordNode: Pair<String, VfsNode>? = null,
    val pendingArchiveExtractToFolder: Boolean = false,
    val pendingBatchRenameNodes: Pair<String, List<VfsNode>>? = null,
    val quickView: QuickViewState? = null,
    val networkDialog: NetworkDialogState? = null,
    val driveAuthDialog: DriveAuthDialogState? = null,
    val isImageEditRunning: Boolean = false,
    val userMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChromeFilesViewModel @Inject constructor(
    private val repository: VirtualFileSystemRepository,
    private val clipboardBridge: SystemClipboardBridge,
    private val imageEditor: ImageEditor
) : ViewModel() {

    /** Laufende und abgeschlossene Dateioperationen für das Operations-Center unten rechts. */
    val operations: StateFlow<List<FileOperationProgress>> = repository.operationsFlow

    private val _uiState = MutableStateFlow(ChromeFilesUiState())
    val uiState: StateFlow<ChromeFilesUiState> = _uiState.asStateFlow()

    private val _openFileEvents = MutableSharedFlow<VfsNode>(extraBufferCapacity = 4)
    val openFileEvents: SharedFlow<VfsNode> = _openFileEvents.asSharedFlow()

    private val showHidden = MutableStateFlow(false)
    private val focusedPaneIdFlow = MutableStateFlow("")
    private val panes = mutableMapOf<String, PaneController>()
    private val expandedKeys = MutableStateFlow(setOf(VOLUME_MY_FILES))

    init {
        viewModelScope.launch {
            repository.initialize()
            val first = createPane()
            first.navigateTo(repository.myFilesRoot.absolutePath, VOLUME_MY_FILES)
            _uiState.update { it.copy(tabPaneIds = listOf(first.paneId), activeTabPaneId = first.paneId) }
            focusPane(first.paneId)
        }
        viewModelScope.launch {
            repository.volumesFlow.collect { volumes ->
                val driveConnected = repository.isDriveConnected()
                _uiState.update {
                    it.copy(
                        volumes = volumes,
                        hasAllFilesAccess = repository.hasAllFilesAccess(),
                        isUsingDeviceStorage = repository.isUsingDeviceStorage(),
                        isDriveConnected = driveConnected
                    )
                }
                // Ausgeworfene Volumes: betroffene Ansichten zurück zu „Meine Dateien“.
                panes.values.forEach { pane ->
                    val vol = pane.current.currentVolumeId
                    val exists = vol in VIRTUAL_IDS || vol == VOLUME_DRIVE || vol == VOLUME_MY_FILES || volumes.any { it.id == vol }
                    if (pane.current.currentPath.isNotEmpty() && !exists) {
                        pane.navigateTo(repository.myFilesRoot.absolutePath, VOLUME_MY_FILES)
                    }
                }
                rebuildSidebar()
            }
        }
        // Info-Spalte: Metadaten der Hauptauswahl in der fokussierten Ansicht nachladen.
        viewModelScope.launch {
            combine(
                focusedPaneIdFlow.flatMapLatest { id -> panes[id]?.state ?: flowOf(null) },
                _uiState.distinctUntilChangedBy { it.isDetailsPanelVisible }
            ) { paneState, ui ->
                val lead = paneState?.leadNode?.takeIf { paneState.selectedIds.size == 1 }
                Pair(lead, ui.isDetailsPanelVisible)
            }.distinctUntilChanged().collectLatest { (lead, visible) ->
                _uiState.update { it.copy(detailedNode = lead) }
                if (visible && lead != null) {
                    val detailed = repository.loadDetailedMetadata(lead)
                    _uiState.update { st -> if (st.detailedNode?.id == lead.id) st.copy(detailedNode = detailed) else st }
                }
            }
        }
    }

    private fun <T> StateFlow<T>.distinctUntilChangedBy(selector: (T) -> Any?) =
        this.distinctUntilChanged { a, b -> selector(a) == selector(b) }

    // ================================================================== Panes, Tabs, Split-View

    fun pane(id: String): PaneController? = panes[id]

    private val focused: PaneController? get() = panes[_uiState.value.focusedPaneId]

    private fun createPane(template: PaneController? = null): PaneController {
        val controller = PaneController(
            paneId = UUID.randomUUID().toString(),
            repository = repository,
            parentScope = viewModelScope,
            showHiddenFiles = showHidden,
            volumesProvider = { _uiState.value.volumes },
            onMessage = ::postMessage,
            initialViewMode = template?.current?.viewMode ?: FileViewMode.LIST,
            initialSortSpec = template?.current?.sortSpec ?: de.chromefiles.app.model.SortSpec()
        )
        panes[controller.paneId] = controller
        return controller
    }

    fun focusPane(id: String) {
        if (id !in panes || _uiState.value.focusedPaneId == id) return
        _uiState.update { it.copy(focusedPaneId = id) }
        focusedPaneIdFlow.value = id
        rebuildSidebar()
    }

    /** Strg + T: neuer Tab am aktuellen Ort (oder am angegebenen Ordner). */
    fun newTab(path: String? = null, volumeId: String? = null) {
        val source = focused
        val pane = createPane(source)
        pane.navigateTo(
            path ?: source?.current?.currentPath ?: repository.myFilesRoot.absolutePath,
            volumeId ?: source?.current?.currentVolumeId ?: VOLUME_MY_FILES
        )
        _uiState.update { it.copy(tabPaneIds = it.tabPaneIds + pane.paneId, activeTabPaneId = pane.paneId) }
        focusPane(pane.paneId)
    }

    fun activateTab(paneId: String) {
        _uiState.update { it.copy(activeTabPaneId = paneId) }
        focusPane(paneId)
    }

    /** Strg + W: Tab bzw. rechte Hälfte der Split-Ansicht schließen. */
    fun closePane(paneId: String) {
        val s = _uiState.value
        if (paneId == s.splitPaneId) {
            toggleSplitView()
            return
        }
        if (s.tabPaneIds.size <= 1) return
        val index = s.tabPaneIds.indexOf(paneId)
        val remaining = s.tabPaneIds - paneId
        val newActive = if (s.activeTabPaneId == paneId) remaining[(index - 1).coerceAtLeast(0)] else s.activeTabPaneId
        panes.remove(paneId)?.dispose()
        _uiState.update { it.copy(tabPaneIds = remaining, activeTabPaneId = newActive) }
        if (s.focusedPaneId == paneId) {
            _uiState.update { it.copy(focusedPaneId = "") }
            focusPane(newActive)
        }
    }

    /** Strg + N: Dual-Pane-Split-Ansicht ein-/ausschalten. */
    fun toggleSplitView() {
        val s = _uiState.value
        val existing = s.splitPaneId
        if (existing != null) {
            panes.remove(existing)?.dispose()
            _uiState.update { it.copy(splitPaneId = null, focusedPaneId = "") }
            focusPane(s.activeTabPaneId)
        } else {
            val source = focused
            val pane = createPane(source)
            pane.navigateTo(
                source?.current?.currentPath ?: repository.myFilesRoot.absolutePath,
                source?.current?.currentVolumeId ?: VOLUME_MY_FILES
            )
            _uiState.update { it.copy(splitPaneId = pane.paneId) }
            focusPane(pane.paneId)
        }
    }

    // ================================================================== Navigation

    fun onSidebarNodeClicked(node: SidebarTreeNode) {
        focused?.navigateTo(node.path, node.volumeId)
        rebuildSidebar()
    }

    fun navigatePane(paneId: String, path: String, volumeId: String) {
        panes[paneId]?.navigateTo(path, volumeId)
        rebuildSidebar()
    }

    /** Doppelklick / Enter: Ordner öffnen, Archive einhängen, sonstige Dateien extern öffnen. */
    fun openNode(paneId: String, node: VfsNode) {
        val pane = panes[paneId] ?: return
        when {
            node.isDirectory -> {
                val vol = if (node.volumeId in VIRTUAL_IDS) pane.resolveVolumeIdForPath(node.path) else node.volumeId
                pane.navigateTo(node.path, vol)
                rebuildSidebar()
            }
            node.isArchive -> mountArchive(paneId, node)
            else -> emitOpenFile(node)
        }
    }

    /** Remote-, SAF- und Google-Drive-Dateien werden vor dem externen Öffnen in den Cache geladen. */
    private fun emitOpenFile(node: VfsNode) {
        if (!node.isRemote && !node.isSaf && !node.isGoogleDriveApi) {
            _openFileEvents.tryEmit(node)
            return
        }
        viewModelScope.launch {
            postMessage("„${node.name}“ wird vorbereitet …")
            repository.materialize(node)
                .onSuccess { local -> _openFileEvents.tryEmit(node.copy(path = local.absolutePath)) }
                .onFailure { postMessage("Datei konnte nicht geöffnet werden: ${it.message}") }
        }
    }

    /** Lokale Datei für Vorschau/Bearbeitung (lädt Remote-Dateien bei Bedarf in den Cache). */
    suspend fun localFileFor(node: VfsNode): Result<File> = repository.materialize(node)

    // ================================================================== QuickView (Leertaste)

    fun openQuickView(paneId: String) {
        val pane = panes[paneId]?.current ?: return
        val files = pane.nodes.filterNot { it.isDirectory }
        val start = pane.leadNode?.takeIf { !it.isDirectory } ?: pane.selectedNodes.firstOrNull { !it.isDirectory }
        if (start == null || files.isEmpty()) {
            postMessage("Für QuickView bitte eine Datei auswählen.")
            return
        }
        _uiState.update { it.copy(quickView = QuickViewState(paneId, files, files.indexOfFirst { f -> f.id == start.id }.coerceAtLeast(0))) }
    }

    fun closeQuickView() = _uiState.update { it.copy(quickView = null) }

    fun quickViewStep(delta: Int) {
        val qv = _uiState.value.quickView ?: return
        val next = (qv.index + delta).coerceIn(0, qv.items.lastIndex)
        if (next == qv.index) return
        _uiState.update { it.copy(quickView = qv.copy(index = next)) }
        panes[qv.paneId]?.moveSelectionTo(qv.items[next].id, extend = false)
    }

    fun rotateQuickViewImage(clockwise: Boolean) = editQuickViewImage(replaceCurrent = true) { file ->
        imageEditor.rotate(file, clockwise)
    }

    fun cropQuickViewImage(normalizedRect: RectF) = editQuickViewImage(replaceCurrent = false) { file ->
        imageEditor.crop(file, normalizedRect)
    }

    fun scaleQuickViewImage(width: Int, height: Int) = editQuickViewImage(replaceCurrent = false) { file ->
        imageEditor.scale(file, width, height)
    }

    fun imageSizeFor(node: VfsNode): Pair<Int, Int>? =
        if (node.isRemote || node.isSaf || node.isGoogleDriveApi) null else imageEditor.orientedSize(File(node.path))

    fun canEditImage(node: VfsNode): Boolean =
        node.isImage && !node.isRemote && !node.isSaf && !node.isGoogleDriveApi && !node.isReadOnly && imageEditor.isEditable(node.extension)

    /** Speichert Änderungen aus dem integrierten Text-/Code-Editor im QuickView zurück in die Datei. */
    fun saveQuickViewText(newText: String) {
        val qv = _uiState.value.quickView ?: return
        val node = qv.current
        if (node.isReadOnly) {
            postMessage("„${node.name}“ ist schreibgeschützt.")
            return
        }
        viewModelScope.launch {
            repository.saveTextContent(node, newText)
                .onSuccess { updated ->
                    panes[qv.paneId]?.reload()
                    _uiState.update { st ->
                        val cur = st.quickView ?: return@update st
                        val items = cur.items.toMutableList().apply { set(cur.index, updated) }
                        st.copy(quickView = cur.copy(items = items, version = cur.version + 1))
                    }
                    postMessage("„${node.name}“ wurde gespeichert.")
                }
                .onFailure { err ->
                    postMessage("Speichern fehlgeschlagen: ${err.message}")
                }
        }
    }

    private fun editQuickViewImage(replaceCurrent: Boolean, action: suspend (File) -> Result<File>) {
        val qv = _uiState.value.quickView ?: return
        val node = qv.current
        if (!canEditImage(node)) {
            postMessage("Dieses Bild kann hier nicht bearbeitet werden.")
            return
        }
        _uiState.update { it.copy(isImageEditRunning = true) }
        viewModelScope.launch {
            action(File(node.path))
                .onSuccess { result ->
                    val paneController = panes[qv.paneId]
                    paneController?.reload()
                    if (replaceCurrent) {
                        _uiState.update { st ->
                            st.quickView?.let { cur -> st.copy(quickView = cur.copy(version = cur.version + 1)) } ?: st
                        }
                        postMessage("„${node.name}“ wurde gedreht.")
                    } else {
                        val created = repository.nodeForLocalFile(result, node.volumeId)
                        _uiState.update { st ->
                            val cur = st.quickView ?: return@update st
                            val items = cur.items.toMutableList().apply { add(cur.index + 1, created) }
                            st.copy(quickView = cur.copy(items = items, index = cur.index + 1, version = cur.version + 1))
                        }
                        postMessage("„${result.name}“ wurde gespeichert.")
                    }
                }
                .onFailure { postMessage(it.message ?: "Bearbeitung fehlgeschlagen.") }
            _uiState.update { it.copy(isImageEditRunning = false) }
        }
    }

    // ================================================================== Operations-Center

    fun pauseOperation(id: String) = repository.pauseOperation(id)
    fun resumeOperation(id: String) = repository.resumeOperation(id)
    fun cancelOperation(id: String) = repository.cancelOperation(id)
    fun dismissCompletedOperations() = repository.dismissCompletedOperations()
    fun resolveConflict(id: String, resolution: ConflictResolution, applyToAll: Boolean) =
        repository.resolveConflict(id, resolution, applyToAll)

    // ================================================================== Netzwerkfreigaben

    fun showNetworkDialog() = _uiState.update { it.copy(networkDialog = NetworkDialogState()) }

    fun dismissNetworkDialog() {
        if (_uiState.value.networkDialog?.isConnecting == true) return
        _uiState.update { it.copy(networkDialog = null) }
    }

    fun connectNetworkShare(title: String, config: RemoteConnectionConfig) {
        _uiState.update { it.copy(networkDialog = NetworkDialogState(isConnecting = true)) }
        viewModelScope.launch {
            repository.mountRemoteShare(title, config)
                .onSuccess { volume ->
                    _uiState.update { it.copy(networkDialog = null) }
                    postMessage("„${volume.title}“ ist verbunden.")
                    focused?.navigateTo(volume.rootPath, volume.id)
                    rebuildSidebar()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(networkDialog = NetworkDialogState(error = err.message ?: "Verbindung fehlgeschlagen.")) }
                }
        }
    }

    // ================================================================== SAF (Linux-Crostini / USB / Google Drive) & Gerätespeicher

    fun onSafTreeSelected(treeUri: Uri, asLinuxContainer: Boolean, asGoogleDrive: Boolean = false) {
        val currentVol = focused?.current?.currentVolumeId
        val effectiveAsDrive = asGoogleDrive || (!asLinuxContainer && currentVol == VOLUME_DRIVE)
        val effectiveAsLinux = asLinuxContainer || (!effectiveAsDrive && currentVol == VOLUME_LINUX)
        viewModelScope.launch {
            repository.safVolumes.registerTreeUri(treeUri, effectiveAsLinux, effectiveAsDrive)
                .onSuccess { volume ->
                    repository.refreshVolumes()
                    postMessage("„${volume.title}“ wurde erfolgreich eingebunden.")
                    focused?.navigateTo(volume.rootPath, volume.id)
                    reloadAllPanes()
                    rebuildSidebar()
                }
                .onFailure { err ->
                    postMessage("Ordner konnte nicht eingebunden werden: ${err.message}")
                }
        }
    }

    /** Wird beim Zurückkehren aus den Android-Systemeinstellungen (ON_RESUME) automatisch aufgerufen. */
    fun refreshPermissionsState() {
        viewModelScope.launch {
            val wasGranted = _uiState.value.hasAllFilesAccess
            val nowGranted = repository.hasAllFilesAccess()
            if (wasGranted != nowGranted) {
                repository.refreshVolumes()
                reloadAllPanes()
                rebuildSidebar()
                if (nowGranted) {
                    postMessage("Lokaler Gerätespeicher freigegeben (${repository.myFilesRoot.absolutePath}).")
                }
            }
        }
    }

    fun onAllFilesAccessResult() {
        viewModelScope.launch {
            val granted = repository.hasAllFilesAccess()
            repository.refreshVolumes()
            reloadAllPanes()
            if (granted) {
                focused?.navigateTo(repository.myFilesRoot.absolutePath, VOLUME_MY_FILES)
                postMessage("Vollständiger Dateizugriff aktiviert (${repository.myFilesRoot.absolutePath}).")
            } else {
                postMessage("Bitte „Zugriff auf alle Dateien“ aktivieren, um das lokale Dateisystem anzuzeigen.")
            }
            rebuildSidebar()
        }
    }

    fun toggleDeviceStorageMode() {
        viewModelScope.launch {
            repository.refreshVolumes()
            focused?.navigateTo(repository.myFilesRoot.absolutePath, VOLUME_MY_FILES)
            rebuildSidebar()
        }
    }

    // ================================================================== Google Drive REST API v3 (OAuth 2.0 & Android System-Konto)

    fun savedDriveClientId(): String = repository.driveManager.savedClientId()

    fun buildDrivePkceUrl(clientId: String): String {
        val url = repository.driveManager.buildPkceAuthorizationUrl(clientId)
        viewModelScope.launch {
            val code = repository.driveManager.awaitLoopbackAuthCode()
            if (!code.isNullOrBlank() && _uiState.value.driveAuthDialog != null) {
                connectDriveWithCode(clientId, code)
            }
        }
        return url
    }

    fun showDriveAuthDialog() {
        val email = repository.driveManager.accountFlow.value?.email
        _uiState.update { it.copy(driveAuthDialog = DriveAuthDialogState(connectedEmail = email)) }
    }

    fun dismissDriveAuthDialog() {
        if (_uiState.value.driveAuthDialog?.isConnecting == true) return
        repository.driveManager.stopLoopbackListener()
        _uiState.update { it.copy(driveAuthDialog = null) }
    }

    fun connectDriveWithSystemAccountToken(accessToken: String) {
        viewModelScope.launch {
            repository.driveManager.connectWithAccessToken(accessToken, "", "")
                .onSuccess { info ->
                    repository.refreshVolumes()
                    _uiState.update { it.copy(driveAuthDialog = null) }
                    postMessage("Google Drive verbunden (${info.email}).")
                    focused?.navigateTo(de.chromefiles.app.data.drive.GoogleDriveManager.ROOT_MY_DRIVE, VOLUME_DRIVE)
                    rebuildSidebar()
                }
                .onFailure { err ->
                    postMessage("Google-Drive-Anmeldung fehlgeschlagen: ${err.message}")
                }
        }
    }

    fun connectDriveWithCode(clientId: String, codeOrUrl: String) {
        repository.driveManager.stopLoopbackListener()
        _uiState.update { it.copy(driveAuthDialog = DriveAuthDialogState(isConnecting = true)) }
        viewModelScope.launch {
            repository.driveManager.connectWithAuthCode(clientId, codeOrUrl)
                .onSuccess { info ->
                    repository.refreshVolumes()
                    _uiState.update { it.copy(driveAuthDialog = null) }
                    postMessage("Google Drive verbunden (${info.email}).")
                    focused?.navigateTo(de.chromefiles.app.data.drive.GoogleDriveManager.ROOT_MY_DRIVE, VOLUME_DRIVE)
                    rebuildSidebar()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(driveAuthDialog = DriveAuthDialogState(error = err.message ?: "Google-Drive-Anmeldung fehlgeschlagen."))
                    }
                }
        }
    }

    fun connectDriveWithToken(accessToken: String, refreshToken: String, clientId: String) {
        repository.driveManager.stopLoopbackListener()
        _uiState.update { it.copy(driveAuthDialog = DriveAuthDialogState(isConnecting = true)) }
        viewModelScope.launch {
            repository.driveManager.connectWithAccessToken(accessToken, refreshToken, clientId)
                .onSuccess { info ->
                    repository.refreshVolumes()
                    _uiState.update { it.copy(driveAuthDialog = null) }
                    postMessage("Google Drive verbunden (${info.email}).")
                    focused?.navigateTo(de.chromefiles.app.data.drive.GoogleDriveManager.ROOT_MY_DRIVE, VOLUME_DRIVE)
                    rebuildSidebar()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(driveAuthDialog = DriveAuthDialogState(error = err.message ?: "Google-Drive-Verbindung fehlgeschlagen."))
                    }
                }
        }
    }

    fun disconnectDrive() {
        viewModelScope.launch {
            repository.ejectVolume(VOLUME_DRIVE)
            _uiState.update { it.copy(driveAuthDialog = null) }
            postMessage("Google Drive wurde getrennt.")
            focused?.navigateTo(VirtualFileSystemRepository.UNCONNECTED_DRIVE_PATH, VOLUME_DRIVE)
            rebuildSidebar()
        }
    }

    fun openSelection(paneId: String) {
        val pane = panes[paneId] ?: return
        val selected = pane.current.selectedNodes
        when {
            selected.size == 1 -> openNode(paneId, selected.first())
            selected.isNotEmpty() -> selected.filterNot { it.isDirectory }.forEach { emitOpenFile(it) }
        }
    }

    fun openWith(node: VfsNode) {
        if (!node.isDirectory) emitOpenFile(node)
    }

    fun openInNewTab(paneId: String, node: VfsNode) {
        if (!node.isDirectory) return
        val vol = if (node.volumeId in VIRTUAL_IDS) panes[paneId]?.resolveVolumeIdForPath(node.path) ?: VOLUME_MY_FILES else node.volumeId
        newTab(node.path, vol)
    }

    fun mountArchive(paneId: String, node: VfsNode, password: String? = null) {
        _uiState.update { it.copy(pendingArchivePasswordNode = null, pendingArchiveExtractToFolder = false) }
        viewModelScope.launch {
            repository.mountArchiveNode(node, password?.toCharArray())
                .onSuccess { volume ->
                    postMessage("„${volume.title}“ wurde als Laufwerk eingehängt.")
                    panes[paneId]?.navigateTo(volume.rootPath, volume.id)
                }
                .onFailure { err ->
                    if (err is ArchivePasswordRequiredException || err.cause is ArchivePasswordRequiredException) {
                        _uiState.update {
                            it.copy(
                                pendingArchivePasswordNode = paneId to node,
                                pendingArchiveExtractToFolder = false
                            )
                        }
                        if (!password.isNullOrEmpty()) {
                            postMessage("Falsches Passwort für „${node.name}“.")
                        }
                    } else {
                        postMessage("Archiv konnte nicht eingehängt werden: ${err.message}")
                    }
                }
        }
    }

    /** Entpackt ein Archiv (.zip, .rar, .7z, .tar.gz) direkt in einen Unterordner im aktuellen Verzeichnis. */
    fun extractArchive(paneId: String, node: VfsNode, password: String? = null) {
        val pane = panes[paneId] ?: return
        _uiState.update { it.copy(pendingArchivePasswordNode = null, pendingArchiveExtractToFolder = false) }
        viewModelScope.launch {
            postMessage("„${node.name}“ wird entpackt …")
            repository.extractArchiveNode(
                archiveNode = node,
                destinationDirPath = pane.current.currentPath,
                destinationVolumeId = pane.current.currentVolumeId,
                password = password?.toCharArray()
            ).onSuccess { extractedFolder ->
                postMessage("„${node.name}“ wurde nach „${extractedFolder.name}“ entpackt.")
                pane.reload()
                pane.selectIds(setOf(extractedFolder.id))
                rebuildSidebar()
            }.onFailure { err ->
                if (err is ArchivePasswordRequiredException || err.cause is ArchivePasswordRequiredException) {
                    _uiState.update {
                        it.copy(
                            pendingArchivePasswordNode = paneId to node,
                            pendingArchiveExtractToFolder = true
                        )
                    }
                    if (!password.isNullOrEmpty()) {
                        postMessage("Falsches Passwort für „${node.name}“.")
                    }
                } else {
                    postMessage("Entpacken fehlgeschlagen: ${err.message}")
                }
            }
        }
    }

    fun submitArchivePassword(password: String) {
        val state = _uiState.value
        val (paneId, archiveNode) = state.pendingArchivePasswordNode ?: return
        if (state.pendingArchiveExtractToFolder) {
            extractArchive(paneId, archiveNode, password)
        } else {
            mountArchive(paneId, archiveNode, password)
        }
    }

    fun dismissArchivePasswordDialog() = _uiState.update {
        it.copy(pendingArchivePasswordNode = null, pendingArchiveExtractToFolder = false)
    }

    /** Verarbeitet eingehende Dateien/Archive (Intent.ACTION_VIEW), z. B. durch Doppelklick aus einer anderen App. */
    fun handleIncomingIntentUri(uri: Uri) {
        viewModelScope.launch {
            val targetPaneId = _uiState.value.focusedPaneId.ifEmpty { _uiState.value.activeTabPaneId }
            val downloadsDir = File(repository.myFilesRoot, "Downloads").apply { mkdirs() }
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val file = File(uri.path!!)
                if (file.exists()) {
                    val node = repository.nodeForLocalFile(file, VOLUME_MY_FILES)
                    if (node.isArchive && targetPaneId.isNotEmpty()) {
                        mountArchive(targetPaneId, node)
                    } else if (targetPaneId.isNotEmpty()) {
                        panes[targetPaneId]?.navigateTo(file.parentFile?.absolutePath ?: downloadsDir.absolutePath, VOLUME_MY_FILES)
                    }
                    return@launch
                }
            }
            repository.importContentUris(listOf(uri), downloadsDir.absolutePath, VOLUME_MY_FILES)
                .onSuccess {
                    val newest = downloadsDir.listFiles()?.maxByOrNull { it.lastModified() }
                    if (newest != null && targetPaneId.isNotEmpty()) {
                        val node = repository.nodeForLocalFile(newest, VOLUME_MY_FILES)
                        if (node.isArchive) {
                            mountArchive(targetPaneId, node)
                        } else {
                            panes[targetPaneId]?.navigateTo(downloadsDir.absolutePath, VOLUME_MY_FILES)
                            panes[targetPaneId]?.selectIds(setOf(node.id))
                        }
                    }
                }
                .onFailure { postMessage("Datei konnte nicht geöffnet werden: ${it.message}") }
        }
    }

    fun ejectVolume(volumeId: String) {
        viewModelScope.launch {
            val title = _uiState.value.volumes.firstOrNull { it.id == volumeId }?.title ?: "Laufwerk"
            repository.ejectVolume(volumeId)
            postMessage("„$title“ wurde ausgeworfen.")
        }
    }

    // ================================================================== Zwischenablage (Strg + C / X / V)

    fun copySelection(paneId: String, isCut: Boolean) {
        val pane = panes[paneId] ?: return
        val selected = pane.current.selectedNodes
        if (selected.isEmpty()) return
        if (isCut && (pane.current.isTrash || selected.any { it.isReadOnly })) {
            postMessage("Schreibgeschützte Elemente können nicht ausgeschnitten werden.")
            return
        }
        clipboardBridge.writeFiles(selected)
        _uiState.update { it.copy(clipboard = ClipboardState(selected, isCut)) }
        val verb = if (isCut) "ausgeschnitten" else "kopiert"
        postMessage(if (selected.size == 1) "„${selected.first().name}“ $verb." else "${selected.size} Elemente $verb.")
    }

    /** Einfügen in den aktuellen Ordner der Ansicht oder – bei Rechtsklick auf einen Ordner – in diesen. */
    fun paste(paneId: String, intoFolder: VfsNode? = null) {
        val pane = panes[paneId] ?: return
        val destination = when {
            intoFolder != null && intoFolder.isDirectory && !intoFolder.isReadOnly ->
                DropDestination(intoFolder.path, pane.resolveVolumeIdForPath(intoFolder.path))
            pane.current.isWritableLocation -> DropDestination(pane.current.currentPath, pane.current.currentVolumeId)
            else -> {
                postMessage("An diesem Ort kann nichts eingefügt werden.")
                return
            }
        }
        val clip = _uiState.value.clipboard
        if (clip != null && clipboardBridge.isOwnClipCurrent()) {
            transfer(clip.nodes, destination, isMove = clip.isCut)
            if (clip.isCut) _uiState.update { it.copy(clipboard = null) }
            return
        }
        val external = clipboardBridge.readExternalUris()
        if (external.isNotEmpty()) {
            importExternal(external, destination, onFinished = {})
        } else {
            postMessage("Die Zwischenablage enthält keine Dateien.")
        }
    }

    // ================================================================== Drag & Drop

    fun canDropOn(destination: DropDestination): Boolean =
        destination.volumeId != VOLUME_RECENT && !destination.volumeId.startsWith("archive_")

    /**
     * Interne Ablage: Papierkorb -> in Papierkorb, Favoriten -> markieren, Offline -> anheften,
     * sonst Ordner: gleiches Volume = Verschieben, anderes Volume (oder Archiv-Quelle) = Kopieren.
     */
    fun dropNodes(nodes: List<VfsNode>, destination: DropDestination): Boolean {
        if (nodes.isEmpty() || !canDropOn(destination)) return false
        when (destination.volumeId) {
            VOLUME_TRASH -> {
                moveToTrash(nodes)
                return true
            }
            VOLUME_STARRED -> {
                viewModelScope.launch {
                    val toStar = nodes.filterNot { it.isStarred }
                    if (toStar.isNotEmpty()) {
                        toStar.forEach { repository.toggleStarred(it) }
                    }
                    postMessage(
                        if (nodes.size == 1) "„${nodes.first().name}“ zu Favoriten hinzugefügt."
                        else "${nodes.size} Elemente zu Favoriten hinzugefügt."
                    )
                    reloadAllPanes()
                    rebuildSidebar()
                }
                return true
            }
            VOLUME_DRIVE_OFFLINE -> {
                viewModelScope.launch {
                    nodes.filterNot { it.isOfflinePinned || it.isDirectory }.forEach { repository.toggleOfflinePinned(it) }
                    postMessage("Als offline verfügbar markiert.")
                    reloadAllPanes()
                }
                return true
            }
        }
        val cleanDest = destination.path.trimEnd('/')
        val movable = nodes.filterNot { node ->
            node.parentPath.trimEnd('/') == cleanDest || node.path.trimEnd('/') == cleanDest
        }
        if (movable.isEmpty()) return false
        val sourceVolume = movable.first().let { n ->
            if (n.volumeId in VIRTUAL_IDS) focused?.resolveVolumeIdForPath(n.path) else n.volumeId
        }
        val isMove = sourceVolume == destination.volumeId && movable.none { it.isReadOnly }
        transfer(movable, destination, isMove)
        return true
    }

    fun importExternal(uris: List<Uri>, destination: DropDestination, onFinished: () -> Unit) {
        if (!canDropOn(destination) || destination.volumeId in VIRTUAL_IDS) {
            postMessage("Dateien können hier nicht abgelegt werden.")
            onFinished()
            return
        }
        viewModelScope.launch {
            repository.importContentUris(uris, destination.path, destination.volumeId)
                .onSuccess { postMessage(if (it == 1) "1 Datei importiert." else "$it Dateien importiert.") }
                .onFailure { postMessage("Import fehlgeschlagen: ${it.message}") }
            onFinished()
            reloadAllPanes()
        }
    }

    private fun transfer(nodes: List<VfsNode>, destination: DropDestination, isMove: Boolean) {
        viewModelScope.launch {
            repository.executeCopyOrMove(
                nodes = nodes,
                destinationDirPath = destination.path,
                destinationVolumeId = destination.volumeId,
                isMove = isMove,
                conflictResolution = null
            ).onSuccess { result ->
                val verb = if (isMove) "verschoben" else "kopiert"
                postMessage(if (result.size == 1) "„${result.first().name}“ $verb." else "${result.size} Elemente $verb.")
            }.onFailure {
                postMessage(it.message ?: "Dateioperation fehlgeschlagen.")
            }
            repository.refreshVolumes()
            reloadAllPanes()
        }
    }

    // ================================================================== Löschen, Umbenennen, Ordner, ZIP / RAR

    /** Entf / Alt + Backspace: in den Papierkorb (im Papierkorb selbst: endgültig löschen). */
    fun trashSelection(paneId: String) {
        val pane = panes[paneId] ?: return
        val selected = pane.current.selectedNodes
        if (selected.isEmpty()) return
        if (pane.current.isTrash) {
            requestPermanentDelete(paneId)
            return
        }
        if (selected.any { it.isRemote }) {
            // Netzwerkfreigaben haben keinen Papierkorb: wie in ChromeOS endgültig löschen (mit Rückfrage).
            requestPermanentDelete(paneId)
            return
        }
        moveToTrash(selected)
    }

    private fun moveToTrash(nodes: List<VfsNode>) {
        val deletable = nodes.filterNot { it.isReadOnly || it.volumeId == VOLUME_TRASH }
        if (deletable.isEmpty()) {
            postMessage("Schreibgeschützte Elemente können nicht gelöscht werden.")
            return
        }
        viewModelScope.launch {
            repository.moveToTrash(deletable)
                .onSuccess { postMessage(if (it == 1) "1 Element in den Papierkorb verschoben." else "$it Elemente in den Papierkorb verschoben.") }
                .onFailure { postMessage("Löschen fehlgeschlagen: ${it.message}") }
            reloadAllPanes()
        }
    }

    /** Umschalt + Entf: Bestätigungsdialog für endgültiges Löschen. */
    fun requestPermanentDelete(paneId: String) {
        val selected = panes[paneId]?.current?.selectedNodes.orEmpty().filterNot { it.isReadOnly }
        if (selected.isNotEmpty()) _uiState.update { it.copy(pendingPermanentDelete = selected) }
    }

    fun confirmPermanentDelete() {
        val nodes = _uiState.value.pendingPermanentDelete ?: return
        _uiState.update { it.copy(pendingPermanentDelete = null) }
        viewModelScope.launch {
            repository.deletePermanently(nodes)
                .onSuccess { postMessage(if (it == 1) "1 Element endgültig gelöscht." else "$it Elemente endgültig gelöscht.") }
                .onFailure { postMessage("Löschen fehlgeschlagen: ${it.message}") }
            reloadAllPanes()
        }
    }

    fun dismissPermanentDelete() = _uiState.update { it.copy(pendingPermanentDelete = null) }

    /** Strg + Enter: Inline-Umbenennen (1 Element) oder Mehrfach-Umbenennen-Dialog (> 1 Element) starten. */
    fun startRename(paneId: String) {
        val pane = panes[paneId] ?: return
        val editableSelected = pane.current.selectedNodes.filterNot { it.isReadOnly }
        if (editableSelected.size > 1 && !pane.current.isTrash) {
            _uiState.update { it.copy(pendingBatchRenameNodes = paneId to editableSelected) }
            return
        }
        if (!pane.startRename()) {
            postMessage("Bitte mindestens ein beschreibbares Element zum Umbenennen auswählen.")
        }
    }

    fun confirmBatchRename(renames: List<Pair<VfsNode, String>>) {
        val (paneId, _) = _uiState.value.pendingBatchRenameNodes ?: return
        _uiState.update { it.copy(pendingBatchRenameNodes = null) }
        viewModelScope.launch {
            var count = 0
            for ((node, newName) in renames) {
                repository.renameNode(node, newName).onSuccess { count++ }
            }
            postMessage("$count Element(e) umbenannt.")
            panes[paneId]?.reload()
            rebuildSidebar()
        }
    }

    fun dismissBatchRename() = _uiState.update { it.copy(pendingBatchRenameNodes = null) }

    fun showNewFolderDialog(paneId: String) {
        if (panes[paneId]?.current?.isWritableLocation == true) {
            _uiState.update { it.copy(newFolderPaneId = paneId) }
        } else {
            postMessage("An diesem Ort können keine Ordner erstellt werden.")
        }
    }

    fun dismissNewFolderDialog() = _uiState.update { it.copy(newFolderPaneId = null) }

    fun createFolder(name: String) {
        val paneId = _uiState.value.newFolderPaneId ?: return
        val pane = panes[paneId] ?: return
        _uiState.update { it.copy(newFolderPaneId = null) }
        viewModelScope.launch {
            repository.createDirectory(pane.current.currentPath, name, pane.current.currentVolumeId)
                .onSuccess { created ->
                    pane.selectIds(setOf(created.id))
                    pane.reload()
                    rebuildSidebar()
                }
                .onFailure { postMessage(it.message ?: "Ordner konnte nicht erstellt werden.") }
        }
    }

    fun showCompressDialog(paneId: String) {
        val pane = panes[paneId] ?: return
        val selected = pane.current.selectedNodes
        if (selected.isEmpty()) {
            postMessage("Bitte zuerst mindestens eine Datei oder einen Ordner auswählen.")
            return
        }
        if (!pane.current.isWritableLocation) {
            postMessage("An diesem Ort kann kein Archiv erstellt werden.")
            return
        }
        _uiState.update { it.copy(compressDialog = CompressDialogState(paneId, selected)) }
    }

    fun dismissCompressDialog() = _uiState.update { it.copy(compressDialog = null) }

    fun confirmCompress(archiveName: String, format: ArchiveFormat, password: String?) {
        val dialog = _uiState.value.compressDialog ?: return
        val pane = panes[dialog.paneId] ?: return
        _uiState.update { it.copy(compressDialog = null) }
        viewModelScope.launch {
            repository.compressSelectionToArchive(
                nodes = dialog.nodes,
                format = format,
                destinationName = archiveName.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() }?.toCharArray()
            ).onSuccess { archive ->
                postMessage("„${archive.name}“ wurde erstellt.")
                pane.selectIds(setOf(archive.id))
            }.onFailure {
                postMessage("Komprimieren fehlgeschlagen: ${it.message}")
            }
            pane.reload()
        }
    }

    fun compressSelection(paneId: String, format: ArchiveFormat = ArchiveFormat.ZIP) {
        val pane = panes[paneId] ?: return
        val selected = pane.current.selectedNodes
        if (selected.isEmpty()) return
        if (!pane.current.isWritableLocation) {
            postMessage("Hier kann kein Archiv erstellt werden.")
            return
        }
        viewModelScope.launch {
            repository.compressSelectionToArchive(selected, format)
                .onSuccess { archive ->
                    postMessage("„${archive.name}“ wurde erstellt.")
                    pane.selectIds(setOf(archive.id))
                }
                .onFailure { postMessage("Komprimieren fehlgeschlagen: ${it.message}") }
            pane.reload()
        }
    }

    fun toggleColorTag(node: VfsNode, tagId: String) {
        viewModelScope.launch {
            val updated = repository.toggleColorTag(node, tagId)
            _uiState.update { st ->
                if (st.detailedNode?.id == node.id) st.copy(detailedNode = updated) else st
            }
            reloadAllPanes()
        }
    }

    fun toggleStarredSelection(paneId: String) {
        val selected = panes[paneId]?.current?.selectedNodes.orEmpty()
        if (selected.isEmpty()) return
        val star = selected.any { !it.isStarred }
        viewModelScope.launch {
            selected.filter { it.isStarred != star }.forEach { repository.toggleStarred(it) }
            postMessage(if (star) "Zu Favoriten hinzugefügt." else "Aus Favoriten entfernt.")
            reloadAllPanes()
            rebuildSidebar()
        }
    }

    fun toggleStarred(node: VfsNode) {
        viewModelScope.launch {
            val updated = repository.toggleStarred(node)
            postMessage(if (updated.isStarred) "„${node.name}“ zu Favoriten hinzugefügt." else "„${node.name}“ aus Favoriten entfernt.")
            reloadAllPanes()
            rebuildSidebar()
        }
    }

    fun toggleOfflinePinned(node: VfsNode) {
        viewModelScope.launch {
            val updated = repository.toggleOfflinePinned(node)
            postMessage(
                if (updated.isOfflinePinned) "„${node.name}“ ist jetzt offline verfügbar."
                else "„${node.name}“ ist nicht mehr offline verfügbar."
            )
            reloadAllPanes()
        }
    }

    fun restoreSelectionFromTrash(paneId: String) {
        val selected = panes[paneId]?.current?.selectedNodes.orEmpty()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFromTrash(selected)
                .onSuccess { postMessage("$it Element(e) wiederhergestellt.") }
                .onFailure { postMessage("Wiederherstellen fehlgeschlagen: ${it.message}") }
            reloadAllPanes()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
                .onSuccess { postMessage("Papierkorb wurde geleert.") }
                .onFailure { postMessage("Papierkorb konnte nicht geleert werden: ${it.message}") }
            reloadAllPanes()
        }
    }

    // ================================================================== Kontextmenü

    fun showContextMenu(paneId: String, target: VfsNode?) {
        focusPane(paneId)
        val pane = panes[paneId] ?: return
        if (target != null) pane.ensureSelected(target) else pane.clearSelection()
        _uiState.update { it.copy(contextMenu = ContextMenuState(paneId, target)) }
    }

    fun dismissContextMenu() = _uiState.update { it.copy(contextMenu = null) }

    fun showDetailsFor(paneId: String) {
        focusPane(paneId)
        _uiState.update { it.copy(isDetailsPanelVisible = true) }
    }

    // ================================================================== Layout & globale Optionen

    fun toggleShowHiddenFiles() {
        showHidden.value = !showHidden.value
        _uiState.update { it.copy(showHiddenFiles = showHidden.value) }
        reloadAllPanes()
        rebuildSidebar()
    }

    fun toggleDetailsPanel() = _uiState.update { it.copy(isDetailsPanelVisible = !it.isDetailsPanelVisible) }

    fun toggleSidebar() = _uiState.update { it.copy(isSidebarCollapsed = !it.isSidebarCollapsed) }

    fun setSidebarCollapsed(collapsed: Boolean) = _uiState.update { it.copy(isSidebarCollapsed = collapsed) }

    fun resizeSidebarBy(deltaDp: Float) = _uiState.update {
        it.copy(sidebarWidthDp = (it.sidebarWidthDp + deltaDp).coerceIn(SIDEBAR_MIN_DP, SIDEBAR_MAX_DP))
    }

    fun toggleTreeNode(node: SidebarTreeNode) {
        expandedKeys.update { keys -> if (node.key in keys) keys - node.key else keys + node.key }
        rebuildSidebar()
    }

    fun consumeUserMessage() = _uiState.update { it.copy(userMessage = null) }

    fun postUserMessage(message: String) = postMessage(message)

    private fun postMessage(message: String) = _uiState.update { it.copy(userMessage = message) }

    private fun reloadAllPanes() = panes.values.forEach { it.reload() }

    // ================================================================== Seitenleisten-Baum

    private fun rebuildSidebar() {
        viewModelScope.launch {
            val s = _uiState.value
            val keys = expandedKeys.value
            val showHiddenFiles = showHidden.value
            val result = mutableListOf<SidebarTreeNode>()

            fun isPathExpandable(path: String): Boolean {
                if (path.startsWith("saf://") || path.startsWith("gdrive://")) return true
                if (path.startsWith("remote://") || path.startsWith("chromeos://")) return false
                val dir = File(path)
                return dir.listFiles()?.any { it.isDirectory && (showHiddenFiles || !it.name.startsWith(".")) } == true
            }

            suspend fun addDirectoryChildren(parentPath: String, volume: VfsVolume, depth: Int) {
                if (depth > 6) return
                repository.listSubdirectories(parentPath, volume.id, showHiddenFiles).forEach { dirNode ->
                    val expanded = dirNode.path in keys
                    val expandable = isPathExpandable(dirNode.path)
                    result += SidebarTreeNode(
                        key = dirNode.path, title = dirNode.name, subtitle = null, path = dirNode.path,
                        volumeId = volume.id, volumeType = volume.type, depth = depth,
                        isExpandable = expandable, isExpanded = expanded, isEjectable = false
                    )
                    if (expanded && expandable) addDirectoryChildren(dirNode.path, volume, depth + 1)
                }
            }

            for (volume in s.volumes) {
                val expanded = volume.id in keys
                result += SidebarTreeNode(
                    key = volume.id, title = volume.title, subtitle = volume.subtitle, path = volume.rootPath,
                    volumeId = volume.id, volumeType = volume.type, depth = 0,
                    isExpandable = volume.isExpandable, isExpanded = expanded, isEjectable = volume.isEjectable
                )
                if (!expanded || !volume.isExpandable) continue
                if (volume.children.isNotEmpty()) {
                    for (child in volume.children) {
                        val isOffline = child.id == VOLUME_DRIVE_OFFLINE
                        val childVolumeId = when {
                            isOffline -> child.id
                            volume.id == VOLUME_STARRED -> focused?.resolveVolumeIdForPath(child.rootPath) ?: VOLUME_MY_FILES
                            else -> volume.id
                        }
                        val childExpandable = !isOffline && isPathExpandable(child.rootPath)
                        val childExpanded = child.rootPath in keys && childExpandable
                        result += SidebarTreeNode(
                            key = child.rootPath, title = child.title, subtitle = null, path = child.rootPath,
                            volumeId = childVolumeId, volumeType = child.type, depth = 1,
                            isExpandable = childExpandable, isExpanded = childExpanded, isEjectable = false
                        )
                        if (childExpanded) addDirectoryChildren(child.rootPath, volume.copy(id = childVolumeId), 2)
                    }
                } else {
                    addDirectoryChildren(volume.rootPath, volume, 1)
                }
            }
            _uiState.update { it.copy(sidebarNodes = result) }
        }
    }

    override fun onCleared() {
        panes.values.forEach { it.dispose() }
        panes.clear()
    }

    companion object {
        const val SIDEBAR_MIN_DP = 200f
        const val SIDEBAR_MAX_DP = 440f
        val VIRTUAL_IDS = setOf(VOLUME_RECENT, VOLUME_STARRED, VOLUME_TRASH, VOLUME_DRIVE_OFFLINE)
    }
}
