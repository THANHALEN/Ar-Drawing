package com.example.ardrawing.viewmodel

import android.net.Uri
import com.example.ardrawing.data.BitmapHandle

data class ArDrawingUiState(
    val bitmapHandle    : BitmapHandle? = null,
    val bitmapId        : Int           = 0,
    val isProcessing    : Boolean       = false,
    val processingError : String?       = null,
    val cameraError     : String?       = null,
    val opacity         : Float         = 0.6f,
    val isLocked        : Boolean       = false,

    // V7 MỚI: chế độ hiển thị
    // ORIGINAL  = ảnh gốc màu làm mờ (giống app Store)
    // LINE_ART  = Sobel edges (pipeline hiện tại)
    val displayMode     : DisplayMode   = DisplayMode.LINE_ART,

    // V7 MỚI: lưu URI để re-process khi toggle mode
    val savedUri        : Uri?          = null,

    // Gesture transform (survive config change)
    val savedScale      : Float         = 1f,
    val savedRotation   : Float         = 0f,
    val savedOffsetX    : Float         = 0f,
    val savedOffsetY    : Float         = 0f,

    val edgeThreshold   : Int           = 30
)
