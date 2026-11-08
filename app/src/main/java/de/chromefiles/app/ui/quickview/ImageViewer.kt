package de.chromefiles.app.ui.quickview

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 8f
private const val WHEEL_ZOOM_STEP = 1.15f
private const val MIN_CROP_FRACTION = 0.05f

/**
 * Bildansicht mit Zoom (Pinch, Mausrad, Doppelklick = zurücksetzen) und Verschieben.
 * [cacheKey] ändert sich nach Bearbeitungen, damit Coil nicht das alte Bild aus dem Speicher zeigt.
 */
@Composable
fun ZoomableImage(file: File, cacheKey: String, contentDescription: String, modifier: Modifier = Modifier) {
    var scale by remember(cacheKey) { mutableFloatStateOf(1f) }
    var offset by remember(cacheKey) { mutableStateOf(Offset.Zero) }
    val context = LocalPlatformContext.current

    Box(
        modifier = modifier
            .pointerInput(cacheKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(cacheKey) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .pointerInput(cacheKey) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                scale = (if (delta < 0) scale * WHEEL_ZOOM_STEP else scale / WHEEL_ZOOM_STEP).coerceIn(1f, MAX_ZOOM)
                                if (scale == 1f) offset = Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .size(4096, 4096)
                .memoryCacheKey(cacheKey)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Zuschneide-Modus: zeigt das Bild (Fit) mit verschiebbarem Rahmen und vier Eck-Griffen.
 * [cropRect] ist normalisiert (0..1) relativ zum Bild.
 */
@Composable
fun CropEditor(
    file: File,
    cacheKey: String,
    imageSize: Pair<Int, Int>,
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    val handleRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val imageAspect = imageSize.first.toFloat() / imageSize.second.coerceAtLeast(1)
        val displayW = min(containerW, containerH * imageAspect)
        val displayH = displayW / imageAspect
        val imageArea = Rect(
            Offset((containerW - displayW) / 2f, (containerH - displayH) / 2f),
            Size(displayW, displayH)
        )

        AsyncImage(
            model = ImageRequest.Builder(context).data(file).size(4096, 4096).memoryCacheKey(cacheKey).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
        val current by rememberUpdatedState(cropRect)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(imageArea) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val r = toPixels(current, imageArea)
                            activeHandle = listOf(
                                CropHandle.TOP_LEFT to r.topLeft,
                                CropHandle.TOP_RIGHT to r.topRight,
                                CropHandle.BOTTOM_LEFT to r.bottomLeft,
                                CropHandle.BOTTOM_RIGHT to r.bottomRight
                            ).firstOrNull { (_, p) -> (p - start).getDistance() <= handleRadiusPx * 1.6f }?.first
                                ?: if (r.contains(start)) CropHandle.MOVE else null
                        },
                        onDragEnd = { activeHandle = null },
                        onDragCancel = { activeHandle = null },
                        onDrag = { change, drag ->
                            val handle = activeHandle ?: return@detectDragGestures
                            change.consume()
                            val dx = drag.x / imageArea.width
                            val dy = drag.y / imageArea.height
                            onCropRectChange(adjustCrop(current, handle, dx, dy))
                        }
                    )
                }
        ) {
            val r = toPixels(current, imageArea)
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, topLeft = Offset(imageArea.left, imageArea.top), size = Size(imageArea.width, r.top - imageArea.top))
            drawRect(dim, topLeft = Offset(imageArea.left, r.bottom), size = Size(imageArea.width, imageArea.bottom - r.bottom))
            drawRect(dim, topLeft = Offset(imageArea.left, r.top), size = Size(r.left - imageArea.left, r.height))
            drawRect(dim, topLeft = Offset(r.right, r.top), size = Size(imageArea.right - r.right, r.height))
            drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = Stroke(width = 2.dp.toPx()))
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.5f), Offset(r.left, y), Offset(r.right, y), strokeWidth = 1.dp.toPx())
            }
            listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { p ->
                drawCircle(Color.White, radius = handleRadiusPx / 2f, center = p)
            }
        }
    }
}

private fun toPixels(normalized: Rect, area: Rect) = Rect(
    left = area.left + normalized.left * area.width,
    top = area.top + normalized.top * area.height,
    right = area.left + normalized.right * area.width,
    bottom = area.top + normalized.bottom * area.height
)

private fun adjustCrop(r: Rect, handle: CropHandle, dx: Float, dy: Float): Rect {
    var left = r.left
    var top = r.top
    var right = r.right
    var bottom = r.bottom
    when (handle) {
        CropHandle.MOVE -> {
            val w = r.width
            val h = r.height
            left = (left + dx).coerceIn(0f, 1f - w)
            top = (top + dy).coerceIn(0f, 1f - h)
            right = left + w
            bottom = top + h
        }
        CropHandle.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION)
        }
        CropHandle.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION)
        }
        CropHandle.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f)
        }
        CropHandle.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f)
        }
    }
    return Rect(left, top, right, bottom)
}

fun Rect.toAndroidRectF(): RectF = RectF(left, top, right, bottom)

/** Dialog „Größe ändern“ mit optional gekoppeltem Seitenverhältnis. */
@Composable
fun ScaleImageDialog(
    originalSize: Pair<Int, Int>,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val (origW, origH) = originalSize
    var widthText by remember { mutableStateOf(origW.toString()) }
    var heightText by remember { mutableStateOf(origH.toString()) }
    var keepAspect by remember { mutableStateOf(true) }
    val aspect = origW.toFloat() / origH.coerceAtLeast(1)

    val w = widthText.toIntOrNull()
    val h = heightText.toIntOrNull()
    val valid = w != null && h != null && w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION &&
        !(w == origW && h == origH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Größe ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Original: $origW × $origH Pixel")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { v ->
                            widthText = v.filter { it.isDigit() }.take(5)
                            val nw = widthText.toIntOrNull()
                            if (keepAspect && nw != null) heightText = max(1, (nw / aspect).roundToInt()).toString()
                        },
                        label = { Text("Breite") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { v ->
                            heightText = v.filter { it.isDigit() }.take(5)
                            val nh = heightText.toIntOrNull()
                            if (keepAspect && nh != null) widthText = max(1, (nh * aspect).roundToInt()).toString()
                        },
                        label = { Text("Höhe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepAspect, onCheckedChange = { keepAspect = it })
                    Text("Seitenverhältnis beibehalten")
                }
                if (w != null && h != null && (w > MAX_DIMENSION || h > MAX_DIMENSION)) {
                    Text("Maximal $MAX_DIMENSION Pixel pro Seite.")
                }
                Text("Das Ergebnis wird als neue Datei neben dem Original gespeichert.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(w!!, h!!) }, enabled = valid) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

private const val MAX_DIMENSION = 16384

/** Prüft, ob ein normalisierter Zuschnitt praktisch das ganze Bild umfasst. */
fun Rect.isFullImage(): Boolean =
    abs(left) < 0.001f && abs(top) < 0.001f && abs(right - 1f) < 0.001f && abs(bottom - 1f) < 0.001f

