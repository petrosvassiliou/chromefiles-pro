package de.chromefiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class TabInfo(val paneId: String, val title: String)

/**
 * Tab-Leiste (Strg + T / Strg + W / Strg + Tab). Wird nur bei mehr als einem Tab angezeigt.
 */
@Composable
fun TabStrip(
    tabs: List<TabInfo>,
    activePaneId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { tab ->
            val active = tab.paneId == activePaneId
            Row(
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 220.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) colors.secondaryContainer else colors.surfaceContainerHigh)
                    .clickable { onSelect(tab.paneId) }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tab.title.ifEmpty { "Neuer Tab" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.onSecondaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = { onClose(tab.paneId) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Tab schließen (Strg + W)", modifier = Modifier.size(16.dp))
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "Neuer Tab (Strg + T)", modifier = Modifier.size(18.dp))
        }
    }
}
