package com.example.ardrawing.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Stable gesture transform holder.
 * Parent tạo một lần → truyền xuống child → child cập nhật trực tiếp.
 * Zero parent recomposition khi gesture 60fps.
 */
class TransformState(
    initialScale: Float    = 1f,
    initialRotation: Float = 0f,
    initialOffset: Offset  = Offset.Zero
) {
    var scale    by mutableFloatStateOf(initialScale)
    var rotation by mutableFloatStateOf(initialRotation)
    var offset   by mutableStateOf(initialOffset)

    fun update(scale: Float, rotation: Float, offset: Offset) {
        this.scale = scale; this.rotation = rotation; this.offset = offset
    }
    fun reset() { scale = 1f; rotation = 0f; offset = Offset.Zero }
}
