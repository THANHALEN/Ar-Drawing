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

    private val imageProcessor   = ImageProcessor()
    private val _uiState         = MutableStateFlow(ArDrawingUiState())
    val uiState: StateFlow<ArDrawingUiState> = _uiState.asStateFlow()

    private var processingJob    : Job? = null
    private val _requestRevision = AtomicInteger(0)
    private var _activeHandle    : BitmapHandle? = null
    private var _bitmapRevision  = 0

    fun loadAndProcessImage(contentResolver: ContentResolver, uri: Uri) {
        _uiState.update { it.copy(savedUri = uri) }
        processWithCurrentMode(contentResolver, uri)
    }

    fun toggleDisplayMode(contentResolver: ContentResolver) {
        val newMode = if (_uiState.value.displayMode == DisplayMode.ORIGINAL)
            DisplayMode.LINE_ART else DisplayMode.ORIGINAL
        _uiState.update { it.copy(displayMode = newMode) }
        _uiState.value.savedUri?.let { processWithCurrentMode(contentResolver, it) }
    }

    private fun processWithCurrentMode(contentResolver: ContentResolver, uri: Uri) {
        processingJob?.cancel()
        val myRevision = _requestRevision.incrementAndGet()
        val mode = _uiState.value.displayMode

        processingJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, processingError = null) }
            try {
                val newBitmap = when (mode) {
                    DisplayMode.ORIGINAL -> {
                        Log.d(TAG, "Mode: ORIGINAL")
                        imageProcessor.loadOriginal(contentResolver, uri)
                    }
                    DisplayMode.LINE_ART -> {
                        Log.d(TAG, "Mode: LINE_ART (ML Kit + Color Dodge)")
                        imageProcessor.loadAndProcess(
                            contentResolver = contentResolver,
                            uri             = uri,
                            threshold       = _uiState.value.edgeThreshold
                        )
                    }
                }

                if (_requestRevision.get() != myRevision) {
                    newBitmap.recycleIfNotRecycled()
                    return@launch
                }

                val newHandle = BitmapHandle(newBitmap)
                val oldHandle = _activeHandle
                _activeHandle    = newHandle
                _bitmapRevision++
                _uiState.update { it.copy(
                    bitmapHandle = newHandle,
                    bitmapId     = _bitmapRevision,
                    isProcessing = false,
                    savedScale   = 1f, savedRotation = 0f,
                    savedOffsetX = 0f, savedOffsetY  = 0f
                ) }
                oldHandle?.release()

            } catch (e: CancellationException) {
                _uiState.update { it.copy(isProcessing = false) }
                throw e
            } catch (e: Exception) {
                if (_requestRevision.get() == myRevision) {
                    Log.e(TAG, "Error: ${e.message}", e)
                    _uiState.update { it.copy(
                        isProcessing    = false,
                        processingError = "Lỗi xử lý: ${e.localizedMessage}"
                    ) }
                }
            }
        }
    }

    fun updateOpacity(opacity: Float) { _uiState.update { it.copy(opacity = opacity.coerceIn(0f, 1f)) } }
    fun toggleLock()                   { _uiState.update { it.copy(isLocked = !it.isLocked) } }
    fun onCameraError(msg: String)     { _uiState.update { it.copy(cameraError = msg) } }
    fun clearCameraError()             { _uiState.update { it.copy(cameraError = null) } }
    fun clearError()                   { _uiState.update { it.copy(processingError = null) } }
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
        // V9 MỚI: đóng ML Kit segmenter để giải phóng resources
        imageProcessor.close()
        Log.d(TAG, "ViewModel cleared, ML Kit closed")
    }

    companion object { private const val TAG = "ArDrawingViewModel" }
}
