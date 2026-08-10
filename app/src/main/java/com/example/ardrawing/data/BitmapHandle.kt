package com.example.ardrawing.data

import android.graphics.Bitmap
import android.util.Log
import com.example.ardrawing.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * BitmapHandle — Reference-counted Bitmap wrapper.
 * V5 FIX #8: retain() dùng CAS loop (compareAndSet) thay vì getAndIncrement().
 *
 * V4 bug: getAndIncrement() tăng TRƯỚC (0→1) rồi mới check — có thể resurrect handle đã dead.
 * V5 fix: compareAndSet(current, current+1) chỉ thành công khi current > 0. Atomic & safe.
 */
class BitmapHandle(val bitmap: Bitmap) {

    private val refCount = AtomicInteger(1)

    fun retain(): BitmapHandle {
        while (true) {
            val current = refCount.get()
            check(current > 0) {
                "BitmapHandle đã dead (refCount=$current). Kiểm tra retain/release ownership."
            }
            if (refCount.compareAndSet(current, current + 1)) return this
        }
    }

    fun release() {
        val remaining = refCount.decrementAndGet()
        when {
            remaining == 0 -> bitmap.recycleIfNotRecycled()
            remaining < 0  -> {
                val msg = "BitmapHandle over-released! remaining=$remaining"
                if (BuildConfig.DEBUG) error(msg) else Log.e(TAG, msg)
            }
        }
    }

    val isAlive: Boolean get() = refCount.get() > 0

    companion object {
        private const val TAG = "BitmapHandle"
        fun Bitmap.recycleIfNotRecycled() {
            if (!isRecycled) recycle()
        }
    }
}
