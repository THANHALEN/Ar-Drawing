package com.example.ardrawing.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ardrawing.data.BitmapHandle
import com.example.ardrawing.data.BitmapHandle.Companion.recycleIfNotRecycled
import com.example.ardrawing.data.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class ArDrawingViewModel : ViewModel() {

    private val imageProcessor = ImageProcessor()
    private val _uiState = MutableStateFlow(ArDrawingUiState())
    val uiState: StateFlow<ArDrawingUiState> = _uiState.asStateFlow()

    private var processingJob: Job? = null

    // V5 FIX #3: Revision counter — latest-request-wins
    private val _requestRevision = AtomicInteger(0)
    private var _activeHandle: BitmapHandle? = null
    private var _bitmapRevision = 0

    fun loadAndProcessImage(contentResolver: ContentResolver, uri: Uri) {
        processingJob?.cancel()
        val myRevision = _requestRevision.incrementAndGet()

        processingJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, processingError = null) }
            try {
                val newBitmap = imageProcessor.loadAndProcess(
                    contentResolver = contentResolver,
                    uri = uri,
                    threshold = _uiState.value.edgeThreshold
                )
                // Kiểm tra: nếu user đã chọn ảnh khác → discard kết quả lỗi thời
                if (_requestRevision.get() != myRevision) {
                    newBitmap.recycleIfNotRecycled()
                    Log.d(TAG, "Discard stale result rev=$myRevision")
                    return@launch
                }
                val newHandle = BitmapHandle(newBitmap)
                val oldHandle = _activeHandle
                _activeHandle = newHandle
                _bitmapRevision++
                _uiState.update { it.copy(
                    bitmapHandle = newHandle,
                    bitmapId     = _bitmapRevision,
                    isProcessing = false,
                    savedScale = 1f, savedRotation = 0f,
                    savedOffsetX = 0f, savedOffsetY = 0f
                ) }
                oldHandle?.release()

            } catch (e: CancellationException) {
                // V5 FIX #3: KHÔNG set state gì → job mới đã giữ isProcessing=true
                Log.d(TAG, "Rev=$myRevision cancelled")
                throw e  // Re-throw bắt buộc

            } catch (e: Exception) {
                if (_requestRevision.get() == myRevision) {
                    Log.e(TAG, "Error rev=$myRevision: ${e.message}", e)
                    _uiState.update { it.copy(
                        isProcessing = false,
                        processingError = "Không thể xử lý ảnh: ${e.localizedMessage}"
                    ) }
                }
            }
        }
    }

    fun updateOpacity(opacity: Float)  { _uiState.update { it.copy(opacity = opacity.coerceIn(0f,1f)) } }
    fun toggleLock()                    { _uiState.update { it.copy(isLocked = !it.isLocked) } }
    fun onCameraError(msg: String)      { _uiState.update { it.copy(cameraError = msg) } }
    fun clearCameraError()              { _uiState.update { it.copy(cameraError = null) } }
    fun clearError()                    { _uiState.update { it.copy(processingError = null) } }

    fun saveGestureTransform(scale: Float, rotation: Float, offsetX: Float, offsetY: Float) {
        _uiState.update { it.copy(
            savedScale = scale, savedRotation = rotation,
            savedOffsetX = offsetX, savedOffsetY = offsetY
        ) }
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        _activeHandle?.release()
        _activeHandle = null
    }

    companion object { private const val TAG = "ArDrawingViewModel" }
}
