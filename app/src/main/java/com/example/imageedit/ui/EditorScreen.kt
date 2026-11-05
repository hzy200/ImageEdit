package com.example.imageedit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.imageedit.BuildConfig
import com.example.imageedit.core.assets.AssetLibrary
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.image.ColorAdjustMatrix
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.GlColorMatrix
import com.example.imageedit.core.render.ImageViewport
import com.example.imageedit.core.render.PreviewDraft
import com.example.imageedit.core.render.PreviewGeometry
import com.example.imageedit.core.render.PreviewViewport
import com.example.imageedit.core.render.RenderBackend
import com.example.imageedit.core.render.RenderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel = viewModel()) {
    var showColorDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showGarmentColorDialog by remember { mutableStateOf(false) }
    var showTextureDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var compareMode by remember { mutableStateOf(false) }
    var compareFraction by remember { mutableFloatStateOf(0.5f) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var backend by remember { mutableStateOf(RenderSettings.backend) }
    val glCapable = remember(context) { GlCapabilities.supportsGles3(context) }
    var glFailed by remember { mutableStateOf(false) }
    var previewDraft by remember { mutableStateOf<PreviewDraft?>(null) }
    val useGl = glCapable && backend.allowsGles() && !glFailed
    val selectionMask = viewModel.image?.masks?.get("selection")
    val garmentMask = viewModel.image?.masks?.get("garment")

    LaunchedEffect(context) {
        android.util.Log.i("GlPreview", GlCapabilities.describe(context))
    }

    LaunchedEffect(viewModel.error) {
        viewModel.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.openImage(uri)
    }
    val exportJpegLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { uri ->
        if (uri != null) viewModel.export(uri, ExportFormat.JPEG)
    }
    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) viewModel.export(uri, ExportFormat.PNG)
    }
    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.replaceBackgroundWithUri(uri)
    }
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingExport?.invoke()
        pendingExport = null
    }
    val launchExport: (() -> Unit) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("一句话修图 · M3") },
                actions = {
                    if (BuildConfig.DEBUG) {
                        TextButton(
                            onClick = {
                                val next = if (backend == RenderBackend.CPU) {
                                    RenderBackend.AUTO
                                } else {
                                    RenderBackend.CPU
                                }
                                RenderSettings.set(context, next)
                                backend = next
                                glFailed = false
                                previewDraft = null
                            },
                        ) {
                            Text(if (useGl) "GL" else "CPU")
                        }
                    }
                    TextButton(onClick = { showApiKeyDialog = true }) {
                        Text(if (viewModel.hasApiKey) "AI Key ✓" else "AI Key")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val image = viewModel.image
            if (image == null) {
                EmptyState(
                    message = viewModel.message,
                    onPick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            } else {
                EditorContent(
                    image = image,
                    originalImage = viewModel.originalImage,
                    compareMode = compareMode,
                    compareFraction = compareFraction,
                    onCompareFraction = { compareFraction = it },
                    onToggleCompare = { compareMode = !compareMode },
                    useGl = useGl,
                    previewDraft = previewDraft,
                    onGlFailure = { glFailed = true },
                    message = viewModel.planProgress.ifBlank {
                        viewModel.planMessage ?: viewModel.message
                    },
                    instruction = viewModel.instruction,
                    onInstructionChange = viewModel::updateInstruction,
                    onParse = viewModel::parseInstruction,
                    selecting = selecting,
                    selection = viewModel.selection,
                    onToggleSelection = { selecting = !selecting },
                    onSelection = { left, top, right, bottom ->
                        viewModel.setSelection(left, top, right, bottom)
                        selecting = false
                    },
                    busy = viewModel.busy,
                    canUndo = viewModel.history.isNotEmpty(),
                    onAdjustColor = {
                        previewDraft = GlColorMatrix.from(
                            ColorAdjustMatrix.build(0f, 0f, 1f),
                        ).copy(mask = selectionMask)
                        showColorDialog = true
                    },
                    onCropSquare = viewModel::cropSquare,
                    onRotate = viewModel::rotate90,
                    onFlip = viewModel::flipHorizontal,
                    onCutout = viewModel::cutout,
                    onBackground = { showBackgroundDialog = true },
                    onRecolorGarment = { showGarmentColorDialog = true },
                    onTexture = { showTextureDialog = true },
                    onUndo = viewModel::undo,
                    onExportJpeg = {
                        launchExport {
                            exportJpegLauncher.launch("imageedit-${System.currentTimeMillis()}.jpg")
                        }
                    },
                    onExportPng = {
                        launchExport {
                            exportPngLauncher.launch("imageedit-${System.currentTimeMillis()}.png")
                        }
                    },
                    onPickAnother = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
        }
    }

    if (showColorDialog) {
        ColorDialog(
            hasSelection = selectionMask != null,
            onPreview = { brightness, contrast, saturation, onlySelection ->
                previewDraft = GlColorMatrix.from(
                    ColorAdjustMatrix.build(brightness, contrast, saturation),
                ).copy(mask = if (onlySelection) selectionMask else null)
            },
            onDismiss = {
                previewDraft = null
                showColorDialog = false
            },
            onApply = { brightness, contrast, saturation, onlySelection ->
                previewDraft = null
                viewModel.applyAdjustColor(
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    applyToSelection = onlySelection,
                )
                showColorDialog = false
            },
        )
    }

    if (showBackgroundDialog) {
        BackgroundDialog(
            onDismiss = {
                previewDraft = null
                showBackgroundDialog = false
            },
            onPreview = { choice, bitmap ->
                previewDraft = when (choice) {
                    is BackgroundChoice.Solid -> PreviewDraft.Background(
                        color = android.graphics.Color.parseColor(choice.hex),
                        bitmap = null,
                        fit = "cover",
                        bokeh = 0f,
                        contactShadow = false,
                    )

                    is BackgroundChoice.Preset -> bitmap?.let {
                        PreviewDraft.Background(
                            color = null,
                            bitmap = it,
                            fit = "cover",
                            bokeh = 6f,
                            contactShadow = true,
                        )
                    }
                }
            },
            onApply = { choice ->
                previewDraft = null
                when (choice) {
                    is BackgroundChoice.Solid -> viewModel.replaceBackground(choice.hex)
                    is BackgroundChoice.Preset -> viewModel.replaceBackgroundPreset(choice.id)
                }
                showBackgroundDialog = false
            },
            onLocal = {
                previewDraft = null
                showBackgroundDialog = false
                backgroundPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (showGarmentColorDialog) {
        GarmentColorDialog(
            mask = garmentMask,
            busy = viewModel.busy,
            onPrepareMask = viewModel::prepareGarmentMask,
            onPreview = { hue ->
                garmentMask?.let {
                    previewDraft = PreviewDraft.GarmentHue(it, hue, 1.1f, true)
                }
            },
            onDismiss = {
                previewDraft = null
                showGarmentColorDialog = false
            },
            onApply = { hue ->
                previewDraft = null
                viewModel.recolorGarment(hue)
                showGarmentColorDialog = false
            },
        )
    }

    if (showTextureDialog) {
        TextureDialog(
            mask = garmentMask,
            busy = viewModel.busy,
            onPrepareMask = viewModel::prepareGarmentMask,
            onPreview = { texture, pattern, strength ->
                garmentMask?.let {
                    previewDraft = PreviewDraft.Texture(it, pattern, strength)
                }
            },
            onDismiss = {
                previewDraft = null
                showTextureDialog = false
            },
            onPick = { texture, strength ->
                previewDraft = null
                viewModel.applyGarmentTexture(texture, strength)
                showTextureDialog = false
            },
        )
    }

    viewModel.plan?.let { plan ->
        PlanDialog(
            plan = plan,
            progress = viewModel.planProgress,
            busy = viewModel.busy,
            onConfirm = viewModel::executePlan,
            onDismiss = viewModel::cancelPlan,
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            hasKey = viewModel.hasApiKey,
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                viewModel.saveApiKey(key)
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.saveApiKey("")
                showApiKeyDialog = false
            },
        )
    }
}

@Composable
private fun EmptyState(message: String, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("选择一张图片开始")
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPick) {
            Text("选择图片")
        }
    }
}

@Composable
private fun EditorContent(
    image: ImageContext,
    originalImage: ImageContext?,
    compareMode: Boolean,
    compareFraction: Float,
    onCompareFraction: (Float) -> Unit,
    onToggleCompare: () -> Unit,
    useGl: Boolean,
    previewDraft: PreviewDraft?,
    onGlFailure: () -> Unit,
    message: String,
    instruction: String,
    onInstructionChange: (String) -> Unit,
    onParse: () -> Unit,
    selecting: Boolean,
    selection: RegionRequest?,
    onToggleSelection: () -> Unit,
    onSelection: (Float, Float, Float, Float) -> Unit,
    busy: Boolean,
    canUndo: Boolean,
    onAdjustColor: () -> Unit,
    onCropSquare: () -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onCutout: () -> Unit,
    onBackground: () -> Unit,
    onRecolorGarment: () -> Unit,
    onTexture: () -> Unit,
    onUndo: () -> Unit,
    onExportJpeg: () -> Unit,
    onExportPng: () -> Unit,
    onPickAnother: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101010)),
        ) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            var glViewport by remember { mutableStateOf(PreviewViewport()) }
            val displayViewport = if (useGl) {
                PreviewGeometry.compute(
                    imageWidth = image.width,
                    imageHeight = image.height,
                    surfaceWidth = boxW.roundToInt(),
                    surfaceHeight = boxH.roundToInt(),
                    viewport = glViewport,
                )
            } else {
                val fitScale = min(boxW / image.width, boxH / image.height)
                val fitWidth = image.width * fitScale
                val fitHeight = image.height * fitScale
                ImageViewport(
                    left = (boxW - fitWidth) / 2f,
                    top = (boxH - fitHeight) / 2f,
                    width = fitWidth,
                    height = fitHeight,
                )
            }
            val displayW = displayViewport.width
            val displayH = displayViewport.height
            val offsetX = displayViewport.left
            val offsetY = displayViewport.top
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragEnd by remember { mutableStateOf<Offset?>(null) }

            if (useGl) {
                GlCanvas(
                    result = image.bitmap,
                    original = originalImage?.bitmap,
                    compareMode = compareMode,
                    compareFraction = compareFraction,
                    draft = previewDraft,
                    gesturesEnabled = !selecting,
                    onViewportChanged = { glViewport = it },
                    onGlFailure = onGlFailure,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (compareMode && originalImage != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val after = image.bitmap.asImageBitmap()
                    val before = originalImage.bitmap.asImageBitmap()
                    drawImage(
                        image = after,
                        dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                        dstSize = IntSize(displayW.toInt(), displayH.toInt()),
                    )
                    clipRect(
                        left = offsetX,
                        top = offsetY,
                        right = offsetX + displayW * compareFraction,
                        bottom = offsetY + displayH,
                    ) {
                        drawImage(
                            image = before,
                            dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(displayW.toInt(), displayH.toInt()),
                        )
                    }
                    val dividerX = offsetX + displayW * compareFraction
                    drawLine(
                        color = Color.Yellow,
                        start = Offset(dividerX, offsetY),
                        end = Offset(dividerX, offsetY + displayH),
                        strokeWidth = 3f,
                    )
                }
            } else {
                Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = "编辑画布",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            if (selecting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selecting) {
                            detectDragGestures(
                                onDragStart = {
                                    dragStart = it
                                    dragEnd = it
                                },
                                onDrag = { change, _ ->
                                    dragEnd = change.position
                                    change.consume()
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragEnd
                                    if (start != null && end != null) {
                                        val l = ((min(start.x, end.x) - offsetX) / displayW)
                                            .coerceIn(0f, 1f)
                                        val t = ((min(start.y, end.y) - offsetY) / displayH)
                                            .coerceIn(0f, 1f)
                                        val r = ((max(start.x, end.x) - offsetX) / displayW)
                                            .coerceIn(0f, 1f)
                                        val b = ((max(start.y, end.y) - offsetY) / displayH)
                                            .coerceIn(0f, 1f)
                                        if (r - l > 0.01f && b - t > 0.01f) {
                                            onSelection(l, t, r, b)
                                        }
                                    }
                                    dragStart = null
                                    dragEnd = null
                                },
                                onDragCancel = {
                                    dragStart = null
                                    dragEnd = null
                                },
                            )
                        },
                )
                Text(
                    text = "拖拽框选区域",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val start = dragStart
                val end = dragEnd
                if (start != null && end != null) {
                    val left = min(start.x, end.x).coerceIn(offsetX, offsetX + displayW)
                    val top = min(start.y, end.y).coerceIn(offsetY, offsetY + displayH)
                    val right = max(start.x, end.x).coerceIn(offsetX, offsetX + displayW)
                    val bottom = max(start.y, end.y).coerceIn(offsetY, offsetY + displayH)
                    drawRect(
                        color = Color.Yellow,
                        topLeft = Offset(left, top),
                        size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                        style = Stroke(width = 3f),
                    )
                } else {
                    selection?.rect?.let { rect ->
                        drawRect(
                            color = Color.Yellow,
                            topLeft = Offset(
                                offsetX + rect.left * displayW,
                                offsetY + rect.top * displayH,
                            ),
                            size = Size(
                                (rect.right - rect.left) * displayW,
                                (rect.bottom - rect.top) * displayH,
                            ),
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("一句话指令，如：抠图换白底") },
            )
            Button(
                onClick = onParse,
                enabled = !busy,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("解析")
            }
        }

        Text(
            text = "能力范围：去背/换底、调色、裁剪、背景替换、换色/面料；不支持换款式或生成不存在的场景。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        if (compareMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("前后对比")
                Slider(
                    value = compareFraction,
                    onValueChange = onCompareFraction,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, enabled = !busy && canUndo) {
                Text("撤销")
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onAdjustColor, enabled = !busy) { Text("调色") }
            OutlinedButton(onClick = onCropSquare, enabled = !busy) { Text("裁剪 1:1") }
            OutlinedButton(onClick = onRotate, enabled = !busy) { Text("旋转 90°") }
            OutlinedButton(onClick = onFlip, enabled = !busy) { Text("翻转") }
            OutlinedButton(onClick = onCutout, enabled = !busy) { Text("抠图") }
            OutlinedButton(onClick = onBackground, enabled = !busy) { Text("背景") }
            OutlinedButton(onClick = onRecolorGarment, enabled = !busy) { Text("换色") }
            OutlinedButton(onClick = onTexture, enabled = !busy) { Text("面料") }
            OutlinedButton(
                onClick = onToggleSelection,
                enabled = !busy,
            ) {
                Text(if (selecting) "结束选区" else "选区")
            }
            OutlinedButton(onClick = onToggleCompare, enabled = !busy) {
                Text(if (compareMode) "退出对比" else "对比")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onExportJpeg, enabled = !busy) { Text("导出 JPEG") }
            OutlinedButton(onClick = onExportPng, enabled = !busy) { Text("导出 PNG") }
            TextButton(onClick = onPickAnother, enabled = !busy) { Text("换图") }
        }
    }
}

