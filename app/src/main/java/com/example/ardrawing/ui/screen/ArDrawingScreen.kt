package com.example.ardrawing.ui.screen

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.livedata.observeAsState
import com.example.ardrawing.ui.TransformState
import com.example.ardrawing.viewmodel.ArDrawingViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

// ============================================================================
// MAIN SCREEN
// ============================================================================

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ArDrawingScreen(
    modifier: Modifier = Modifier,
    viewModel: ArDrawingViewModel = viewModel()
) {
    val context       = LocalContext.current
    val uiState       by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }

    // ── Permission: chỉ cần CAMERA ─────────────────────────────────────────
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // V5 fix #5: phân biệt chưa từng xin vs permanently denied
    var hasEverRequestedCamera by rememberSaveable { mutableStateOf(false) }
    val isPermanentlyDenied = hasEverRequestedCamera &&
        !cameraPermission.status.isGranted &&
        !cameraPermission.status.shouldShowRationale

    // ── Image Picker ────────────────────────────────────────────────────────
    // PickVisualMedia KHÔNG cần storage permission — URI scoped access
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.loadAndProcessImage(context.contentResolver, it) }
    }

    // ── Camera State ────────────────────────────────────────────────────────
    // Camera object giữ ở Composable level (lifecycle resource, không vào ViewModel)
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Torch state từ LiveData thực của CameraX (không phải optimistic update)
    val torchLiveData = remember(camera) {
        camera?.cameraInfo?.torchState ?: MutableLiveData(TorchState.OFF)
    }
    val torchState   by torchLiveData.observeAsState(TorchState.OFF)
    val isFlashOn    = torchState == TorchState.ON
    val hasFlashUnit = remember(camera) { camera?.cameraInfo?.hasFlashUnit() == true }

    // ── Transform State (Gesture) ───────────────────────────────────────────
    // Stable holder — child cập nhật trực tiếp, KHÔNG callback lên parent
    // → ZERO parent recomposition trong gesture loop (60fps)
    val transformState = remember {
        TransformState(
            initialScale    = uiState.savedScale,
            initialRotation = uiState.savedRotation,
            initialOffset   = Offset(uiState.savedOffsetX, uiState.savedOffsetY)
        )
    }

    // V5 fix #2: sessionStartBitmapId — phân biệt config change vs ảnh mới
    // Config change: ViewModel survive → bitmapId KHÔNG tăng → KHÔNG reset transform
    // Chọn ảnh mới: bitmapId tăng → reset transform
    val sessionStartBitmapId = remember { uiState.bitmapId }
    LaunchedEffect(uiState.bitmapId) {
        if (uiState.bitmapId > sessionStartBitmapId) {
            transformState.reset()
        }
    }

    // ── Opacity Local State ─────────────────────────────────────────────────
    // Local để Slider mượt (không qua ViewModel mỗi event)
    // Chỉ commit vào ViewModel khi user thả tay (onValueChangeFinished)
    var localOpacity by remember { mutableFloatStateOf(uiState.opacity) }
    LaunchedEffect(uiState.opacity) { localOpacity = uiState.opacity }

    // ── BitmapHandle Lifecycle ──────────────────────────────────────────────
    // V5 fix #bitmap-race: retain khi UI bắt đầu dùng, release khi dispose
    // Đảm bảo bitmap KHÔNG bị recycle khi Compose vẫn đang render
    val bitmapHandle = uiState.bitmapHandle
    DisposableEffect(bitmapHandle) {
        bitmapHandle?.retain()
        onDispose { bitmapHandle?.release() }
    }

    // ── Sync Gesture → ViewModel khi dispose ───────────────────────────────
    // Chạy khi xoay màn hình (config change) hoặc navigate away
    val transformRef by rememberUpdatedState(transformState)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveGestureTransform(
                scale    = transformRef.scale,
                rotation = transformRef.rotation,
                offsetX  = transformRef.offset.x,
                offsetY  = transformRef.offset.y
            )
        }
    }

    // ── Error Handling ──────────────────────────────────────────────────────
    LaunchedEffect(uiState.processingError) {
        uiState.processingError?.let {
            snackbarState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.cameraError) {
        uiState.cameraError?.let {
            snackbarState.showSnackbar("Lỗi camera: $it")
            viewModel.clearCameraError()
        }
    }

    // ── Permission Gate ─────────────────────────────────────────────────────
    if (!cameraPermission.status.isGranted) {
        PermissionRequestScreen(
            isPermanentlyDenied = isPermanentlyDenied,
            onRequestPermission = {
                hasEverRequestedCamera = true
                cameraPermission.launchPermissionRequest()
            },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        )
        return
    }

    // ── Main UI ─────────────────────────────────────────────────────────────
    Box(modifier = modifier.fillMaxSize()) {

        // Layer 1 (Bottom): Camera live preview
        CameraPreviewLayer(
            modifier      = Modifier.fillMaxSize(),
            onCameraReady = { cam -> camera = cam },
            onCameraError = { msg -> viewModel.onCameraError(msg) }
        )

        // Layer 2 (Middle): Line art overlay
        val bitmap = bitmapHandle?.bitmap?.takeIf { !it.isRecycled }
        if (bitmap != null) {
            ImageOverlayLayer(
                bitmap         = bitmap,
                opacity        = localOpacity,
                isLocked       = uiState.isLocked,
                transformState = transformState
            )
        } else if (!uiState.isProcessing) {
            NoImagePlaceholder(modifier = Modifier.fillMaxSize())
        }

        // Layer 3 (Top): Processing indicator
        AnimatedVisibility(
            visible  = uiState.isProcessing,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier            = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment    = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text  = "Đang xử lý ảnh...",
                        color = Color.White, fontSize = 16.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = "Otsu auto-threshold đang tính...",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            }
        }

        // Layer 4 (Top): Control panel
        ControlPanel(
            modifier                = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            opacity                 = localOpacity,
            isLocked                = uiState.isLocked,
            isFlashOn               = isFlashOn,
            hasFlashUnit            = hasFlashUnit,
            hasImage                = bitmap != null && !uiState.isProcessing,
            onOpacityChange         = { localOpacity = it },
            onOpacityChangeFinished = { viewModel.updateOpacity(localOpacity) },
            onPickImage             = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onToggleLock            = { viewModel.toggleLock() },
            onToggleFlash           = {
                camera?.let { cam ->
                    if (cam.cameraInfo.hasFlashUnit()) {
                        val on = cam.cameraInfo.torchState.value == TorchState.ON
                        // V5 fix #7: đọc kết quả Future để bắt lỗi enableTorch
                        val torchFuture = cam.cameraControl.enableTorch(!on)
                        torchFuture.addListener({
                            try {
                                torchFuture.get()
                            } catch (e: java.util.concurrent.ExecutionException) {
                                Log.e("Flash", "Torch failed: ${e.cause?.message}")
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                }
            },
            onResetTransform        = {
                transformState.reset()
                // Commit ngay vào ViewModel, không đợi onDispose
                viewModel.saveGestureTransform(1f, 0f, 0f, 0f)
            }
        )

        // Snackbar
        SnackbarHost(
            hostState = snackbarState,
            modifier  = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )
    }
}

// ============================================================================
// CAMERA PREVIEW LAYER
// ============================================================================

/**
 * CameraPreviewLayer — Wrapper CameraX PreviewView trong Compose.
 *
 * V5 fix #7: toàn bộ camera init trong try/catch (kể cả future.get())
 * V5 fix: disposed guard tránh callback đến muộn sau onDispose
 * V5 fix: fallback sang front camera nếu không có camera sau
 */
@Composable
fun CameraPreviewLayer(
    modifier      : Modifier = Modifier,
    onCameraReady : (Camera) -> Unit,
    onCameraError : (String) -> Unit
) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // PreviewView tạo một lần — không recreate khi recompose
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams        = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            // COMPATIBLE = TextureView (không phải SurfaceView)
            // Cho phép graphicsLayer BlendMode.Multiply blend qua camera content
            implementationMode  = PreviewView.ImplementationMode.COMPATIBLE
            scaleType           = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        var disposed: Boolean = false
        var cameraProvider: ProcessCameraProvider? = null

        val future   = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            if (disposed) return@Runnable

            // V5 fix #7: future.get() BÊN TRONG try/catch
            // ExecutionException từ CameraX có thể crash main thread nếu để ngoài
            try {
                cameraProvider = future.get()

                // Chọn camera: ưu tiên camera sau, fallback camera trước
                val selector = when {
                    cameraProvider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        if (!disposed) onCameraError("Không tìm thấy camera")
                        return@Runnable
                    }
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                cameraProvider!!.unbindAll()  // Tránh duplicate binding
                val cam = cameraProvider!!.bindToLifecycle(lifecycleOwner, selector, preview)
                if (!disposed) onCameraReady(cam)

            } catch (e: Exception) {
                Log.e("CameraLayer", "Camera init thất bại: ${e.message}", e)
                if (!disposed) onCameraError(e.localizedMessage ?: "Lỗi khởi tạo camera")
            }
        }

        future.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true           // Guard: tránh callback đến muộn xử lý
            cameraProvider?.unbindAll()  // Giải phóng camera resource
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

// ============================================================================
// IMAGE OVERLAY LAYER — V6 UPGRADE: BlendMode.Multiply
// ============================================================================

/**
 * ImageOverlayLayer — Hiển thị line art lên camera với gesture support.
 *
 * V6 THAY ĐỔI CHÍNH: BlendMode.Multiply thay vì SrcOver mặc định.
 *
 * Tại sao Multiply tốt hơn cho line art overlay:
 *
 *   SrcOver (V5):
 *     result = src_color × src_alpha + dst × (1 - src_alpha)
 *     → Nét đen "phủ" lên camera với opacity% → trông nhân tạo, nổi
 *
 *   Multiply (V6):
 *     result = src_color × dst_color / 255
 *     → transparent pixel (alpha=0): camera × anything/255 × 0 → camera pass-through
 *     → dark_gray pixel (alpha=255, gray=100): camera × 100/255 = camera × 0.39 (darker)
 *     → black pixel (alpha=255, gray=0): camera × 0 = 0 → pitch black line
 *     → Nét như "bút chì vẽ trên bề mặt camera feed" — tự nhiên hơn
 *
 * CompositingStrategy.Offscreen:
 *     Render composable vào offscreen buffer TRƯỚC khi blend
 *     → opacity (alpha) được áp dụng đúng trước khi Multiply
 *     → Không có artifact khi alpha < 1.0
 *
 * Điều kiện: ImageProcessor phải output transparent bg + gray edges
 *     (V6 ImageProcessor đã output đúng định dạng này)
 *
 * Gesture: pointerInput(isLocked) → coroutine restart khi lock state đổi
 *     isLocked=true: lambda rỗng → touch xuyên qua layer xuống camera
 */
@Composable
fun ImageOverlayLayer(
    bitmap         : Bitmap,
    opacity        : Float,
    isLocked       : Boolean,
    transformState : TransformState
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Image(
        bitmap             = imageBitmap,
        contentDescription = "Ảnh đường nét để đồ",
        contentScale       = ContentScale.Fit,
        modifier           = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // ── Transform (Zoom / Rotate / Pan) ──────────────────────
                // TransformState được cập nhật trực tiếp bởi child gesture
                // → graphicsLayer đọc state → chỉ invalidate draw phase
                // → KHÔNG trigger recomposition của parent (zero-recompose gesture)
                scaleX       = transformState.scale
                scaleY       = transformState.scale
                rotationZ    = transformState.rotation
                translationX = transformState.offset.x
                translationY = transformState.offset.y

                // ── Opacity ───────────────────────────────────────────────
                // Slider "Độ mờ" controls overall visibility
                // Với Multiply blend:
                //   opacity=1.0 → full Multiply effect (nét rõ nhất)
                //   opacity=0.6 → moderate (default, cân bằng)
                //   opacity=0.0 → overlay ẩn hoàn toàn
                alpha = opacity

                // ── V6: BlendMode.Multiply ────────────────────────────────
                // Thay thế SrcOver mặc định để blend tự nhiên với camera feed
                blendMode = BlendMode.Multiply

                // ── CompositingStrategy.Offscreen ─────────────────────────
                // Bắt buộc khi dùng BlendMode không phải SrcOver với alpha < 1.0
                // Đảm bảo: render → apply alpha → Multiply blend (đúng thứ tự)
                // Nếu thiếu: alpha và BlendMode tương tác sai → artifact
                compositingStrategy = CompositingStrategy.Offscreen
            }
            // ── Gesture: Pinch/Zoom/Rotate/Pan ───────────────────────────
            // KEY = isLocked: khi lock state đổi → coroutine restart
            // isLocked=true: lambda rỗng → KHÔNG consume gesture events
            //   → touch xuyên qua xuống camera preview layer
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectTransformGestures { _, pan, zoom, rotationDelta ->
                        // Cập nhật TransformState trực tiếp — KHÔNG callback lên parent
                        transformState.update(
                            scale    = (transformState.scale * zoom).coerceIn(0.1f, 10f),
                            rotation = transformState.rotation + rotationDelta,
                            offset   = Offset(
                                x = transformState.offset.x + pan.x,
                                y = transformState.offset.y + pan.y
                            )
                        )
                    }
                }
            }
    )
}

