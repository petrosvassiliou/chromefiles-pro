package de.chromefiles.app.ui.util

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalHapticFeedback
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.model.VfsVolumeType
import de.chromefiles.app.ui.viewmodel.SidebarTreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Deutsche Formatierung (de-DE) von Größen, Datumsangaben und Dauer – wie in der ChromeOS Files App.
 */
object ChromeFormat {
    private val germanLocale: Locale = Locale.GERMANY
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", germanLocale)
    private val dateFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", germanLocale)

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Byte"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(germanLocale, "%.1f %s", value, units[unitIndex])
    }

    fun date(millis: Long): String {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.now(zone)
        return when (dateTime.toLocalDate()) {
            today -> "Heute, ${dateTime.format(timeFormatter)}"
            today.minusDays(1) -> "Gestern, ${dateTime.format(timeFormatter)}"
            else -> dateTime.format(dateFormatter)
        }
    }

    fun duration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(germanLocale, "%d:%02d:%02d", h, m, s)
        else String.format(germanLocale, "%d:%02d", m, s)
    }

    fun itemCount(count: Int): String = if (count == 1) "1 Element" else "$count Elemente"
}

fun VfsNode.icon(): ImageVector = when {
    isDirectory -> Icons.Filled.Folder
    isArchive -> Icons.Outlined.FolderZip
    isPdf -> Icons.Outlined.PictureAsPdf
    isImage -> Icons.Outlined.Image
    isVideo -> Icons.Outlined.Movie
    isAudio -> Icons.Outlined.AudioFile
    extension.lowercase() in setOf("csv", "xls", "xlsx") -> Icons.Outlined.TableChart
    extension.lowercase() in VfsNode.CODE_EXTENSIONS -> Icons.Outlined.Code
    extension.lowercase() in setOf("md", "txt", "rtf", "doc", "docx") -> Icons.AutoMirrored.Outlined.Article
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
fun VfsNode.iconTint(): Color = when {
    isDirectory -> MaterialTheme.colorScheme.onSurfaceVariant
    isPdf || isImage || isVideo || isAudio -> Color(0xFFD93025)
    extension.lowercase() in setOf("csv", "xls", "xlsx") -> Color(0xFF188038)
    extension.lowercase() in setOf("md", "txt", "rtf", "doc", "docx") -> Color(0xFF1A73E8)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun SidebarTreeNode.icon(): ImageVector {
    if (depth == 0) {
        return when (volumeType) {
            VfsVolumeType.RECENT -> Icons.Outlined.Schedule
            VfsVolumeType.STARRED -> Icons.Outlined.StarOutline
            VfsVolumeType.MY_FILES -> Icons.Outlined.Laptop
            VfsVolumeType.LINUX_CROSTINI -> Icons.Outlined.Terminal
            VfsVolumeType.GOOGLE_DRIVE -> Icons.Outlined.Cloud
            VfsVolumeType.MOUNTED_ARCHIVE -> Icons.Outlined.FolderZip
            VfsVolumeType.REMOVABLE_MEDIA -> Icons.Outlined.Usb
            VfsVolumeType.SMB_SHARE -> Icons.Outlined.Lan
            VfsVolumeType.TRASH -> Icons.Outlined.Delete
        }
    }
    return when (title) {
        "Downloads" -> Icons.Outlined.Download
        "Play-Dateien" -> Icons.Outlined.Android
        "Kamera" -> Icons.Outlined.PhotoCamera
        "Geteilte Ablagen" -> Icons.Outlined.FolderShared
        "Für mich freigegeben" -> Icons.Outlined.People
        "Computer" -> Icons.Outlined.Computer
        "Offline verfügbar" -> Icons.Outlined.OfflinePin
        else -> Icons.Outlined.Folder
    }
}

/**
 * Desktop- & Touch-Gesten für Datei-Elemente (Listenzeile / Kachel):
 * - Maus links: Klick mit Strg/Meta- und Umschalt-Modifikatoren, Doppelklick, Ziehen startet natives Drag & Drop
 *   (funktioniert auch app-übergreifend, z. B. in die ChromeOS Files App oder Chrome).
 * - Maus rechts bzw. Zwei-Finger-Tipp auf dem Trackpad: Kontextmenü (wird beim Loslassen geöffnet, damit
 *   das PopupWindow nicht durch das Mouse-Up-Event sofort wieder geschlossen wird).
 * - Touch:
 *   - Tippen = Klick / Öffnen, Doppeltippen = Öffnen
 *   - Langes Drücken + Ziehen = Natives Drag & Drop starten (für Dateioperationen & Favoriten)
 *   - Langes Drücken ohne Ziehen = Kontextmenü öffnen + Haptisches Feedback
 * Ist [enabled] false (z. B. beim Inline-Umbenennen), werden alle Gesten an Kinder durchgereicht.
 */
@Composable
fun Modifier.fileItemGestures(
    key: Any?,
    enabled: Boolean,
    onClick: (ctrl: Boolean, shift: Boolean, isTouch: Boolean) -> Unit,
    onDoubleClick: () -> Unit,
    onContextMenu: () -> Unit,
    onPrepareDrag: () -> DragAndDropTransferData?,
    decorationColor: Color
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    val currentOnPrepareDrag by rememberUpdatedState(onPrepareDrag)
    val haptics = LocalHapticFeedback.current
    val lastClickUptime = remember(key) { LongArray(1) }

    fun handleClick(uptime: Long, mods: PointerKeyboardModifiers, isTouch: Boolean) {
        val ctrl = mods.isCtrlPressed || mods.isMetaPressed
        val shift = mods.isShiftPressed
        val isDouble = !ctrl && !shift && uptime - lastClickUptime[0] < DOUBLE_CLICK_TIMEOUT_MS
        if (isDouble) {
            lastClickUptime[0] = 0L
            currentOnDoubleClick()
        } else {
            lastClickUptime[0] = uptime
            currentOnClick(ctrl, shift, isTouch)
        }
    }

    return this.dragAndDropSource(
        drawDragDecoration = {
            drawRoundRect(
                color = decorationColor,
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
        }
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled || down.isConsumed) return@awaitEachGesture
            val pressEvent = currentEvent
            val mods = pressEvent.keyboardModifiers

            // Sofortiger Rechtsklick für Googlebook OS / ChromeOS & Android:
            // Funktioniert unabhängig vom PointerType (Mouse, Touchpad, Stylus) bei rechter Maustaste,
            // Trackpad-Sekundärklick, Maus-Zurücktaste oder ChromeOS Alt + Klick.
            val isImmediateRightClick = pressEvent.buttons.isSecondaryPressed ||
                pressEvent.buttons.isBackPressed ||
                (mods.isAltPressed && (pressEvent.buttons.isPrimaryPressed || down.pressed))
            if (isImmediateRightClick) {
                down.consume()
                currentOnContextMenu()
                withTimeoutOrNull(350L) {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (!event.buttons.isSecondaryPressed && event.changes.none { it.pressed }) {
                            break
                        }
                    }
                }
                return@awaitEachGesture
            }

            if (down.type == PointerType.Mouse) {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.buttons.isSecondaryPressed || event.buttons.isBackPressed) {
                        event.changes.forEach { it.consume() }
                        currentOnContextMenu()
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        change.consume()
                        handleClick(change.uptimeMillis, mods, isTouch = false)
                        break
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        change.consume()
                        currentOnPrepareDrag()?.let { startTransfer(it) }
                        break
                    }
                }
            } else {
                var up: PointerInputChange? = null
                var cancelledOrScrolled = false
                var secondaryTriggered = false
                var maxPointers = pressEvent.changes.count { it.pressed }.coerceAtLeast(1)
                val finishedInTime = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedNow = event.changes.count { it.pressed }
                        if (pressedNow > maxPointers) maxPointers = pressedNow
                        if (event.buttons.isSecondaryPressed ||
                            event.buttons.isBackPressed ||
                            event.keyboardModifiers.isAltPressed
                        ) {
                            event.changes.forEach { it.consume() }
                            secondaryTriggered = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            cancelledOrScrolled = true
                            break
                        }
                        if (!change.pressed) {
                            up = change
                            break
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            cancelledOrScrolled = true
                            break
                        }
                    }
                    true
                }
                if (secondaryTriggered || (maxPointers == 2 && !cancelledOrScrolled && finishedInTime != null)) {
                    // Trackpad Zwei-Finger-Klick oder nachträglicher Sekundär-Button auf Googlebook OS
                    up?.consume()
                    currentOnContextMenu()
                } else if (finishedInTime == null && !cancelledOrScrolled) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Nach Long-Press: Bewegt der Nutzer den Finger -> Drag & Drop starten.
                    // Lässt er los oder hält 450 ms ruhig -> Kontextmenü öffnen.
                    var dragStarted = false
                    var releasedDuringHold = false
                    withTimeoutOrNull(450L) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                releasedDuringHold = true
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                change.consume()
                                dragStarted = true
                                currentOnPrepareDrag()?.let { startTransfer(it) }
                                break
                            }
                        }
                    }
                    if (!dragStarted) {
                        currentOnContextMenu()
                        if (!releasedDuringHold) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop * 1.5f) {
                                    currentOnPrepareDrag()?.let { startTransfer(it) }
                                    break
                                }
                            }
                        }
                    }
                } else if (!cancelledOrScrolled) {
                    up?.let {
                        it.consume()
                        handleClick(it.uptimeMillis, mods, isTouch = true)
                    }
                }
            }
        }
    }
}

private const val DOUBLE_CLICK_TIMEOUT_MS = 400L

/**
 * Rendert die erste Seite einer PDF-Datei als Bitmap (Thumbnail & Info-Spalte).
 */
@Composable
fun rememberPdfFirstPage(path: String, targetWidthPx: Int): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, path, targetWidthPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                try {
                    if (renderer.pageCount == 0) return@runCatching null
                    val page = renderer.openPage(0)
                    try {
                        val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(
                            targetWidthPx.coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
            }.getOrNull()
        }
    }
    return bitmap
}
