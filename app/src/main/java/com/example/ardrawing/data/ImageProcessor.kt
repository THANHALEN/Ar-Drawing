package com.example.ardrawing.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.ardrawing.data.BitmapHandle.Companion.recycleIfNotRecycled
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow

/**
 * ImageProcessor V9 — ML Kit Selfie Segmentation + Color Dodge Pencil Sketch.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ V9 Pipeline (LINE_ART mode):                                     │
 * │                                                                  │
 * │  Photo                                                           │
 * │   ↓ decode + EXIF + downscale                                    │
 * │   ├─→ ML Kit Segmentation ──────────→ foreground mask            │
 * │   └─→ Grayscale → Color Dodge Sketch                             │
 * │              ↓                                                   │
 * │         Composite blend:                                         │
 * │           foreground → pencil sketch (transparent bg)            │
 * │           background → fully transparent (camera shows through)  │
 * │              ↓                                                   │
 * │         Result: sketch của người nổi trên camera feed            │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Color Dodge algorithm (verified từ Python testing):
 *   1. Gray → Invert
 *   2. Invert → Box Blur lớn (radius=40, 3 passes ≈ Gaussian σ≈25)
 *   3. Color Dodge: sketch = gray × 255 / (255 - blurred)
 *   4. Power curve: output = pow(sketch/255, 0.4) × 255
 *   → Kết quả: nền trắng, nét chì xám như app gốc ✓
 *
 * ML Kit Selfie Segmentation:
 *   - On-device model, không cần internet (bundled via AndroidManifest)
 *   - Detect người (foreground) vs nền (background)
 *   - fgConfidence > 0.5 = foreground → giữ sketch
 *   - fgConfidence ≤ 0.5 = background → transparent (xóa sketch)
 */
class ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
        const val MAX_OUTPUT_WIDTH  = 1920
        const val MAX_OUTPUT_HEIGHT = 1080
        const val DEFAULT_THRESHOLD = 30

        // Color Dodge parameters (verified via Python testing với ảnh thật)
        private const val BLUR_RADIUS = 40   // Box blur radius (≈ Gaussian r=40 trong Python)
        private const val BLUR_PASSES = 3    // 3 passes ≈ Gaussian σ≈25px
        private const val POWER_CURVE = 0.4  // Tone curve: 0.4 → best match với app gốc

        // Segmentation thresholds
        private const val FG_HARD  = 0.5f   // Trên ngưỡng này → hoàn toàn là người
        private const val FG_SOFT  = 0.25f  // Dưới ngưỡng này → hoàn toàn là nền
        // Giữa FG_SOFT và FG_HARD → transition mềm (tránh edge artifact)
    }

    // =========================================================================
    // ML Kit Segmenter — khởi tạo một lần, tái sử dụng
    // SelfieSegmenterOptions:
    //   SINGLE_IMAGE_MODE: tối ưu cho ảnh tĩnh (không phải video)
    //   enableRawSizeMask(): mask có cùng kích thước với input image
    // =========================================================================
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    /** Gọi trong ViewModel.onCleared() để giải phóng ML Kit resources */
    fun close() {
        try { segmenter.close() }
        catch (e: Exception) { Log.w(TAG, "Segmenter close error: ${e.message}") }
    }

    // =========================================================================
    // ORIGINAL MODE: Ảnh màu gốc làm mờ (không xử lý)
    // =========================================================================
    suspend fun loadOriginal(contentResolver: ContentResolver, uri: Uri): Bitmap {
        var prev: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {
                val decoded = withContext(Dispatchers.IO) { decodeSampledBitmap(contentResolver, uri) }
                prev = decoded
                currentCoroutineContext().ensureActive()

                val rotated = withContext(Dispatchers.IO) { applyExifRotation(decoded, contentResolver, uri) }
                if (rotated !== decoded) decoded.recycleIfNotRecycled()
                prev = rotated
                currentCoroutineContext().ensureActive()

                val scaled = downscaleToMax(rotated)
                if (scaled !== rotated) rotated.recycleIfNotRecycled()
                prev = null
                scaled
            }
        } catch (e: Exception) { prev?.recycleIfNotRecycled(); throw e }
    }

    // =========================================================================
    // LINE_ART MODE: ML Kit Segmentation + Color Dodge Pencil Sketch
    //
    // @param threshold 0-100:
    //   Thấp (0-20)  → FG threshold thấp → detect nhiều foreground hơn
    //   Vừa (30-50)  → FG threshold cân bằng (default)
    //   Cao (60-100) → FG threshold cao → chỉ detect foreground rõ ràng
    // =========================================================================
    suspend fun loadAndProcess(
        contentResolver: ContentResolver,
        uri: Uri,
        threshold: Int = DEFAULT_THRESHOLD
    ): Bitmap {
        var prev: Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {

                // B1: Decode (IO thread)
                val decoded = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(contentResolver, uri)
                }
                prev = decoded
                currentCoroutineContext().ensureActive()

                // B2: EXIF rotation (IO thread)
                val rotated = withContext(Dispatchers.IO) {
                    applyExifRotation(decoded, contentResolver, uri)
                }
                if (rotated !== decoded) decoded.recycleIfNotRecycled()
                prev = rotated
                currentCoroutineContext().ensureActive()

                // B3: Downscale về MAX_OUTPUT
                val scaled = downscaleToMax(rotated)
                if (scaled !== rotated) rotated.recycleIfNotRecycled()
                prev = scaled
                currentCoroutineContext().ensureActive()

                // B4: ML Kit Segmentation
                // Trả về FloatArray (foreground confidence mỗi pixel, 0.0-1.0)
                // Fallback về null nếu ML Kit fail → dùng sketch toàn ảnh
                val fgMask: FloatArray? = try {
                    runSegmentation(scaled)
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit segmentation failed, fallback: ${e.message}")
                    null  // Fallback: áp sketch cho toàn ảnh
                }
                currentCoroutineContext().ensureActive()

                // B5: Grayscale
                val gray = toGrayscale(scaled)
                scaled.recycleIfNotRecycled()
                prev = gray
                currentCoroutineContext().ensureActive()

                // B6: Color Dodge + Segmentation Blend
                // threshold → fgConfidenceThreshold
                val fgThresh = 0.25f + (threshold / 100f) * 0.4f
                // threshold=0  → 0.25 (aggressive foreground)
                // threshold=30 → 0.37 (balanced)
                // threshold=100→ 0.65 (conservative foreground)
                val result = colorDodgeWithSegmentation(gray, fgMask, fgThresh)
                gray.recycleIfNotRecycled()
                prev = null

                Log.d(TAG, "V9 OK: ${result.width}×${result.height}, fgThresh=$fgThresh")
                result
            }
        } catch (e: Exception) {
            prev?.recycleIfNotRecycled()
            throw e
        }
    }

    // =========================================================================
    // ML Kit SEGMENTATION
    // Trả về FloatArray[width×height] với giá trị 0.0-1.0
    //   > 0.5 = foreground (người)
    //   < 0.5 = background (nền phòng)
    // =========================================================================
    private suspend fun runSegmentation(bitmap: Bitmap): FloatArray {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // suspendCancellableCoroutine: chuyển Task<> callback thành suspend function
        val mask: SegmentationMask = suspendCancellableCoroutine { cont ->
            segmenter.process(inputImage)
                .addOnSuccessListener { segMask -> cont.resume(segMask) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

        // Đọc toàn bộ mask buffer thành FloatArray
        // enableRawSizeMask() đảm bảo mask.width == bitmap.width, mask.height == bitmap.height
        val buffer = mask.buffer
        buffer.rewind()
        val floatBuffer: FloatBuffer = buffer.asFloatBuffer()
        val result = FloatArray(mask.width * mask.height)
        floatBuffer.get(result)

        Log.d(TAG, "Segmentation mask: ${mask.width}×${mask.height}, " +
                   "fg pixels=${result.count { it > 0.5f } * 100 / result.size}%")
        return result
    }

    // =========================================================================
    // COLOR DODGE PENCIL SKETCH + SEGMENTATION BLEND
    //
    // Color Dodge (từ Python testing, params verified):
    //   gray → invert → heavy box blur → dodge blend → power curve
    //
    // Segmentation blend:
    //   fgConf >= fgThresh → foreground: áp sketch (transparent bg + dark strokes)
    //   fgConf < softThresh → background: transparent (camera shows through)
    //   Giữa hai ngưỡng → smooth transition tránh hard edges
    //
    // Output ARGB:
    //   Nét chì (stroke) → opaque black (A>0, RGB=0)
    //   Nền người (paper) → near-transparent
    //   Nền phòng → fully transparent (A=0)
    // =========================================================================
    private suspend fun colorDodgeWithSegmentation(
        gray: Bitmap,
        fgMask: FloatArray?,
        fgThresh: Float
    ): Bitmap {
        val w = gray.width; val h = gray.height; val total = w * h
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        try {
            // ── Load grayscale pixels ────────────────────────────────────────
            val gPx = IntArray(total)
            gray.getPixels(gPx, 0, w, 0, 0, w, h)
            val gArr = FloatArray(total) { (gPx[it] and 0xFF).toFloat() }
            currentCoroutineContext().ensureActive()

            // ── Color Dodge Step 1: Invert ────────────────────────────────────
            val invArr = FloatArray(total) { 255f - gArr[it] }

            // ── Color Dodge Step 2: Heavy Box Blur ───────────────────────────
            // 3 passes × radius=40 ≈ Gaussian σ=25px
            // Blur lớn → vùng đồng nhất (nền) về 255 → dodge=255 (trắng)
            // Vùng chi tiết (người) giữ gradient → dodge thấp hơn (nét)
            val blurred = heavyBoxBlur(invArr, w, h, BLUR_RADIUS, BLUR_PASSES)
            currentCoroutineContext().ensureActive()

            // ── Color Dodge Step 3: Dodge Blend + Power Curve ────────────────
            // dodge = gray × 255 / (255 - blurred)    [Color Dodge formula]
            // sketch = pow(dodge/255, 0.4) × 255       [Tone curve, verified]
            val sketchArr = FloatArray(total)
            for (i in 0 until total) {
                val denom = (255f - blurred[i]).coerceAtLeast(1f)
                val dodge = (gArr[i] * 255f / denom).coerceIn(0f, 255f)
                sketchArr[i] = (Math.pow(dodge.toDouble() / 255.0, POWER_CURVE) * 255.0)
                    .toFloat().coerceIn(0f, 255f)
            }
            currentCoroutineContext().ensureActive()

            // ── Segmentation Blend ───────────────────────────────────────────
            val softThresh = FG_SOFT  // Below this = definitely background
            val outPx = IntArray(total)

            for (i in 0 until total) {
                val sketchVal = sketchArr[i]

                if (fgMask == null) {
                    // Fallback (no segmentation): áp sketch cho toàn ảnh
                    val alpha = (255f - sketchVal).coerceIn(0f, 255f).toInt()
                    outPx[i] = (alpha shl 24) or 0x00000000
                    continue
                }

                val fgConf = fgMask[i].coerceIn(0f, 1f)

                when {
                    fgConf >= fgThresh -> {
                        // ── FOREGROUND (người) ─────────────────────────────
                        // Pencil sketch overlay:
                        //   sketch=255 (paper/white) → alpha=0 (trong suốt, camera xuyên qua)
                        //   sketch=100 (gray stroke) → alpha=155 (semi-opaque, nét thấy được)
                        //   sketch=0   (dark stroke) → alpha=255 (opaque, nét đậm nhất)
                        val alpha = (255f - sketchVal).coerceIn(0f, 255f).toInt()
                        outPx[i] = (alpha shl 24) or 0x00000000
                    }

                    fgConf >= softThresh -> {
                        // ── TRANSITION ZONE (edge người/nền) ──────────────
                        // Fade out sketch để tránh hard edge artifact
                        // Smooth linear blend từ soft→hard threshold
                        val blend = (fgConf - softThresh) / (fgThresh - softThresh)
                        val alpha = ((255f - sketchVal) * blend).coerceIn(0f, 255f).toInt()
                        outPx[i] = (alpha shl 24) or 0x00000000
                    }

                    else -> {
                        // ── BACKGROUND (nền phòng) ─────────────────────────
                        // Fully transparent → camera feed hiện nguyên
                        // Người dùng thấy trực tiếp không gian xung quanh
                        outPx[i] = 0x00000000
                    }
                }
            }

            output.setPixels(outPx, 0, w, 0, 0, w, h)
            return output

        } catch (e: Exception) {
            output.recycleIfNotRecycled()
            throw e
        }
    }

    // =========================================================================
    // HEAVY BOX BLUR — Separable, prefix sum, O(n) per pass
    //
    // Tại sao Box Blur thay Gaussian nhỏ:
    //   Pencil Sketch cần blur RẤT LỚN (radius=40)
    //   Gaussian 3×3 (r=1) không đủ → vẫn ra Sobel-like noise
    //   Box Blur r=40 → window 81×81px → smooth rất mạnh
    //   3 passes Box Blur ≈ Gaussian (Central Limit Theorem)
    //
    // Memory: 3 IntArray × w×h → 3 × 8MB = 24MB cho 1080p
    // Speed: O(w×h) per pass → ~2M ops × 6 passes = ~12M ops → <200ms
    // =========================================================================
    private suspend fun heavyBoxBlur(
        src: FloatArray,
        w: Int, h: Int,
        radius: Int,
        passes: Int
    ): FloatArray {
        // 3 buffer slots: rotate references giữa các passes
        val buf   = Array(3) { if (it == 0) src else FloatArray(src.size) }
        val prefW = IntArray(w + 1)   // Prefix sum buffer cho horizontal
        val prefH = IntArray(h + 1)   // Prefix sum buffer cho vertical

        var curIdx = 0
        for (pass in 0 until passes) {
            val hIdx = (curIdx + 1) % 3   // H-pass output slot
            val vIdx = (curIdx + 2) % 3   // V-pass output slot

            // ── Horizontal pass ────────────────────────────────────────────
            // Mỗi pixel = trung bình của [x-r, x+r] trong hàng y
            for (y in 0 until h) {
                if (y % 100 == 0) currentCoroutineContext().ensureActive()
                val base = y * w
                // Build prefix sum
                prefW[0] = 0
                for (x in 0 until w) {
                    prefW[x + 1] = prefW[x] + buf[curIdx][base + x].toInt()
                }
                // Apply box average
                for (x in 0 until w) {
                    val lo  = (x - radius).coerceAtLeast(0)
                    val hi  = (x + radius).coerceAtMost(w - 1)
                    val cnt = hi - lo + 1
                    buf[hIdx][base + x] = (prefW[hi + 1] - prefW[lo]).toFloat() / cnt
                }
            }

            // ── Vertical pass ──────────────────────────────────────────────
            // Mỗi pixel = trung bình của [y-r, y+r] trong cột x
            for (x in 0 until w) {
                if (x % 200 == 0) currentCoroutineContext().ensureActive()
                // Build prefix sum
                prefH[0] = 0
                for (y in 0 until h) {
                    prefH[y + 1] = prefH[y] + buf[hIdx][y * w + x].toInt()
                }
                // Apply box average
                for (y in 0 until h) {
                    val lo  = (y - radius).coerceAtLeast(0)
                    val hi  = (y + radius).coerceAtMost(h - 1)
                    val cnt = hi - lo + 1
                    buf[vIdx][y * w + x] = (prefH[hi + 1] - prefH[lo]).toFloat() / cnt
                }
            }

            curIdx = vIdx
        }
        return buf[curIdx]
    }

    // =========================================================================
    // DECODE + EXIF + DOWNSCALE (dùng chung)
    // =========================================================================

    private fun decodeSampledBitmap(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val ss = calculateInSampleSize(opts.outWidth, opts.outHeight, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT)
        val decOpts = BitmapFactory.Options().apply {
            inSampleSize = ss
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, decOpts)
        } ?: error("Không thể mở URI: $uri")
    }

    internal fun calculateInSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        if (outW <= 0 || outH <= 0) return 1
        var ss = 1
        if (outH > reqH || outW > reqW) {
            val hH = outH / 2; val hW = outW / 2
            while ((hH / ss) >= reqH || (hW / ss) >= reqW) {
                if (ss >= 32768) break; ss *= 2
            }
        }
        val budget = reqW.toLong() * reqH * 4
        while (ss < 32768 && (outW.toLong() / ss) * (outH / ss) > budget) ss *= 2
        return ss
    }

    private fun applyExifRotation(source: Bitmap, contentResolver: ContentResolver, uri: Uri): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use { s ->
                ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90    -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180   -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270   -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f);m.postScale(-1f, 1f) }
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }

    private fun downscaleToMax(source: Bitmap): Bitmap {
        val sw = source.width; val sh = source.height
        if (sw <= MAX_OUTPUT_WIDTH && sh <= MAX_OUTPUT_HEIGHT) return source
        val f = min(MAX_OUTPUT_WIDTH.toFloat() / sw, MAX_OUTPUT_HEIGHT.toFloat() / sh)
        return Bitmap.createScaledBitmap(source,
            (sw * f).toInt().coerceAtLeast(1),
            (sh * f).toInt().coerceAtLeast(1), true)
    }

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
}