// ============================================================================
// CONTROL PANEL
// ============================================================================

/**
 * ControlPanel — Thanh điều khiển dưới màn hình.
 *
 * Controls:
 *   • Slider "Độ mờ": 0→1, cập nhật local state ngay (Slider mượt),
 *     commit vào ViewModel khi thả tay (onValueChangeFinished)
 *   • Chọn ảnh: PickVisualMedia (không cần storage permission)
 *   • Khóa hình: disable gesture trên overlay
 *   • Đèn pin: toggle torch (chỉ hiện nếu hasFlashUnit)
 *   • Đặt lại: reset transform về center
 */
@Composable
fun ControlPanel(
    modifier                : Modifier = Modifier,
    opacity                 : Float,
    isLocked                : Boolean,
    isFlashOn               : Boolean,
    hasFlashUnit            : Boolean,
    hasImage                : Boolean,
    onOpacityChange         : (Float) -> Unit,
    onOpacityChangeFinished : () -> Unit,
    onPickImage             : () -> Unit,
    onToggleLock            : () -> Unit,
    onToggleFlash           : () -> Unit,
    onResetTransform        : () -> Unit
) {
    Column(
        modifier            = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Opacity Slider ────────────────────────────────────────────────
        Column {
            Row(
                modifier                    = Modifier.fillMaxWidth(),
                horizontalArrangement       = Arrangement.SpaceBetween,
                verticalAlignment           = Alignment.CenterVertically
            ) {
                Text(
                    text       = "Độ mờ",
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text     = "${(opacity * 100).toInt()}%",
                    color    = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
            }
            Slider(
                value                 = opacity,
                onValueChange         = onOpacityChange,
                // onValueChangeFinished: commit vào ViewModel khi thả tay
                // Giảm số lần StateFlow emit — không cần update mỗi pixel di chuyển
                onValueChangeFinished = onOpacityChangeFinished,
                valueRange            = 0f..1f,
                modifier              = Modifier.fillMaxWidth(),
                colors                = SliderDefaults.colors(
                    thumbColor        = MaterialTheme.colorScheme.primary,
                    activeTrackColor  = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }

        // ── Action Buttons ────────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Chọn ảnh
            ControlButton(
                onClick  = onPickImage,
                label    = "Chọn ảnh",
                isActive = false,
                icon     = {
                    Icon(
                        imageVector        = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Chọn ảnh",
                        tint               = Color.White
                    )
                }
            )

            // Khóa / Mở khóa
            ControlButton(
                onClick  = onToggleLock,
                label    = if (isLocked) "Đã khóa" else "Khóa hình",
                isActive = isLocked,
                enabled  = hasImage,
                icon     = {
                    Icon(
                        imageVector        = if (isLocked) Icons.Default.Lock
                                            else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) "Mở khóa" else "Khóa",
                        tint               = if (isLocked) MaterialTheme.colorScheme.primary
                                            else Color.White
                    )
                }
            )

            // Đèn pin (chỉ hiện khi thiết bị có flash)
            if (hasFlashUnit) {
                ControlButton(
                    onClick  = onToggleFlash,
                    label    = if (isFlashOn) "Đèn bật" else "Đèn pin",
                    isActive = isFlashOn,
                    icon     = {
                        Icon(
                            imageVector        = if (isFlashOn) Icons.Default.FlashOn
                                               else Icons.Default.FlashOff,
                            contentDescription = if (isFlashOn) "Tắt đèn" else "Bật đèn",
                            // Màu vàng khi đèn bật → dễ nhận biết
                            tint               = if (isFlashOn) Color(0xFFFFEB3B)
                                               else Color.White
                        )
                    }
                )
            }

            // Đặt lại vị trí
            ControlButton(
                onClick  = onResetTransform,
                label    = "Đặt lại",
                isActive = false,
                enabled  = hasImage,
                icon     = {
                    Icon(
                        imageVector        = Icons.Default.RestartAlt,
                        contentDescription = "Đặt lại vị trí",
                        tint               = Color.White
                    )
                }
            )
        }
    }
}

/**
 * ControlButton — Nút điều khiển: icon tròn + label text.
 * isActive = true → viền highlight + background tinted
 * enabled  = false → mờ 30%
 */
@Composable
fun ControlButton(
    onClick  : () -> Unit,
    icon     : @Composable () -> Unit,
    label    : String,
    isActive : Boolean,
    enabled  : Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick  = onClick,
            enabled  = enabled,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.1f)
                )
                .then(
                    if (isActive) Modifier.border(
                        width  = 1.5.dp,
                        color  = MaterialTheme.colorScheme.primary,
                        shape  = CircleShape
                    ) else Modifier
                ),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor         = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.3f)
            )
        ) {
            icon()
        }

        Text(
            text     = label,
            color    = if (enabled) Color.White.copy(alpha = 0.8f)
                       else Color.White.copy(alpha = 0.3f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ============================================================================
// PLACEHOLDER SCREENS
// ============================================================================

@Composable
fun NoImagePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.5f),
                modifier           = Modifier.size(64.dp)
            )
            Text(
                text      = "Nhấn \"Chọn ảnh\" để tải ảnh tham chiếu\nrồi đặt lên camera để đồ nét",
                color     = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                fontSize  = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * PermissionRequestScreen — Màn hình xin quyền Camera.
 *
 * V5 fix #5: isPermanentlyDenied được xác định đúng nhờ hasEverRequestedCamera:
 *   • Lần đầu mở app (chưa xin lần nào): isPermanentlyDenied = false → nút "Cấp quyền"
 *   • Đã xin nhưng từ chối vĩnh viễn: isPermanentlyDenied = true → nút "Mở Settings"
 */
@Composable
fun PermissionRequestScreen(
    isPermanentlyDenied : Boolean,
    onRequestPermission : () -> Unit,
    onOpenSettings      : () -> Unit
) {
    Box(
        modifier         = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.CameraAlt,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(72.dp)
            )

            Text(
                text       = "AR Drawing",
                color      = Color.White,
                fontSize   = 24.sp,
                fontWeight = FontWeight.Bold
            )

            if (isPermanentlyDenied) {
                Text(
                    text      = "Quyền Camera bị từ chối vĩnh viễn.\nVui lòng mở Settings để cấp quyền.",
                    color     = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize  = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Mở Settings", fontSize = 15.sp)
                }
            } else {
                Text(
                    text      = "App cần quyền Camera để hoạt động.\nẢnh từ thư viện không cần quyền bổ sung.",
                    color     = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize  = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Cấp quyền Camera", fontSize = 15.sp)
                }
            }
        }
    }
}
