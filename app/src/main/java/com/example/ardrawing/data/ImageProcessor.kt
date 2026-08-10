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

/**
 * ImageProcessor V6 — Nâng cấp cho ảnh thật (photo), không chỉ line art.
 *
 * Thay đổi so với V5:
 * ┌─────────────────────────────────────────────────────────┐
 * │ V5 (line art only):                                     │
 * │  Gray → Blur3×3 → Sobel(fixedThreshold) → transparent  │
 * │                                                         │
 * │ V6 (any photo):                                         │
 * │  Gray → Blur3×3 → Blur3×3 → Sobel+Otsu → smooth gray  │
 * │         ↑ double pass       ↑ auto-thresh  ↑ Multiply  │
 * └─────────────────────────────────────────────────────────┘
 *
 * Otsu's method: tự tìm threshold tối ưu từ histogram gradient
 *   → không cần user chỉnh threshold thủ công cho từng ảnh
 *
 * Output: transparent bg + smooth gray edges (0-200)
 *   → dùng với BlendMode.Multiply trong ImageOverlayLayer
 *   → transparent × camera = camera (pass-through)
 *   → dark_gray × camera = darkened (natural pencil line)
 */
class ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
        const val MAX_OUTPUT_WIDTH  = 1920
        const val MAX_OUTPUT_HEIGHT = 1080
        const val DEFAULT_THRESHOLD = 30  // 0=nhiều cạnh, 100=ít cạnh

        // Sobel 3×3 đúng: max|Gx| = max|Gy| = 4×255 = 1020
        // magnitude_max = sqrt(1020² + 1020²) = 1020×√2 ≈ 1442.22
        private const val SOBEL_SINGLE_MAX = 1020
        private val SOBEL_MAG_MAX = SOBEL_SINGLE_MAX * sqrt(2.0)  // 1442.22
        private val SOBEL_SQ_MAX  = SOBEL_SINGLE_MAX.toLong() * SOBEL_SINGLE_MAX * 2L
    }

    /**
     * Pipeline V6:
     * decode → EXIF → downscale → grayscale → blur×2 → Sobel+Otsu → output
     *
     * @param threshold 0-100: điều chỉnh Otsu multiplier
     *   0  → 0.10× Otsu (rất nhạy, show nhiều cạnh, có thể noisy)
     *   30 → 1.00× Otsu (tối ưu tự động — DEFAULT)
     *   60 → 2.00× Otsu (chọn lọc, chỉ cạnh rõ)
     *   100→ 3.33× Otsu (chỉ cạnh rất mạnh)
     */
    suspend fun loadAndProcess(
        contentResolver: ContentResolver,
        uri: Uri,
        threshold: Int = DEFAULT_THRESHOLD
    ): Bitmap {
        var prevBitmap: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {

                // B1: Decode tối ưu (IO thread)
                val decoded = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(contentResolver, uri)
                }
                prevBitmap = decoded
                currentCoroutineContext().ensureActive()

                // B2: EXIF rotation (IO thread)
                val rotated = withContext(Dispatchers.IO) {
                    applyExifRotation(decoded, contentResolver, uri)
                }
                if (rotated !== decoded) decoded.recycleIfNotRecycled()
                prevBitmap = rotated
                currentCoroutineContext().ensureActive()

                // B3: Downscale về MAX_OUTPUT
                val scaled = downscaleToMax(rotated)
                if (scaled !== rotated) rotated.recycleIfNotRecycled()
                prevBitmap = scaled
                currentCoroutineContext().ensureActive()

                // B4: Grayscale (BT.601)
                val gray = toGrayscale(scaled)
                scaled.recycleIfNotRecycled()
                prevBitmap = gray
                currentCoroutineContext().ensureActive()

                // B5a: Gaussian Blur lần 1
                // V6 THAY ĐỔI: apply blur 2 lần thay vì 1 lần.
                // 3×3 × 2 lần ≈ Gaussian σ=√2 — noise suppression tốt hơn cho real photos.
                // Ảnh thật có texture phức tạp (da người, vải, tường) tạo false-positive edges.
                // Double blur loại bỏ texture noise trước khi Sobel detect.
                val blurred1 = gaussianBlur3x3(gray)
                gray.recycleIfNotRecycled()
                prevBitmap = blurred1
                currentCoroutineContext().ensureActive()

                // B5b: Gaussian Blur lần 2
                val blurred2 = gaussianBlur3x3(blurred1)
                blurred1.recycleIfNotRecycled()
                prevBitmap = blurred2
                currentCoroutineContext().ensureActive()

                // B6: Sobel + Otsu auto-threshold → smooth edge output
                // V6 THAY ĐỔI: thay sobelToLineArt (fixed threshold) bằng sobelWithOtsu.
                // Otsu tự tính threshold tối ưu từ histogram → hoạt động với mọi loại ảnh.
                // threshold slider → Otsu multiplier: threshold=30 dùng đúng Otsu.
                val otsuMultiplier = (threshold / 30f).coerceIn(0.1f, 4.0f)
                val result = sobelWithOtsuToLineArt(blurred2, otsuMultiplier)
                blurred2.recycleIfNotRecycled()
                prevBitmap = null  // result là output, KHÔNG recycle

                Log.d(TAG, "V6 Pipeline OK: ${result.width}×${result.height}")
                result
            }
        } catch (e: Exception) {
            prevBitmap?.recycleIfNotRecycled()
            throw e  // Re-throw tất cả (kể cả CancellationException)
        }
    }

    // =========================================================================
    // DECODE
    // =========================================================================

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

    // V5 fix #4: OR condition + pixel budget + guard outW/H<=0
    internal fun calculateInSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        if (outW <= 0 || outH <= 0) return 1
        var ss = 1
        if (outH > reqH || outW > reqW) {
            val hH = outH / 2; val hW = outW / 2
            while ((hH / ss) >= reqH || (hW / ss) >= reqW) {
                if (ss >= 32768) break
                ss *= 2
            }
        }
        val pixelBudget = reqW.toLong() * reqH * 4
        while (ss < 32768 && (outW.toLong() / ss) * (outH / ss) > pixelBudget) ss *= 2
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
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun downscaleToMax(source: Bitmap): Bitmap {
        val sw = source.width; val sh = source.height
        if (sw <= MAX_OUTPUT_WIDTH && sh <= MAX_OUTPUT_HEIGHT) return source
        val f = min(MAX_OUTPUT_WIDTH.toFloat() / sw, MAX_OUTPUT_HEIGHT.toFloat() / sh)
        return Bitmap.createScaledBitmap(source,
            (sw * f).toInt().coerceAtLeast(1),
            (sh * f).toInt().coerceAtLeast(1), true)
    }

    // =========================================================================
    // IMAGE PROCESSING
    // =========================================================================

    // BT.601: Y = 0.299R + 0.587G + 0.114B
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
                    val luma = (0.299f * ((p shr 16) and 0xFF) +
                                0.587f * ((p shr 8) and 0xFF) +
                                0.114f * (p and 0xFF)).toInt().coerceIn(0, 255)
                    row[x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
                output.setPixels(row, 0, w, 0, y, w, 1)
            }
            return output
        } catch (e: Exception) { output.recycleIfNotRecycled(); throw e }
    }

    // Gaussian Blur 3×3 — kernel [[1,2,1],[2,4,2],[1,2,1]] / 16
    // Sliding 3-row window: peak memory = 3 rows (tiny) + output bitmap
    private suspend fun gaussianBlur3x3(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val out  = IntArray(w)
            val rows = Array(3) { IntArray(w) }
            source.getPixels(rows[0], 0, w, 0, 0, w, 1)
            if (h > 1) source.getPixels(rows[1], 0, w, 0, 1, w, 1)
            output.setPixels(rows[0], 0, w, 0, 0, w, 1)  // border row 0
            for (y in 1 until h - 1) {
                currentCoroutineContext().ensureActive()
                source.getPixels(rows[2], 0, w, 0, y + 1, w, 1)
                out[0] = rows[1][0]; out[w - 1] = rows[1][w - 1]
                for (x in 1 until w - 1) {
                    val p00 = rows[0][x-1] and 0xFF; val p01 = rows[0][x] and 0xFF; val p02 = rows[0][x+1] and 0xFF
                    val p10 = rows[1][x-1] and 0xFF; val p11 = rows[1][x] and 0xFF; val p12 = rows[1][x+1] and 0xFF
                    val p20 = rows[2][x-1] and 0xFF; val p21 = rows[2][x] and 0xFF; val p22 = rows[2][x+1] and 0xFF
                    val v = ((p00 + 2*p01 + p02 + 2*p10 + 4*p11 + 2*p12 + p20 + 2*p21 + p22) / 16)
                        .coerceIn(0, 255)
                    out[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                output.setPixels(out, 0, w, 0, y, w, 1)
                val tmp = rows[0]; rows[0] = rows[1]; rows[1] = rows[2]; rows[2] = tmp
            }
            if (h > 1) output.setPixels(rows[1], 0, w, 0, h - 1, w, 1)
            return output
        } catch (e: Exception) { output.recycleIfNotRecycled(); throw e }
    }

    // =========================================================================
    // V6 MỚI: SOBEL + OTSU AUTO-THRESHOLD
    // =========================================================================

    /**
     * Sobel Edge Detection với Otsu's automatic threshold.
     *
     * Algorithm:
     *   Pass 1: Tính gradient magnitude cho mọi pixel → lưu vào IntArray
     *   Otsu:   Tìm threshold tối ưu từ magnitude histogram
     *   Pass 2: Áp dụng threshold → smooth gray edges (gradient, không hard binary)
     *
     * Output format (cho BlendMode.Multiply):
     *   • Pixel KHÔNG phải edge: 0x00000000 (transparent) → camera pass-through
     *   • Pixel edge (mag ≥ threshold): ARGB(255, gray, gray, gray)
     *       - gray = 200 khi mag = threshold (barely visible)
     *       - gray = 0 khi mag = 255 (pitch black, strongest edge)
     *   → Kết quả: cạnh mịn, anti-aliased tự nhiên
     *
     * @param otsuMultiplier: hệ số nhân với Otsu threshold
     *   < 1.0 → ngưỡng thấp hơn Otsu → nhiều cạnh hơn
     *   = 1.0 → dùng đúng Otsu (threshold=30)
     *   > 1.0 → ngưỡng cao hơn Otsu → ít cạnh hơn, chọn lọc hơn
     */
    private suspend fun sobelWithOtsuToLineArt(
        source: Bitmap,
        otsuMultiplier: Float = 1.0f
    ): Bitmap {
        val w = source.width; val h = source.height
        // Lưu toàn bộ magnitude để: (1) tính Otsu histogram, (2) apply threshold
        // Memory: w×h × 4 bytes = ~8MB cho 1080p — chấp nhận được
        val magnitudes = IntArray(w * h)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        try {
            // ================================================================
            // PASS 1: Tính Sobel gradient magnitude — sliding 3-row window
            // ================================================================
            val rows = Array(3) { IntArray(w) }
            source.getPixels(rows[0], 0, w, 0, 0, w, 1)
            if (h > 1) source.getPixels(rows[1], 0, w, 0, 1, w, 1)
            // Border pixels (row 0, row h-1, col 0, col w-1): magnitude = 0 (default)

            for (y in 1 until h - 1) {
                if (y % 50 == 0) currentCoroutineContext().ensureActive()
                source.getPixels(rows[2], 0, w, 0, y + 1, w, 1)
                val rowBase = y * w

                for (x in 1 until w - 1) {
                    val p00 = rows[0][x-1] and 0xFF; val p01 = rows[0][x] and 0xFF
                    val p02 = rows[0][x+1] and 0xFF
                    val p10 = rows[1][x-1] and 0xFF
                    val p12 = rows[1][x+1] and 0xFF
                    val p20 = rows[2][x-1] and 0xFF; val p21 = rows[2][x] and 0xFF
                    val p22 = rows[2][x+1] and 0xFF

                    // Sobel kernels
                    val gx = -p00 - 2*p10 - p20 + p02 + 2*p12 + p22
                    val gy = -p00 - 2*p01 - p02 + p20 + 2*p21 + p22

                    // Normalized magnitude 0-255
                    magnitudes[rowBase + x] = (
                        sqrt(gx.toLong() * gx + gy.toLong() * gy.toDouble()) /
                        SOBEL_MAG_MAX * 255
                    ).toInt().coerceIn(0, 255)
                }
                val tmp = rows[0]; rows[0] = rows[1]; rows[1] = rows[2]; rows[2] = tmp
            }

            // ================================================================
            // OTSU'S METHOD: Tìm optimal threshold từ magnitude histogram
            // ================================================================
            val rawOtsu = computeOtsuThreshold(magnitudes)
            // Áp dụng otsuMultiplier từ slider:
            // threshold=30 → multiplier=1.0 → dùng Otsu trực tiếp (optimal)
            // threshold=0  → multiplier=0.1 → ngưỡng thấp → nhiều edge (sensitive)
            // threshold=100→ multiplier=3.3 → ngưỡng cao → ít edge (selective)
            val finalThreshold = (rawOtsu * otsuMultiplier).toInt().coerceIn(3, 250)

            Log.d(TAG, "Otsu raw=$rawOtsu, multiplier=%.2f, final=$finalThreshold".format(otsuMultiplier))

            // ================================================================
            // PASS 2: Apply threshold → smooth gray output cho BlendMode.Multiply
            // ================================================================
            val outRow = IntArray(w)
            val range = (255 - finalThreshold).toFloat().coerceAtLeast(1f)

            for (y in 0 until h) {
                if (y % 100 == 0) currentCoroutineContext().ensureActive()
                val rowBase = y * w
                for (x in 0 until w) {
                    val mag = magnitudes[rowBase + x]
                    if (mag > finalThreshold) {
                        // EDGE PIXEL: smooth gradient từ barely-visible đến pitch-black
                        // strength=0 → gray=200 (edge barely visible)
                        // strength=1 → gray=0   (pitch black, strongest edge)
                        val strength = ((mag - finalThreshold).toFloat() / range).coerceIn(0f, 1f)
                        val gray = (200f * (1f - strength)).toInt().coerceIn(0, 200)

                        // ARGB: alpha=255 (opaque), R=G=B=gray
                        // Với BlendMode.Multiply:
                        //   gray=200 → camera × 0.78 (barely darkened, subtle edge)
                        //   gray=100 → camera × 0.39 (clearly darkened)
                        //   gray=0   → camera × 0    (pure black, strongest line)
                        outRow[x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                    } else {
                        // BACKGROUND: transparent → camera pass-through với Multiply
                        outRow[x] = 0x00000000
                    }
                }
                output.setPixels(outRow, 0, w, 0, y, w, 1)
            }

            return output
        } catch (e: Exception) {
            output.recycleIfNotRecycled()
            throw e
        }
    }

    /**
     * Otsu's Thresholding Algorithm
     * Tìm threshold t tối đa hóa between-class variance:
     *   σ²(t) = w₀(t)·w₁(t)·[μ₀(t) - μ₁(t)]²
     *
     * Input: mảng magnitude 0-255 (từ Sobel)
     * Output: threshold tối ưu cho ảnh cụ thể này
     *
     * Không cần user tune — tự động adapt với mọi loại ảnh:
     *   - Portrait (ít edge) → threshold thấp để bắt được cạnh mặt người
     *   - Cityscape (nhiều edge) → threshold cao để lọc bớt
     *   - Line art → threshold rất thấp (ảnh đã là đường nét)
     */
    private fun computeOtsuThreshold(magnitudes: IntArray): Int {
        val histogram = IntArray(256)
        for (mag in magnitudes) histogram[mag]++

        val total = magnitudes.size.toLong()
        // Tính tổng có trọng số: sum = Σ(t × count[t])
        var sum = 0L
        for (t in 1..255) sum += t.toLong() * histogram[t]

        var sumB = 0L       // Tổng trọng số của class "background" (magnitude < t)
        var wB   = 0L       // Số pixel class "background"
        var maxVariance = 0.0
        var threshold = 30  // Fallback mặc định nếu histogram quá phẳng

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0L) continue
            val wF = total - wB  // Class "foreground" (edge pixels)
            if (wF == 0L) break

            sumB += t.toLong() * histogram[t]
            val mB = sumB.toDouble() / wB        // Mean của background class
            val mF = (sum - sumB).toDouble() / wF // Mean của foreground class

            // Between-class variance: σ² = wB·wF·(mB-mF)²
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }

        // Nếu Otsu trả về 0 (histogram quá tập trung), dùng fallback
        return threshold.coerceAtLeast(5)
    }
}
