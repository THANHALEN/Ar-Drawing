package com.example.ardrawing.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.ardrawing.data.BitmapHandle.Companion.recycleIfNotRecycled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min
import kotlin.math.sqrt

class ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
        const val MAX_OUTPUT_WIDTH  = 1920
        const val MAX_OUTPUT_HEIGHT = 1080
        const val DEFAULT_THRESHOLD = 30

        // Sobel 3×3: max|Gx| = max|Gy| = 4×255 = 1020
        // magnitude_max = sqrt(1020² + 1020²) = 1020×√2 ≈ 1442.22
        private const val SOBEL_SINGLE_MAX = 1020
        private val SOBEL_MAG_MAX = SOBEL_SINGLE_MAX * sqrt(2.0)  // 1442.22
        private val SOBEL_SQ_MAX  = SOBEL_SINGLE_MAX.toLong() * SOBEL_SINGLE_MAX * 2L
    }

    suspend fun loadAndProcess(
        contentResolver: ContentResolver,
        uri: Uri,
        threshold: Int = DEFAULT_THRESHOLD
    ): Bitmap {
        var prevBitmap: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {
                val decoded = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(contentResolver, uri)
                }
                prevBitmap = decoded
                currentCoroutineContext().ensureActive()

                val rotated = withContext(Dispatchers.IO) {
                    applyExifRotation(decoded, contentResolver, uri)
                }
                if (rotated !== decoded) decoded.recycleIfNotRecycled()
                prevBitmap = rotated
                currentCoroutineContext().ensureActive()

                val scaled = downscaleToMax(rotated)
                if (scaled !== rotated) rotated.recycleIfNotRecycled()
                prevBitmap = scaled
                currentCoroutineContext().ensureActive()

                val gray = toGrayscale(scaled)
                scaled.recycleIfNotRecycled()
                prevBitmap = gray
                currentCoroutineContext().ensureActive()

                val blurred = gaussianBlur3x3(gray)
                gray.recycleIfNotRecycled()
                prevBitmap = blurred
                currentCoroutineContext().ensureActive()

                val result = sobelToLineArt(blurred, threshold)
                blurred.recycleIfNotRecycled()
                prevBitmap = null

                Log.d(TAG, "Pipeline OK: ${result.width}×${result.height}")
                result
            }
        } catch (e: Exception) {
            prevBitmap?.recycleIfNotRecycled()
            throw e
        }
    }

    private fun decodeSampledBitmap(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val ss = calculateInSampleSize(opts.outWidth, opts.outHeight, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT)
        val decOpts = BitmapFactory.Options().apply {
            inSampleSize = ss
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decOpts)
        } ?: error("Không thể mở stream từ URI: $uri")
    }

    // FIX V5 #4: OR condition + pixel budget + guard outW/H<=0
    internal fun calculateInSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        if (outW <= 0 || outH <= 0) return 1  // Guard: BitmapFactory decode lỗi

        var ss = 1
        // V4 dùng AND: dừng khi BẤT KỲ chiều nào < req → panorama 12000×1000 → ss=1 → OOM!
        // V5 dùng OR:  dừng khi CẢ HAI chiều < req → panorama được xử lý đúng
        if (outH > reqH || outW > reqW) {
            val hH = outH / 2; val hW = outW / 2
            while ((hH / ss) >= reqH || (hW / ss) >= reqW) {
                if (ss >= 32768) break
                ss *= 2
            }
        }
        // Pixel budget: pipeline có 4 bitmaps đồng thời → cap ở MAX*MAX*4
        val pixelBudget = reqW.toLong() * reqH * 4
        while (ss < 32768 && (outW.toLong() / ss) * (outH / ss) > pixelBudget) {
            ss *= 2
        }
        return ss
    }

    private fun applyExifRotation(source: Bitmap, contentResolver: ContentResolver, uri: Uri): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) { ExifInterface.ORIENTATION_NORMAL }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90     -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180    -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270    -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE     -> { matrix.postRotate(90f); matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_TRANSVERSE    -> { matrix.postRotate(-90f);matrix.postScale(-1f,1f) }
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun downscaleToMax(source: Bitmap): Bitmap {
        val sw = source.width; val sh = source.height
        if (sw <= MAX_OUTPUT_WIDTH && sh <= MAX_OUTPUT_HEIGHT) return source
        val f = min(MAX_OUTPUT_WIDTH.toFloat() / sw, MAX_OUTPUT_HEIGHT.toFloat() / sh)
        return Bitmap.createScaledBitmap(source, (sw*f).toInt().coerceAtLeast(1), (sh*f).toInt().coerceAtLeast(1), true)
    }

    // FIX V5 #1: currentCoroutineContext().ensureActive()
    // FIX V5 #6: try/catch cleanup output bitmap khi cancel
    private suspend fun toGrayscale(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(w)
            for (y in 0 until h) {
                if (y % 100 == 0) currentCoroutineContext().ensureActive()
                source.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val p = row[x]
                    val luma = (0.299f*((p shr 16) and 0xFF) + 0.587f*((p shr 8) and 0xFF) +
                                0.114f*(p and 0xFF)).toInt().coerceIn(0, 255)
                    row[x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
                output.setPixels(row, 0, w, 0, y, w, 1)
            }
            return output
        } catch (e: Exception) { output.recycleIfNotRecycled(); throw e }
    }

    private suspend fun gaussianBlur3x3(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val out  = IntArray(w)
            val rows = Array(3) { IntArray(w) }
            source.getPixels(rows[0], 0, w, 0, 0, w, 1)
            source.getPixels(rows[1], 0, w, 0, 1, w, 1)
            output.setPixels(rows[0], 0, w, 0, 0, w, 1)
            for (y in 1 until h - 1) {
                currentCoroutineContext().ensureActive()
                source.getPixels(rows[2], 0, w, 0, y+1, w, 1)
                out[0] = rows[1][0]; out[w-1] = rows[1][w-1]
                for (x in 1 until w - 1) {
                    val p00=rows[0][x-1]and 0xFF; val p01=rows[0][x]and 0xFF; val p02=rows[0][x+1]and 0xFF
                    val p10=rows[1][x-1]and 0xFF; val p11=rows[1][x]and 0xFF; val p12=rows[1][x+1]and 0xFF
                    val p20=rows[2][x-1]and 0xFF; val p21=rows[2][x]and 0xFF; val p22=rows[2][x+1]and 0xFF
                    val v = ((p00+2*p01+p02+2*p10+4*p11+2*p12+p20+2*p21+p22)/16).coerceIn(0,255)
                    out[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                output.setPixels(out, 0, w, 0, y, w, 1)
                val tmp=rows[0]; rows[0]=rows[1]; rows[1]=rows[2]; rows[2]=tmp
            }
            output.setPixels(rows[1], 0, w, 0, h-1, w, 1)
            return output
        } catch (e: Exception) { output.recycleIfNotRecycled(); throw e }
    }

    private suspend fun sobelToLineArt(source: Bitmap, threshold: Int): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val out  = IntArray(w)
            val normT = (threshold / 100.0 * 255).toInt().coerceIn(1, 254)
            val tSq  = normT.toLong() * normT * SOBEL_SQ_MAX / (255L * 255L)
            val rows = Array(3) { IntArray(w) }
            source.getPixels(rows[0], 0, w, 0, 0, w, 1)
            source.getPixels(rows[1], 0, w, 0, 1, w, 1)
            out.fill(0x00000000); output.setPixels(out, 0, w, 0, 0, w, 1)
            for (y in 1 until h - 1) {
                currentCoroutineContext().ensureActive()
                source.getPixels(rows[2], 0, w, 0, y+1, w, 1)
                out.fill(0x00000000)
                for (x in 1 until w - 1) {
                    val p00=rows[0][x-1]and 0xFF; val p01=rows[0][x]and 0xFF; val p02=rows[0][x+1]and 0xFF
                    val p10=rows[1][x-1]and 0xFF
                    val p12=rows[1][x+1]and 0xFF
                    val p20=rows[2][x-1]and 0xFF; val p21=rows[2][x]and 0xFF; val p22=rows[2][x+1]and 0xFF
                    val gx = -p00-2*p10-p20+p02+2*p12+p22
                    val gy = -p00-2*p01-p02+p20+2*p21+p22
                    val magSq = gx.toLong()*gx + gy.toLong()*gy
                    if (magSq >= tSq) {
                        val alpha = (sqrt(magSq.toDouble()) / SOBEL_MAG_MAX * 255).toInt().coerceIn(1, 255)
                        out[x] = (alpha shl 24)
                    }
                }
                output.setPixels(out, 0, w, 0, y, w, 1)
                val tmp=rows[0]; rows[0]=rows[1]; rows[1]=rows[2]; rows[2]=tmp
            }
            out.fill(0x00000000); output.setPixels(out, 0, w, 0, h-1, w, 1)
            return output
        } catch (e: Exception) { output.recycleIfNotRecycled(); throw e }
    }
}
