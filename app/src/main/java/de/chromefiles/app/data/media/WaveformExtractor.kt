package de.chromefiles.app.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Berechnet eine echte Wellenform (Spitzenpegel je Segment) durch Dekodieren der Audiospur
 * mit MediaExtractor + MediaCodec zu 16-Bit-PCM. Ergebnis: [bucketCount] Werte im Bereich 0..1.
 */
object WaveformExtractor {

    suspend fun extract(file: File, bucketCount: Int = 240): FloatArray? = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return@withContext null

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val peaks = FloatArray(bucketCount)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val out = decoder.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * bucketCount)
                            .toInt().coerceIn(0, bucketCount - 1)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val samples = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        var peak = 0
                        // Jede 4. Probe reicht für eine Übersichts-Wellenform und spart viel Rechenzeit.
                        var i = 0
                        while (i < samples.limit()) {
                            peak = max(peak, abs(samples.get(i).toInt()))
                            i += 4
                        }
                        peaks[bucket] = max(peaks[bucket], peak / 32768f)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val maxPeak = peaks.maxOrNull()?.takeIf { it > 0f } ?: return@withContext peaks
            FloatArray(bucketCount) { (peaks[it] / maxPeak).coerceIn(0f, 1f) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private const val TIMEOUT_US = 10_000L
}
