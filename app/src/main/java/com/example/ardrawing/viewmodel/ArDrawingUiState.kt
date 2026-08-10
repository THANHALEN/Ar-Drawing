package com.example.ardrawing.viewmodel

import com.example.ardrawing.data.BitmapHandle

data class ArDrawingUiState(
    val bitmapHandle: BitmapHandle? = null,
    val bitmapId: Int = 0,              // Tăng mỗi khi ảnh mới được load
    val isProcessing: Boolean = false,
    val processingError: String? = null,
    val cameraError: String? = null,
    val opacity: Float = 0.6f,
    val isLocked: Boolean = false,
    val savedScale: Float    = 1f,
    val savedRotation: Float = 0f,
    val savedOffsetX: Float  = 0f,
    val savedOffsetY: Float  = 0f,
    val edgeThreshold: Int = 30
)
