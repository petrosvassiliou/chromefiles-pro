package de.chromefiles.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Bild-Schnellwerkzeuge (wie die ChromeOS-Galerie-Integration):
 * - Drehen: JPEG verlustfrei über das EXIF-Orientierungs-Tag, PNG/WebP verlustfrei neu kodiert.
 * - Zuschneiden & Skalieren: als neue Datei neben dem Original („… (zugeschnitten)“, „… (1280×720)“).
 *   JPEG wird dabei mit Qualität 95 neu kodiert, PNG/verlustfreies WebP bleiben verlustfrei.
 */
@Singleton
class ImageEditor @Inject constructor() {

    fun isEditable(extension: String): Boolean = extension.lowercase() in EDITABLE

    /** Dreht um 90° im ([clockwise]) oder gegen den Uhrzeigersinn. Überschreibt die Datei. */
    suspend fun rotate(file: File, clockwise: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = file.extension.lowercase()
            require(isEditable(ext)) { "Dieses Bildformat kann nicht gedreht werden." }
            if (ext == "jpg" || ext == "jpeg") {
                val exif = ExifInterface(file)
                exif.rotate(if (clockwise) 90 else -90)
                exif.saveAttributes()
            } else {
                val bitmap = decodeOriented(file)
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(if (clockwise) 90f else -90f) }, true
                )
                writeAtomically(rotated, file, ext)
                if (rotated !== bitmap) rotated.recycle()
                bitmap.recycle()
            }
            file
        }.recoverOom()
    }

    /** [normalizedRect] in Bildkoordinaten 0..1 der angezeigten (EXIF-korrigierten) Ausrichtung. */
    suspend fun crop(file: File, normalizedRect: RectF): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = file.extension.lowercase()
            require(isEditable(ext)) { "Dieses Bildformat kann nicht zugeschnitten werden." }
            val bitmap = decodeOriented(file)
            val left = (normalizedRect.left.coerceIn(0f, 1f) * bitmap.width).roundToInt()
            val top = (normalizedRect.top.coerceIn(0f, 1f) * bitmap.height).roundToInt()
            val right = (normalizedRect.right.coerceIn(0f, 1f) * bitmap.width).roundToInt()
            val bottom = (normalizedRect.bottom.coerceIn(0f, 1f) * bitmap.height).roundToInt()
            val width = (right - left).coerceAtLeast(1)
            val height = (bottom - top).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(bitmap, left, top, width.coerceAtMost(bitmap.width - left), height.coerceAtMost(bitmap.height - top))
            val target = uniqueSibling(file, "zugeschnitten")
            writeAtomically(cropped, target, ext)
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            target
        }.recoverOom()
    }

    suspend fun scale(file: File, targetWidth: Int, targetHeight: Int): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = file.extension.lowercase()
            require(isEditable(ext)) { "Dieses Bildformat kann nicht skaliert werden." }
            require(targetWidth in 1..MAX_DIMENSION && targetHeight in 1..MAX_DIMENSION) {
                "Breite und Höhe müssen zwischen 1 und $MAX_DIMENSION Pixeln liegen."
            }
            val bitmap = decodeOriented(file)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            val target = uniqueSibling(file, "${targetWidth}×$targetHeight")
            writeAtomically(scaled, target, ext)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            target
        }.recoverOom()
    }

    /** Pixelmaße in angezeigter Ausrichtung (für den Skalieren-Dialog). */
    fun orientedSize(file: File): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return@runCatching null
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (rotation == 90 || rotation == 270) opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
    }.getOrNull()

    private fun decodeOriented(file: File): Bitmap {
        val raw = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Bild konnte nicht gelesen werden.")
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return raw
        val matrix = Matrix()
        val rotation = exif.rotationDegrees
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (exif.isFlipped) matrix.postScale(-1f, 1f)
        if (matrix.isIdentity) return raw
        val oriented = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (oriented !== raw) raw.recycle()
        return oriented
    }

    private fun writeAtomically(bitmap: Bitmap, target: File, ext: String) {
        val (format, quality) = when (ext) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG to 95
            "png" -> Bitmap.CompressFormat.PNG to 100
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS to 100
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP to 100
            }
            else -> throw IllegalArgumentException("Nicht unterstütztes Format: $ext")
        }
        val temp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            check(bitmap.compress(format, quality, out)) { "Bild konnte nicht gespeichert werden." }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Bild konnte nicht gespeichert werden." }
    }

    private fun uniqueSibling(file: File, suffix: String): File {
        val parent = file.parentFile ?: throw IllegalStateException("Kein Zielordner.")
        var candidate = File(parent, "${file.nameWithoutExtension} ($suffix).${file.extension}")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(parent, "${file.nameWithoutExtension} ($suffix $counter).${file.extension}")
            counter++
        }
        return candidate
    }

    private fun <T> Result<T>.recoverOom(): Result<T> = recoverCatching { err ->
        if (err is OutOfMemoryError) throw IllegalStateException("Das Bild ist zu groß für die Bearbeitung auf diesem Gerät.")
        throw err
    }

    companion object {
        private val EDITABLE = setOf("jpg", "jpeg", "png", "webp")
        private const val MAX_DIMENSION = 12_000
    }
}
