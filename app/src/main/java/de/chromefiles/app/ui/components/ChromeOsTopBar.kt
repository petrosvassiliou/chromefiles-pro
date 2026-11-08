package de.chromefiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.SearchFileTypeFilter
import de.chromefiles.app.model.SearchFilterState
import de.chromefiles.app.model.SearchLocation
import de.chromefiles.app.model.SearchModifiedFilter
import de.chromefiles.app.model.SortColumn
import de.chromefiles.app.model.SortDirection
import de.chromefiles.app.model.SortSpec
import de.chromefiles.app.ui.viewmodel.FileViewMode
import de.chromefiles.app.ui.dnd.DropPayload
import de.chromefiles.app.ui.dnd.fileDropTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Kopfzeile im ChromeOS-Stil: Breadcrumbs, ausklappbare Suche und Toolbar-Buttons.
 */
@Composable
fun ChromeOsTopBar(
    breadcrumbs: List<BreadcrumbSegment>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isSearchOpen: Boolean,
    searchQuery: String,
    viewMode: FileViewMode,
    sortSpec: SortSpec,
    showHiddenFiles: Boolean,
    isDetailsPanelVisible: Boolean,
    canCreateFolder: Boolean,
    isTrash: Boolean,
    hasSelection: Boolean,
    onToggleSidebar: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onBreadcrumbClick: (BreadcrumbSegment) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onSortColumn: (SortColumn) -> Unit,
    onSortDirection: (SortDirection) -> Unit,
    onToggleDetails: () -> Unit,
    onNewFolder: () -> Unit,
    onToggleHiddenFiles: () -> Unit,
    onRefresh: () -> Unit,
    onRestoreFromTrash: () -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier,
    showSidebarToggle: Boolean = true,
    hasAllFilesAccess: Boolean = false,
    isUsingDeviceStorage: Boolean = false,
    isDriveConnected: Boolean = false,
    onRequestAllFilesAccess: () -> Unit = {},
    onToggleDeviceStorage: () -> Unit = {},
    onPickSafFolder: (asLinuxContainer: Boolean) -> Unit = {},
    onOpenDriveAuth: () -> Unit = {},
    canDropOnBreadcrumb: (BreadcrumbSegment) -> Boolean = { false },
    onBreadcrumbDrop: (BreadcrumbSegment, DropPayload) -> Boolean = { _, _ -> false }
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isCompactBar = maxWidth < 560.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showSidebarToggle) {
                ToolbarIcon(Icons.Outlined.Menu, "Seitenleiste ein-/ausblenden", onClick = onToggleSidebar)
            }
            if (!isCompactBar || canGoBack) {
                ToolbarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück (Alt + ←)", enabled = canGoBack, onClick = onBack)
            }
            if (!isCompactBar) {
                ToolbarIcon(Icons.AutoMirrored.Filled.ArrowForward, "Vorwärts (Alt + →)", enabled = canGoForward, onClick = onForward)
            }
            Spacer(Modifier.width(2.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (isSearchOpen) {
                    SearchField(query = searchQuery, onQueryChange = onSearchQueryChange, onClose = onCloseSearch)
                } else {
                    BreadcrumbBar(
                        segments = breadcrumbs,
                        onSegmentClick = onBreadcrumbClick,
                        canDropOn = canDropOnBreadcrumb,
                        onDrop = onBreadcrumbDrop
                    )
                }
            }

            if (!isSearchOpen) {
                ToolbarIcon(Icons.Outlined.Search, "Suchen (Strg + F)", onClick = onOpenSearch)
            }

            if (isTrash && !isCompactBar) {
                TextButton(onClick = onRestoreFromTrash, enabled = hasSelection) {
                    Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wiederherstellen")
                }
                TextButton(onClick = onEmptyTrash) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Papierkorb leeren")
                }
            }

            if (canCreateFolder && !isSearchOpen) {
                ToolbarIcon(Icons.Outlined.CreateNewFolder, "Neuer Ordner (Strg + E)", onClick = onNewFolder)
            }
            if (!isSearchOpen) {
                ToolbarIcon(
                    icon = if (viewMode == FileViewMode.LIST) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                    label = if (viewMode == FileViewMode.LIST) "Rasteransicht" else "Listenansicht",
                    onClick = onToggleViewMode
                )
            }
            if (!isCompactBar) {
                SortMenuButton(sortSpec = sortSpec, onSortColumn = onSortColumn, onSortDirection = onSortDirection)

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Datei-Info ein-/ausblenden") } },
                    state = rememberTooltipState()
                ) {
                    IconToggleButton(checked = isDetailsPanelVisible, onCheckedChange = { onToggleDetails() }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Datei-Info",
                            tint = if (isDetailsPanelVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            MoreMenuButton(
                showHiddenFiles = showHiddenFiles,
                hasAllFilesAccess = hasAllFilesAccess,
                isDriveConnected = isDriveConnected,
                isDetailsPanelVisible = isDetailsPanelVisible,
                isTrash = isTrash,
                hasSelection = hasSelection,
                onToggleHiddenFiles = onToggleHiddenFiles,
                onToggleDetails = onToggleDetails,
                onRefresh = onRefresh,
                onRestoreFromTrash = onRestoreFromTrash,
                onEmptyTrash = onEmptyTrash,
                onRequestAllFilesAccess = onRequestAllFilesAccess,
                onPickSafFolder = onPickSafFolder,
                onOpenDriveAuth = onOpenDriveAuth
            )
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

/**
 * Interaktive Breadcrumb-Pfadleiste. Bei mehr als 4 Segmenten werden die mittleren
 * – wie in ChromeOS – hinter einem „…“-Menü zusammengefasst. Jedes sichtbare Segment
 * ist ein Drag-&-Drop-Ziel.
 */
@Composable
fun BreadcrumbBar(
    segments: List<BreadcrumbSegment>,
    onSegmentClick: (BreadcrumbSegment) -> Unit,
    canDropOn: (BreadcrumbSegment) -> Boolean = { false },
    onDrop: (BreadcrumbSegment, DropPayload) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier
) {
    val collapse = segments.size > 4
    val head = if (collapse) listOf(segments.first()) else segments
    val hidden = if (collapse) segments.subList(1, segments.size - 2) else emptyList()
    val tail = if (collapse) segments.takeLast(2) else emptyList()

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        head.forEachIndexed { index, segment ->
            if (index > 0) BreadcrumbSeparator()
            BreadcrumbChip(
                segment = segment,
                isLast = !collapse && index == segments.lastIndex,
                onClick = onSegmentClick,
                dropEnabled = canDropOn(segment),
                onDrop = { onDrop(segment, it) }
            )
        }
        if (collapse) {
            BreadcrumbSeparator()
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    hidden.forEach { segment ->
                        DropdownMenuItem(
                            text = { Text(segment.title) },
                            onClick = {
                                menuOpen = false
                                onSegmentClick(segment)
                            }
                        )
                    }
                }
            }
            tail.forEachIndexed { index, segment ->
                BreadcrumbSeparator()
                BreadcrumbChip(
                    segment = segment,
                    isLast = index == tail.lastIndex,
                    onClick = onSegmentClick,
                    dropEnabled = canDropOn(segment),
                    onDrop = { onDrop(segment, it) }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbSeparator() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun BreadcrumbChip(
    segment: BreadcrumbSegment,
    isLast: Boolean,
    onClick: (BreadcrumbSegment) -> Unit,
    dropEnabled: Boolean,
    onDrop: (DropPayload) -> Boolean
) {
    Text(
        text = segment.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
        color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(16.dp))
            .fileDropTarget(enabled = dropEnabled, shape = RoundedCornerShape(16.dp), onDrop = onDrop)
            .clickable { onClick(segment) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "In Dateien suchen …",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    }
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Suche schließen", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SortMenuButton(
    sortSpec: SortSpec,
    onSortColumn: (SortColumn) -> Unit,
    onSortDirection: (SortDirection) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(Icons.AutoMirrored.Outlined.Sort, "Sortieren", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortColumn.entries.forEach { column ->
                DropdownMenuItem(
                    text = { Text(column.labelDe) },
                    leadingIcon = {
                        if (sortSpec.column == column) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        if (sortSpec.column != column) onSortColumn(column)
                        open = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Aufsteigend") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                trailingIcon = { if (sortSpec.direction == SortDirection.ASCENDING) Icon(Icons.Filled.Check, null) },
                onClick = {
                    onSortDirection(SortDirection.ASCENDING)
                    open = false
                }
            )
            DropdownMenuItem(
                text = { Text("Absteigend") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                trailingIcon = { if (sortSpec.direction == SortDirection.DESCENDING) Icon(Icons.Filled.Check, null) },
                onClick = {
                    onSortDirection(SortDirection.DESCENDING)
                    open = false
                }
            )
        }
    }
}

@Composable
private fun MoreMenuButton(
    showHiddenFiles: Boolean,
    hasAllFilesAccess: Boolean,
    isDriveConnected: Boolean,
    isDetailsPanelVisible: Boolean,
    isTrash: Boolean,
    hasSelection: Boolean,
    onToggleHiddenFiles: () -> Unit,
    onToggleDetails: () -> Unit,
    onRefresh: () -> Unit,
    onRestoreFromTrash: () -> Unit,
    onEmptyTrash: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onPickSafFolder: (asLinuxContainer: Boolean) -> Unit,
    onOpenDriveAuth: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(Icons.Outlined.MoreVert, "Weitere Optionen", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Datei-Info-Spalte") },
                leadingIcon = {
                    if (isDetailsPanelVisible) Icon(Icons.Filled.Check, contentDescription = null)
                    else Icon(Icons.Outlined.Info, contentDescription = null)
                },
                onClick = {
                    onToggleDetails()
                    open = false
                }
            )
            DropdownMenuItem(
                text = { Text("Versteckte Dateien anzeigen") },
                leadingIcon = {
                    if (showHiddenFiles) Icon(Icons.Filled.Check, contentDescription = null)
                    else Spacer(Modifier.size(24.dp))
                },
                trailingIcon = {
                    Text("Strg + .", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = {
                    onToggleHiddenFiles()
                    open = false
                }
            )
            DropdownMenuItem(
                text = { Text("Aktualisieren") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = {
                    onRefresh()
                    open = false
                }
            )
            if (isTrash) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Auswahl wiederherstellen") },
                    enabled = hasSelection,
                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                    onClick = {
                        open = false
                        onRestoreFromTrash()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Papierkorb leeren") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                    onClick = {
                        open = false
                        onEmptyTrash()
                    }
                )
            }
            HorizontalDivider()
            if (!hasAllFilesAccess) {
                DropdownMenuItem(
                    text = { Text("Lokalen Dateizugriff freigeben …") },
                    onClick = {
                        open = false
                        onRequestAllFilesAccess()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Gerätespeicher aktiv (/storage/emulated/0)") },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { open = false }
                )
            }
            DropdownMenuItem(
                text = { Text("Linux-Container (Crostini) über SAF einbinden …") },
                onClick = {
                    open = false
                    onPickSafFolder(true)
                }
            )
            DropdownMenuItem(
                text = { Text("USB-/SD-Ordner über SAF einbinden …") },
                onClick = {
                    open = false
                    onPickSafFolder(false)
                }
            )
            DropdownMenuItem(
                text = {
                    Text(if (isDriveConnected) "Google Drive-Konto verwalten …" else "Google Drive verbinden …")
                },
                leadingIcon = {
                    if (isDriveConnected) Icon(Icons.Filled.Check, contentDescription = null)
                    else Spacer(Modifier.size(24.dp))
                },
                onClick = {
                    open = false
                    onOpenDriveAuth()
                }
            )
        }
    }
}

/**
 * Die drei ausklappbaren ChromeOS-Such-Filterchips: Ort, Änderungsdatum, Dateityp.
 */
@Composable
fun SearchFilterChipRow(
    filter: SearchFilterState,
    onLocation: (SearchLocation) -> Unit,
    onModified: (SearchModifiedFilter) -> Unit,
    onFileType: (SearchFileTypeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DropdownFilterChip(
            options = SearchLocation.entries,
            selected = filter.location,
            isDefault = filter.location == SearchLocation.EVERYWHERE,
            label = { it.labelDe },
            onSelect = onLocation
        )
        DropdownFilterChip(
            options = SearchModifiedFilter.entries,
            selected = filter.modifiedFilter,
            isDefault = filter.modifiedFilter == SearchModifiedFilter.ANYTIME,
            label = { if (it == SearchModifiedFilter.ANYTIME) "Änderungsdatum: Beliebig" else it.labelDe },
            onSelect = onModified
        )
        DropdownFilterChip(
            options = SearchFileTypeFilter.entries,
            selected = filter.fileTypeFilter,
            isDefault = filter.fileTypeFilter == SearchFileTypeFilter.ALL,
            label = { it.labelDe },
            onSelect = onFileType
        )
    }
}

@Composable
private fun <T> DropdownFilterChip(
    options: List<T>,
    selected: T,
    isDefault: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = !isDefault,
            onClick = { open = true },
            label = { Text(label(selected)) },
            leadingIcon = if (!isDefault) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            shape = RoundedCornerShape(8.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    leadingIcon = {
                        if (option == selected) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Schnellfilter-Chips oberhalb von „Zuletzt verwendet“: Alle, Audio, Dokumente, Bilder, Videos.
 */
@Composable
fun RecentFilterChipRow(
    selected: SearchFileTypeFilter,
    onSelect: (SearchFileTypeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val order = listOf(
        SearchFileTypeFilter.ALL to "Alle",
        SearchFileTypeFilter.AUDIO to "Audio",
        SearchFileTypeFilter.DOCUMENTS to "Dokumente",
        SearchFileTypeFilter.IMAGES to "Bilder",
        SearchFileTypeFilter.VIDEOS to "Videos"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        order.forEach { (filter, label) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(label) },
                leadingIcon = if (selected == filter) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
