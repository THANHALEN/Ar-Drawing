package com.example.ardrawing.viewmodel

/**
 * DisplayMode: chế độ hiển thị ảnh overlay.
 *
 * ORIGINAL  — Ảnh gốc màu làm mờ (giống app AR Drawing trên Store)
 * LINE_ART  — Sobel edge detection → chỉ hiện đường nét (V6 pipeline)
 */
enum class DisplayMode {
    ORIGINAL,
    LINE_ART
}
