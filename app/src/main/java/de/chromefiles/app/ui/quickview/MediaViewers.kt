package de.chromefiles.app.ui.quickview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import de.chromefiles.app.data.media.WaveformExtractor
import de.chromefiles.app.ui.util.ChromeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val PDF_MAX_RENDER_WIDTH_PX = 2000
private const val PLAYER_POLL_MS = 200L

// ============================================================================ PDF

/** Hält einen [PdfRenderer]; Rendern und Schließen sind synchronisiert (der Renderer ist nicht threadsicher). */
private class PdfDocumentHolder(file: File) {
    private val lock = Any()
    private var renderer: PdfRenderer? =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    val pageCount: Int = renderer?.pageCount ?: 0

    fun pageAspect(index: Int): Float? = synchronized(lock) {
        val r = renderer ?: return null
        val page = r.openPage(index)
        try {
            page.width.toFloat() / page.height.coerceAtLeast(1)
        } finally {
            page.close()
        }
    }

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        val page = r.openPage(index)
        try {
            val heightPx = (widthPx.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    fun close() = synchronized(lock) {
        renderer?.close()
        renderer = null
    }
}

/** Mehrseitige PDF-Vorschau (scrollbar) mit Seitenanzeige. */
@Composable
fun PdfViewer(file: File, modifier: Modifier = Modifier) {
    val holder = remember(file) { runCatching { PdfDocumentHolder(file) } }
    DisposableEffect(holder) { onDispose { holder.getOrNull()?.close() } }
    val doc = holder.getOrNull()
    if (doc == null) {
        PreviewMessage("PDF kann nicht geöffnet werden (beschädigt oder passwortgeschützt).", modifier)
        return
    }
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val widthPx = constraints.maxWidth.coerceAtMost(PDF_MAX_RENDER_WIDTH_PX)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(doc.pageCount) { index ->
                val aspect by produceState(1f / 1.414f, doc, index) {
                    value = withContext(Dispatchers.IO) { doc.pageAspect(index) } ?: value
                }
                val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
                    value = withContext(Dispatchers.IO) { runCatching { doc.render(index, widthPx) }.getOrNull() }
                }
                Box(
                    modifier = Modifier
                        .widthIn(max = 1000.dp)
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), contentDescription = "Seite ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        ) {
            Text(
                "Seite $currentPage von ${doc.pageCount}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

// ============================================================================ Video

@Composable
private fun rememberPlayer(file: File, autoPlay: Boolean): ExoPlayer {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = autoPlay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/** Videowiedergabe mit Media3-Steuerleiste. Die View nimmt keinen Tastaturfokus, damit Leertaste/Pfeile beim QuickView bleiben. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val player = rememberPlayer(file, autoPlay = true)
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
                useController = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        update = { it.player = player },
        modifier = modifier.background(Color.Black)
    )
}

// ============================================================================ Audio

private data class AudioTags(val title: String?, val artist: String?, val album: String?)

/** Audiowiedergabe mit Wellenform (klick-/ziehbar zum Spulen), Zeitangaben und ID3-Tags. */
@Composable
fun AudioPlayer(file: File, modifier: Modifier = Modifier) {
    val player = rememberPlayer(file, autoPlay = true)
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(PLAYER_POLL_MS)
        }
    }
    val waveform by produceState<FloatArray?>(null, file) { value = WaveformExtractor.extract(file) }
    val tags by produceState<AudioTags?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                AudioTags(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    val fraction = scrubFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    fun seekTo(f: Float) {
        if (durationMs > 0) player.seekTo((f.coerceIn(0f, 1f) * durationMs).toLong())
    }

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            tags?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = listOfNotNull(tags?.artist?.takeIf { it.isNotBlank() }, tags?.album?.takeIf { it.isNotBlank() }).joinToString(" • ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))

        val wave = waveform
        val playedColor = MaterialTheme.colorScheme.primary
        val restColor = MaterialTheme.colorScheme.outlineVariant
        if (wave != null) {
            Canvas(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .pointerInputSeek(
                        onScrub = { scrubFraction = it },
                        onSeek = { f -> scrubFraction = null; seekTo(f) }
                    )
            ) {
                val barSlot = size.width / wave.size
                val barWidth = (barSlot * 0.6f).coerceAtLeast(1f)
                val midY = size.height / 2f
                wave.forEachIndexed { i, amplitude ->
                    val h = (amplitude.coerceIn(0.02f, 1f)) * size.height
                    val x = i * barSlot + (barSlot - barWidth) / 2f
                    drawRoundRect(
                        color = if ((i + 0.5f) / wave.size <= fraction) playedColor else restColor,
                        topLeft = Offset(x, midY - h / 2f),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
                val cursorX = fraction * size.width
                drawLine(playedColor, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 2.dp.toPx())
            }
        } else {
            Slider(
                value = fraction,
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = { scrubFraction?.let { seekTo(it) }; scrubFraction = null },
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
            )
            Text("Wellenform wird berechnet …", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(ChromeFormat.duration((fraction * durationMs).toLong()), style = MaterialTheme.typography.labelMedium)
            Text(ChromeFormat.duration(durationMs), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        FilledIconButton(
            onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } },
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Wiedergabe",
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

private fun Modifier.pointerInputSeek(onScrub: (Float) -> Unit, onSeek: (Float) -> Unit): Modifier = this
    .then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset -> onSeek(offset.x / size.width) }
        }
    )
    .then(
        Modifier.pointerInput(Unit) {
            var last = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset -> last = offset.x / size.width; onScrub(last) },
                onDragEnd = { onSeek(last) },
                onDragCancel = { onSeek(last) },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    last = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrub(last)
                }
            )
        }
    )

@Composable
internal fun PreviewMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
