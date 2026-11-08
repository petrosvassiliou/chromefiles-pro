package de.chromefiles.app.ui.viewmodel

import de.chromefiles.app.data.vfs.VirtualFileSystemRepository
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_DRIVE_OFFLINE
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_MY_FILES
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_RECENT
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_STARRED
import de.chromefiles.app.data.vfs.VirtualFileSystemRepository.Companion.VOLUME_TRASH
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.SearchFileTypeFilter
import de.chromefiles.app.model.SearchFilterState
import de.chromefiles.app.model.SearchLocation
import de.chromefiles.app.model.SearchModifiedFilter
import de.chromefiles.app.model.SortColumn
import de.chromefiles.app.model.SortDirection
import de.chromefiles.app.model.SortSpec
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Zustand einer einzelnen Dateiansicht (ein Tab oder eine Hälfte der Dual-Pane-Ansicht). */
data class PaneState(
    val paneId: String,
    val currentVolumeId: String = VOLUME_MY_FILES,
    val currentPath: String = "",
    val breadcrumbs: List<BreadcrumbSegment> = emptyList(),
    val nodes: List<VfsNode> = emptyList(),
    val isLoading: Boolean = true,
    val viewMode: FileViewMode = FileViewMode.LIST,
    val sortSpec: SortSpec = SortSpec(),
    val isSearchOpen: Boolean = false,
    val searchFilter: SearchFilterState = SearchFilterState(),
    val recentFilter: SearchFileTypeFilter = SearchFileTypeFilter.ALL,
    val selectedIds: Set<String> = emptySet(),
    val leadSelectionId: String? = null,
    val renamingId: String? = null,
    val columnWidths: ColumnWidths = ColumnWidths(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
) {
    val selectedNodes: List<VfsNode> get() = nodes.filter { it.id in selectedIds }
    val leadNode: VfsNode? get() = nodes.firstOrNull { it.id == leadSelectionId }
    val title: String get() = breadcrumbs.lastOrNull()?.title.orEmpty()
    val isTrash: Boolean get() = currentVolumeId == VOLUME_TRASH
    val isRecent: Boolean get() = currentVolumeId == VOLUME_RECENT
    val isStarred: Boolean get() = currentVolumeId == VOLUME_STARRED
    val isShowingSearchResults: Boolean get() = isSearchOpen && searchFilter.isActive

    /** Ziel für Einfügen/Neuer Ordner: keine virtuellen Ansichten, keine schreibgeschützten Archive. */
    val isWritableLocation: Boolean
        get() = !isShowingSearchResults &&
            currentVolumeId !in setOf(VOLUME_RECENT, VOLUME_STARRED, VOLUME_TRASH, VOLUME_DRIVE_OFFLINE) &&
            !currentVolumeId.startsWith("archive_")
}

/**
 * Logik einer Dateiansicht: Navigation & Verlauf, Laden/Suchen, Sortierung, Auswahl
 * (Klick, Strg/Umschalt, Pfeiltasten, Gummiband) und Inline-Umbenennen.
 */
class PaneController(
    val paneId: String,
    private val repository: VirtualFileSystemRepository,
    parentScope: CoroutineScope,
    private val showHiddenFiles: StateFlow<Boolean>,
    private val volumesProvider: () -> List<VfsVolume>,
    private val onMessage: (String) -> Unit,
    initialViewMode: FileViewMode = FileViewMode.LIST,
    initialSortSpec: SortSpec = SortSpec()
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(
        PaneState(paneId = paneId, viewMode = initialViewMode, sortSpec = initialSortSpec)
    )
    val state: StateFlow<PaneState> = _state.asStateFlow()
    val current: PaneState get() = _state.value

    private data class Location(val path: String, val volumeId: String)

    private val history = mutableListOf<Location>()
    private var historyIndex = -1
    private var anchorId: String? = null
    private var reloadJob: Job? = null
    private var searchJob: Job? = null

    fun dispose() = job.cancel()

    // ------------------------------------------------------------------ Navigation

    fun navigateTo(path: String, volumeId: String, addToHistory: Boolean = true) {
        if (addToHistory) {
            val loc = Location(path, volumeId)
            if (historyIndex < 0 || history.getOrNull(historyIndex) != loc) {
                while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
                history.add(loc)
                historyIndex = history.lastIndex
            }
        }
        anchorId = null
        searchJob?.cancel()
        _state.update {
            it.copy(
                currentPath = path,
                currentVolumeId = volumeId,
                breadcrumbs = repository.buildBreadcrumbs(path, volumeId),
                selectedIds = emptySet(),
                leadSelectionId = null,
                renamingId = null,
                isSearchOpen = false,
                searchFilter = SearchFilterState(),
                canGoBack = historyIndex > 0,
                canGoForward = historyIndex < history.lastIndex
            )
        }
        reload()
    }

    fun goBack() {
        if (historyIndex <= 0) return
        historyIndex--
        history[historyIndex].let { navigateTo(it.path, it.volumeId, addToHistory = false) }
    }

    fun goForward() {
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        history[historyIndex].let { navigateTo(it.path, it.volumeId, addToHistory = false) }
    }

    fun goUp() {
        val crumbs = current.breadcrumbs
        if (crumbs.size >= 2) {
            val parent = crumbs[crumbs.size - 2]
            navigateTo(parent.path, parent.volumeId)
        }
    }

    /** Ermittelt das echte Volume eines Pfads (z. B. für Ordner aus „Zuletzt verwendet“). */
    fun resolveVolumeIdForPath(path: String): String {
        val match = volumesProvider()
            .filter { it.rootPath.isNotEmpty() && !it.rootPath.startsWith("chromeos://") && it.id != VOLUME_TRASH }
            .filter { path == it.rootPath || path.startsWith(it.rootPath + "/") }
            .maxByOrNull { it.rootPath.length }
        return match?.id ?: VOLUME_MY_FILES
    }

    // ------------------------------------------------------------------ Laden & Suche

    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val s = current
            if (s.currentPath.isEmpty()) return@launch
            _state.update { it.copy(isLoading = true) }
            val result = try {
                if (s.isShowingSearchResults) {
                    repository.searchFiles(s.searchFilter, s.currentPath, s.currentVolumeId, showHiddenFiles.value, s.sortSpec)
                } else {
                    repository.listDirectory(s.currentPath, s.currentVolumeId, s.sortSpec, showHiddenFiles.value, s.recentFilter)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Ordner konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}")
                emptyList()
            }
            val valid = result.map { it.id }.toSet()
            _state.update { st ->
                val kept = st.selectedIds.intersect(valid)
                st.copy(
                    nodes = result,
                    isLoading = false,
                    selectedIds = kept,
                    leadSelectionId = st.leadSelectionId?.takeIf { it in kept },
                    renamingId = st.renamingId?.takeIf { it in valid }
                )
            }
        }
    }

    fun openSearch() = _state.update { it.copy(isSearchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(isSearchOpen = false, searchFilter = SearchFilterState()) }
        reload()
    }

    fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchFilter = it.searchFilter.copy(query = query)) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload()
        }
    }

    fun onSearchLocationChanged(v: SearchLocation) {
        _state.update { it.copy(searchFilter = it.searchFilter.copy(location = v)) }
        reload()
    }

    fun onSearchModifiedChanged(v: SearchModifiedFilter) {
        _state.update { it.copy(searchFilter = it.searchFilter.copy(modifiedFilter = v)) }
        reload()
    }

    fun onSearchFileTypeChanged(v: SearchFileTypeFilter) {
        _state.update { it.copy(searchFilter = it.searchFilter.copy(fileTypeFilter = v)) }
        reload()
    }

    fun onRecentFilterChanged(v: SearchFileTypeFilter) {
        _state.update { it.copy(recentFilter = v) }
        reload()
    }

    // ------------------------------------------------------------------ Ansicht

    fun toggleViewMode() = _state.update {
        it.copy(viewMode = if (it.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST)
    }

    fun onSortColumnClicked(column: SortColumn) {
        _state.update { s ->
            val spec = if (s.sortSpec.column == column) {
                s.sortSpec.copy(
                    direction = if (s.sortSpec.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                )
            } else {
                s.sortSpec.copy(
                    column = column,
                    direction = if (column == SortColumn.DATE_MODIFIED) SortDirection.DESCENDING else SortDirection.ASCENDING
                )
            }
            s.copy(sortSpec = spec)
        }
        reload()
    }

    fun setSortDirection(direction: SortDirection) {
        _state.update { it.copy(sortSpec = it.sortSpec.copy(direction = direction)) }
        reload()
    }

    fun resizeColumnBy(column: SortColumn, deltaDp: Float) = _state.update { s ->
        val w = s.columnWidths
        fun clamp(v: Float) = v.coerceIn(COLUMN_MIN_DP, COLUMN_MAX_DP)
        s.copy(
            columnWidths = when (column) {
                SortColumn.SIZE -> w.copy(size = clamp(w.size - deltaDp))
                SortColumn.TYPE -> w.copy(type = clamp(w.type - deltaDp))
                SortColumn.DATE_MODIFIED -> w.copy(date = clamp(w.date - deltaDp))
                SortColumn.NAME -> w
            }
        )
    }

    // ------------------------------------------------------------------ Auswahl

    fun onNodeClicked(node: VfsNode, ctrl: Boolean, shift: Boolean) {
        _state.update { s ->
            val ids = s.nodes.map { it.id }
            val anchor = anchorId?.takeIf { it in ids }
            val selection: Set<String> = when {
                shift && anchor != null -> {
                    val a = ids.indexOf(anchor)
                    val b = ids.indexOf(node.id)
                    val range = ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
                    if (ctrl) s.selectedIds + range else range
                }
                ctrl -> {
                    anchorId = node.id
                    if (node.id in s.selectedIds) s.selectedIds - node.id else s.selectedIds + node.id
                }
                else -> {
                    anchorId = node.id
                    setOf(node.id)
                }
            }
            s.copy(selectedIds = selection, leadSelectionId = if (node.id in selection) node.id else selection.lastOrNull())
        }
    }

    /** Rechtsklick/Drag auf ein nicht ausgewähltes Element wählt nur dieses aus. */
    fun ensureSelected(node: VfsNode) {
        if (node.id in current.selectedIds) {
            _state.update { it.copy(leadSelectionId = node.id) }
        } else {
            anchorId = node.id
            _state.update { it.copy(selectedIds = setOf(node.id), leadSelectionId = node.id) }
        }
    }

    fun selectAll() = _state.update { s ->
        s.copy(selectedIds = s.nodes.map { it.id }.toSet(), leadSelectionId = s.leadSelectionId ?: s.nodes.firstOrNull()?.id)
    }

    fun clearSelection() {
        anchorId = null
        _state.update { it.copy(selectedIds = emptySet(), leadSelectionId = null) }
    }

    /** Gummiband-Auswahl: [base] ist die Auswahl beim Start (bei Strg additiv). */
    fun setMarqueeSelection(base: Set<String>, hits: Set<String>) {
        _state.update { s ->
            val selection = base + hits
            s.copy(selectedIds = selection, leadSelectionId = hits.lastOrNull() ?: s.leadSelectionId?.takeIf { it in selection })
        }
    }

    /** Pfeiltasten: Auswahl auf [targetId] setzen, mit Umschalt den Bereich ab dem Anker erweitern. */
    fun moveSelectionTo(targetId: String, extend: Boolean) {
        val ids = current.nodes.map { it.id }
        if (targetId !in ids) return
        if (extend) {
            val anchor = anchorId?.takeIf { it in ids } ?: current.leadSelectionId ?: targetId
            anchorId = anchor
            val a = ids.indexOf(anchor)
            val b = ids.indexOf(targetId)
            _state.update { it.copy(selectedIds = ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet(), leadSelectionId = targetId) }
        } else {
            anchorId = targetId
            _state.update { it.copy(selectedIds = setOf(targetId), leadSelectionId = targetId) }
        }
    }

    /** Linear durch die Liste bewegen (Listenansicht ↑/↓, Rasteransicht ←/→). */
    fun moveSelectionBy(delta: Int, extend: Boolean) {
        val nodes = current.nodes
        if (nodes.isEmpty()) return
        val leadIndex = nodes.indexOfFirst { it.id == current.leadSelectionId }
        val target = if (leadIndex < 0) {
            if (delta >= 0) 0 else nodes.lastIndex
        } else {
            (leadIndex + delta).coerceIn(0, nodes.lastIndex)
        }
        moveSelectionTo(nodes[target].id, extend)
    }

    fun selectIds(ids: Set<String>) = _state.update {
        it.copy(selectedIds = ids, leadSelectionId = ids.lastOrNull())
    }

    // ------------------------------------------------------------------ Umbenennen (inline)

    fun startRename(): Boolean {
        val s = current
        val lead = s.leadNode ?: return false
        if (s.selectedIds.size != 1 || lead.isReadOnly || s.isTrash) return false
        _state.update { it.copy(renamingId = lead.id) }
        return true
    }

    fun cancelRename() = _state.update { it.copy(renamingId = null) }

    fun commitRename(node: VfsNode, newName: String) {
        if (current.renamingId != node.id) return
        _state.update { it.copy(renamingId = null) }
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == node.name) return
        scope.launch {
            repository.renameNode(node, trimmed)
                .onSuccess { renamed ->
                    _state.update { it.copy(selectedIds = setOf(renamed.id), leadSelectionId = renamed.id) }
                    anchorId = renamed.id
                    reload()
                }
                .onFailure { onMessage(it.message ?: "Umbenennen fehlgeschlagen.") }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 220L
        private const val COLUMN_MIN_DP = 70f
        private const val COLUMN_MAX_DP = 420f
    }
}