@Composable
private fun ColorDialog(
    hasSelection: Boolean,
    onPreview: (Float, Float, Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApply: (Float, Float, Float, Boolean) -> Unit,
) {
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var onlySelection by remember(hasSelection) { mutableStateOf(hasSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调色") },
        text = {
            Column {
                if (hasSelection) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = onlySelection,
                            onCheckedChange = {
                                onlySelection = it
                                onPreview(brightness, contrast, saturation, it)
                            },
                        )
                        Text("仅选区")
                    }
                }
                LabeledSlider("亮度", brightness, -100f..100f) {
                    brightness = it
                    onPreview(brightness, contrast, saturation, onlySelection)
                }
                LabeledSlider("对比度", contrast, -100f..100f) {
                    contrast = it
                    onPreview(brightness, contrast, saturation, onlySelection)
                }
                LabeledSlider("饱和度", saturation, 0f..2f) {
                    saturation = it
                    onPreview(brightness, contrast, saturation, onlySelection)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(brightness, contrast, saturation, onlySelection)
                },
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$label ${"%.2f".format(value)}")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

private sealed interface BackgroundChoice {
    data class Solid(val hex: String) : BackgroundChoice
    data class Preset(val id: String) : BackgroundChoice
}

private data class BackgroundPreview(val id: String, val bitmap: Bitmap)

@Composable
private fun BackgroundDialog(
    onDismiss: () -> Unit,
    onPreview: (BackgroundChoice, Bitmap?) -> Unit,
    onApply: (BackgroundChoice) -> Unit,
    onLocal: () -> Unit,
) {
    val presets = listOf(
        "preset:studio_gray" to "工作室灰",
        "preset:beach_03" to "海滩",
        "preset:gradient_01" to "青蓝渐变",
        "preset:sunset_02" to "日落",
    )
    val colors = listOf(
        "#FFFFFF" to "白色",
        "#D9D9D9" to "浅灰",
        "#2E5BFF" to "蓝色",
        "#D0021B" to "红色",
        "#111111" to "黑色",
    )
    var selected by remember { mutableStateOf<BackgroundChoice?>(null) }
    val selectedPresetId = (selected as? BackgroundChoice.Preset)?.id
    val presetPreview by produceState<BackgroundPreview?>(
        initialValue = null,
        selectedPresetId,
    ) {
        value = selectedPresetId?.let { id ->
            withContext(Dispatchers.Default) {
                BackgroundPreview(id, AssetLibrary.background(id, 1024, 1024))
            }
        }
    }
    LaunchedEffect(selected, presetPreview) {
        when (val choice = selected) {
            null -> Unit
            is BackgroundChoice.Solid -> onPreview(choice, null)
            is BackgroundChoice.Preset -> {
                val preview = presetPreview
                if (preview != null && preview.id == choice.id) {
                    onPreview(choice, preview.bitmap)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("背景替换") },
        text = {
            Column {
                Text("先选择背景查看预览；预览需要前景已有透明区域，应用时会自动抠图。")
                Spacer(Modifier.height(8.dp))
                Text("预设背景")
                presets.forEach { (id, name) ->
                    TextButton(
                        onClick = { selected = BackgroundChoice.Preset(id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(name)
                    }
                }
                Text("纯色背景")
                colors.forEach { (hex, name) ->
                    TextButton(
                        onClick = { selected = BackgroundChoice.Solid(hex) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("$name  $hex")
                    }
                }
                TextButton(
                    onClick = onLocal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从相册选择背景")
                }
                if (selected != null) {
                    Text("已选择预览背景，点击“应用”生效。")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onApply) },
                enabled = selected != null,
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PlanDialog(
    plan: EditPlan,
    progress: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("执行计划") },
        text = {
            Column {
                Text(plan.understood.ifBlank { "编辑计划" })
                Spacer(Modifier.height(8.dp))
                plan.steps.forEach { step ->
                    Text("${step.id} · ${step.tool}")
                    if (step.args.isNotEmpty()) {
                        Text(
                            step.args.entries.joinToString("，") { (key, value) ->
                                "$key=$value"
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (progress.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(progress)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("执行") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (busy) "取消执行" else "取消") }
        },
    )
}

@Composable
private fun ApiKeyDialog(
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek API Key") },
        text = {
            Column {
                Text(
                    if (hasKey) "已配置 Key；输入新 Key 可覆盖。"
                    else "当前使用离线规则；保存 Key 后可启用云端解析。",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("sk-...") },
                )
                Text("Key 只保存在本机；清除会同时禁用 .env 中的构建时 Key。")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("禁用云端") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun GarmentColorDialog(
    mask: Bitmap?,
    busy: Boolean,
    onPrepareMask: () -> Unit,
    onPreview: (Float) -> Unit,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    val colors = listOf(
        "红色" to 0f,
        "橙色" to 30f,
        "黄色" to 60f,
        "绿色" to 120f,
        "蓝色" to 220f,
        "紫色" to 285f,
    )
    var hue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(mask, hue) {
        if (mask != null) onPreview(hue)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服装换色") },
        text = {
            Column {
                Text("只改色相；褶皱、高光、阴影保持不变。")
                if (mask == null) {
                    Text("首次预览需要先解析服装区域（较慢）。")
                    OutlinedButton(
                        onClick = onPrepareMask,
                        enabled = !busy,
                    ) {
                        Text(if (busy) "解析中…" else "解析服装以预览")
                    }
                }
                LabeledSlider("色相", hue, 0f..360f) { hue = it }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    colors.forEach { (name, value) ->
                        TextButton(onClick = { hue = value }) { Text(name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(hue) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private data class TexturePattern(val id: String, val bitmap: Bitmap)

@Composable
private fun TextureDialog(
    mask: Bitmap?,
    busy: Boolean,
    onPrepareMask: () -> Unit,
    onPreview: (String, Bitmap, Float) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String, Float) -> Unit,
) {
    val textures = listOf(
        "preset:plaid_01" to "格纹",
        "preset:floral_02" to "印花",
    )
    var selectedId by remember { mutableStateOf(textures.first().first) }
    var strength by remember { mutableFloatStateOf(30f) }
    val pattern by produceState<TexturePattern?>(initialValue = null, selectedId) {
        value = withContext(Dispatchers.Default) {
            TexturePattern(selectedId, AssetLibrary.texture(selectedId, 512, 512))
        }
    }
    LaunchedEffect(mask, pattern, strength) {
        val current = pattern ?: return@LaunchedEffect
        if (mask != null) onPreview(current.id, current.bitmap, strength)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("面料图案替换") },
        text = {
            Column {
                Text("用纹理亮度替换 V，保留原 H/S；图案随褶皱弯折。")
                if (mask == null) {
                    Text("首次预览需要先解析服装区域（较慢）。")
                    OutlinedButton(
                        onClick = onPrepareMask,
                        enabled = !busy,
                    ) {
                        Text(if (busy) "解析中…" else "解析服装以预览")
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    textures.forEach { (id, name) ->
                        OutlinedButton(onClick = { selectedId = id }) {
                            Text(name)
                        }
                    }
                }
                LabeledSlider("强度", strength, 0f..100f) { strength = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selectedId, strength) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
