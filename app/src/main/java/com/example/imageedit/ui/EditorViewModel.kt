package com.example.imageedit.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.LocalAssetDigest
import com.example.imageedit.core.contracts.PlanValidator
import com.example.imageedit.core.concurrency.ImagePipeline
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.edit.PlanExecutor
import com.example.imageedit.core.edit.SessionStore
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.image.CanvasAnalyzer
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.image.LocalAssetAnalyzer
import com.example.imageedit.core.image.UserUriAllowlist
import com.example.imageedit.core.intent.ApiKeyStore
import com.example.imageedit.core.intent.DeepSeekIntentGateway
import com.example.imageedit.core.intent.IntentGateway
import com.example.imageedit.core.intent.IntentResult
import com.example.imageedit.core.intent.TemplateStore
import com.example.imageedit.core.tools.ToolInvocation
import com.example.imageedit.core.verify.ResultVerifier
import com.example.imageedit.service.ExportService
import com.example.imageedit.service.ExportStatusBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    var image by mutableStateOf<ImageContext?>(null)
        private set

    var originalImage by mutableStateOf<ImageContext?>(null)
        private set

    var history by mutableStateOf<List<ToolInvocation>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf("支持 JPEG/PNG · 预览最长边 2048")
        private set

    var canvasDigest by mutableStateOf<CanvasDigest?>(null)
        private set

    var localAssetDigest by mutableStateOf<LocalAssetDigest?>(null)
        private set

    var instruction by mutableStateOf("")
        private set

    var plan by mutableStateOf<EditPlan?>(null)
        private set

    var planMessage by mutableStateOf<String?>(null)
        private set

    var planProgress by mutableStateOf("")
        private set

    var selection by mutableStateOf<RegionRequest?>(null)
        private set

    var hasApiKey by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var session: EditSession? = null

    @Volatile
    private var sourceUri: Uri? = null

    @Volatile
    private var instructionSnapshot: String = ""

    private var planJob: Job? = null
    private var parseJob: Job? = null
    private var parseToken: Any? = null
    private val intentGateway: IntentGateway by lazy {
        DeepSeekIntentGateway(getApplication())
    }
    private val templateStore by lazy { TemplateStore(getApplication()) }
    private val sessionStore = SessionStore(application)

    init {
        busy = true
        observeExportStatus()
        viewModelScope.launch {
            hasApiKey = withContext(Dispatchers.IO) {
                runCatching { !ApiKeyStore.get(application).isNullOrBlank() }
                    .getOrDefault(false)
            }
            restorePersistedSession()
        }
    }

    fun openImage(uri: Uri) {
        val activeParse = parseJob
        val parseActive = activeParse?.isActive == true
        if (parseActive) {
            parseToken = null
            parseJob = null
            activeParse?.cancel()
        }
        if (busy && !parseActive) return
        planJob?.cancel()
        planJob = null
        busy = true
        viewModelScope.launch {
            message = "正在解码图片…"
            val decoded = runCatching {
                withContext(Dispatchers.IO) {
                    ColorPipeline.loadPreview(getApplication(), uri)
                }
            }
            val context = decoded.getOrNull()
            if (context == null) {
                message = "打开失败：${decoded.exceptionOrNull()?.message ?: "无法解码图片"}"
                error = message
                busy = false
                return@launch
            }

            sourceUri = uri
            UserUriAllowlist.clear()
            session = EditSession(
                original = context,
                onHistoryChanged = { persistSessionNow(uri) },
            )
            image = context
            originalImage = context
            history = emptyList()
            selection = null
            localAssetDigest = null
            plan = null
            planMessage = null
            withContext(Dispatchers.IO) { sessionStore.clear() }
            withContext(ImagePipeline.dispatcher) {
                sessionStore.savePreview(context.bitmap)
            }
            val digest = analyzeDigest(context)
            canvasDigest = digest
            message = CanvasAnalyzer.describe(digest)
            busy = ExportStatusBus.state.value.running
        }
    }

    fun applyAdjustColor(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        applyToSelection: Boolean = false,
    ) {
        apply(
            ToolInvocation(
                tool = "adjust_color",
                args = mapOf(
                    "brightness" to brightness,
                    "contrast" to contrast,
                    "saturation" to saturation,
                    "apply_to" to if (applyToSelection) "selection" else "whole",
                ),
            ),
        )
    }

    fun cropSquare() {
        val current = image ?: return
        val side = minOf(current.width, current.height)
        val left = (current.width - side) / 2f / current.width
        val top = (current.height - side) / 2f / current.height
        val right = 1f - left
        val bottom = 1f - top
        apply(
            ToolInvocation(
                tool = "crop",
                args = mapOf(
                    "left" to left,
                    "top" to top,
                    "right" to right,
                    "bottom" to bottom,
                ),
            ),
        )
    }

    fun rotate90() {
        apply(ToolInvocation("rotate", mapOf("degrees" to "90")))
    }

    fun flipHorizontal() {
        apply(ToolInvocation("flip", mapOf("direction" to "horizontal")))
    }

    fun cutout() {
        apply(
            ToolInvocation(
                tool = "remove_background",
                args = mapOf(
                    "model" to "modnet",
                    "edge_feather" to 1.5,
                    "input_size" to 512,
                ),
            ),
        )
    }

    fun replaceBackground(colorHex: String) {
        applySequence(
            listOf(
                cutoutInvocation(),
                ToolInvocation(
                    tool = "replace_background",
                    args = mapOf(
                        "source" to "solid",
                        "color" to colorHex,
                        "fit" to "cover",
                        "color_transfer" to "none",
                        "contact_shadow" to false,
                        "bokeh" to 0.0,
                        "edge_fusion" to "alpha",
                    ),
                ),
            ),
        )
    }

    fun replaceBackgroundPreset(presetId: String) {
        applySequence(
            listOf(
                cutoutInvocation(),
                ToolInvocation(
                    tool = "replace_background",
                    args = mapOf(
                        "source" to presetId,
                        "fit" to "cover",
                        "color_transfer" to "reinhard_lab",
                        "contact_shadow" to true,
                        "bokeh" to 6.0,
                    ),
                ),
            ),
        )
    }

    fun replaceBackgroundWithUri(backgroundUri: Uri) {
        if (busy) return
        val currentSession = session ?: return
        UserUriAllowlist.allow(backgroundUri.toString())
        busy = true
        viewModelScope.launch {
            try {
                val background = withContext(Dispatchers.IO) {
                    ColorPipeline.loadPreview(getApplication(), backgroundUri, 2048)
                }
                val digest = withContext(ImagePipeline.dispatcher) {
                    coroutineContext.ensureActive()
                    LocalAssetAnalyzer.analyze(background.bitmap)
                }
                localAssetDigest = digest
                canvasDigest = canvasDigest?.copy(localAssetDigest = digest)

                withContext(ImagePipeline.dispatcher) {
                    val executionContext = coroutineContext
                    currentSession.apply(
                        cutoutInvocation(),
                        checkActive = { executionContext.ensureActive() },
                    )
                    currentSession.apply(
                        ToolInvocation(
                            tool = "replace_background",
                            args = mapOf(
                                "source" to "user_local",
                                "background_uri" to backgroundUri.toString(),
                                "fit" to "cover",
                                "color_transfer" to "reinhard_lab",
                                "contact_shadow" to true,
                                "bokeh" to 0.0,
                            ),
                        ),
                        checkActive = { executionContext.ensureActive() },
                    )
                }
                image = currentSession.current
                history = currentSession.history()
                selection = currentSession.current.regionRequest
                canvasDigest = analyzeDigest(currentSession.current)
                message = "已应用本地背景 · ${history.size} 步"
                planMessage = "本地背景摘要：${LocalAssetAnalyzer.describe(digest)}"
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // The first step may have succeeded before the second failed.
                image = currentSession.current
                history = currentSession.history()
                selection = currentSession.current.regionRequest
                canvasDigest = analyzeDigest(currentSession.current)
                message = "本地背景处理失败：${failure.message}"
                error = message
            } finally {
                busy = false
            }
        }
    }

    /**
     * Parses the garment mask once so the preview can reuse it. The dialog may
     * call this explicitly before the user drags a hue/texture slider.
     */
    fun prepareGarmentMask() {
        if (busy) return
        if (image?.masks?.get("garment") != null) return
        applySequence(garmentInvocations())
    }

    fun recolorGarment(hueDegrees: Float) {
        val needsMask = image?.masks?.get("garment") == null
        applySequence(
            (if (needsMask) garmentInvocations() else emptyList()) + ToolInvocation(
                tool = "recolor_hsv",
                args = mapOf(
                    "hue" to hueDegrees,
                    "sat_scale" to 1.1,
                    "preserve_v" to true,
                ),
            ),
        )
    }

    fun applyGarmentTexture(textureId: String, strength: Float = 30f) {
        val needsMask = image?.masks?.get("garment") == null
        applySequence(
            (if (needsMask) garmentInvocations() else emptyList()) + ToolInvocation(
                tool = "apply_texture",
                args = mapOf(
                    "texture" to textureId,
                    "warp_from" to "luminance_sobel",
                    "strength" to strength,
                ),
            ),
        )
    }

    private fun garmentInvocations(): List<ToolInvocation> = listOf(
        ToolInvocation(
            tool = "parse_garment",
            args = mapOf(
                "model" to "schp_atr",
                "classes" to "dress,upper_clothes,skirt,pants,scarf",
            ),
        ),
        ToolInvocation(
            tool = "refine_mask",
            args = mapOf("morphology" to "open_close", "blur" to 3),
        ),
    )

    private fun cutoutInvocation() = ToolInvocation(
        tool = "remove_background",
        args = mapOf(
            "model" to "modnet",
            "edge_feather" to 1.5,
            "input_size" to 512,
        ),
    )

    fun updateInstruction(value: String) {
        instruction = value
        instructionSnapshot = value
        val active = parseJob
        if (active?.isActive == true) {
            parseToken = null
            parseJob = null
            active.cancel()
            busy = false
            planMessage = "已取消上一次解析"
        }
    }

    fun clearError() {
        error = null
    }

    fun saveApiKey(value: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (value.isBlank()) {
                        ApiKeyStore.disableRemote(getApplication())
                    } else {
                        ApiKeyStore.set(getApplication(), value)
                    }
                    !ApiKeyStore.get(getApplication()).isNullOrBlank()
                }
            }
            result.onSuccess { hasKey ->
                hasApiKey = hasKey
                planMessage = if (value.isBlank()) {
                    "已禁用云端解析，使用离线规则/本地模板"
                } else {
                    "已保存 API Key"
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                message = "保存 API Key 失败：${failure.message}"
                error = message
            }
        }
    }

    fun setSelection(left: Float, top: Float, right: Float, bottom: Float) {
        if (busy) return
        apply(
            ToolInvocation(
                tool = "select_region",
                args = mapOf(
                    "left" to left.coerceIn(0f, 1f),
                    "top" to top.coerceIn(0f, 1f),
                    "right" to right.coerceIn(0f, 1f),
                    "bottom" to bottom.coerceIn(0f, 1f),
                ),
            ),
        )
        planMessage = "选区已记录，可以重新解析局部指令"
    }

    fun parseInstruction() {
        val runningParse = parseJob
        if (busy && runningParse?.isActive != true) return
        runningParse?.cancel()
        val digest = canvasDigest ?: run {
            planMessage = "请先打开一张图片"
            return
        }
        if (instruction.isBlank()) {
            planMessage = "请输入一句修图指令"
            return
        }
        val command = instruction
        val token = Any()
        parseToken = token
        busy = true
        planMessage = "正在解析意图…"
        plan = null
        parseJob = viewModelScope.launch {
            try {
                val template = withContext(Dispatchers.IO) {
                    templateStore.find(command)
                }
                if (parseToken !== token) return@launch
                if (template != null && acceptPlan(template, digest, "命中本地模板（离线）")) {
                    return@launch
                }

                val result: IntentResult = intentGateway.parse(command, digest)
                if (parseToken !== token) return@launch
                if (result.requiresSelection) {
                    planMessage = result.message ?: "请先框选区域"
                } else if (result.plan == null) {
                    planMessage = result.message ?: "未生成计划；可尝试手动工具"
                } else {
                    acceptPlan(
                        result.plan,
                        digest,
                        result.message ?: if (!hasApiKey) {
                            "离线规则解析"
                        } else {
                            "计划已生成，请确认后执行"
                        },
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (parseToken === token) {
                    planMessage = "解析失败：${failure.message}"
                    error = planMessage
                }
            } finally {
                if (parseToken === token) {
                    parseToken = null
                    parseJob = null
                    busy = false
                }
            }
        }
    }

    private fun acceptPlan(plan: EditPlan, digest: CanvasDigest, successMessage: String): Boolean {
        val availableAssets = digest.availableAssets.values.flatten().toSet()
        val validation = PlanValidator.validate(
            plan,
            digest,
            availableAssets,
            UserUriAllowlist.snapshot(),
        )
        if (!validation.ok) {
            planMessage = "计划被拦截：${validation.errors.joinToString("；")}"
            error = planMessage
            return false
        }
        this.plan = plan
        planMessage = successMessage
        return true
    }

    fun cancelPlan() {
        val job = planJob
        if (job != null && job.isActive) {
            job.cancel()
            planMessage = "正在停止执行…"
            planProgress = "正在停止…"
            // Keep busy / dialog until the worker actually exits.
        } else {
            plan = null
            planProgress = ""
            planMessage = null
        }
    }

    fun executePlan() {
        if (busy) return
        val currentPlan = plan ?: return
        val currentSession = session ?: return
        val checkpoint = currentSession.history().size
        busy = true
        planJob = viewModelScope.launch {
            try {
                planMessage = "执行中…"
                val result = PlanExecutor.execute(currentPlan, currentSession) { index, step, context ->
                    withContext(Dispatchers.Main) {
                        image = context
                        history = currentSession.history()
                        planProgress = "${index + 1}/${currentPlan.steps.size} · ${step.tool}"
                    }
                }
                image = currentSession.current
                history = currentSession.history()
                canvasDigest = analyzeDigest(currentSession.current)
                if (result.ok) {
                    withContext(Dispatchers.IO) {
                        templateStore.save(instructionSnapshot, currentPlan)
                    }
                    val verification = withContext(ImagePipeline.dispatcher) {
                        val verificationContext = coroutineContext
                        ResultVerifier.verify(
                            currentSession.original,
                            currentSession.current,
                            currentPlan,
                            checkActive = { verificationContext.ensureActive() },
                        )
                    }
                    message = "已完成 ${result.executed} 步"
                    planMessage = if (verification.ok) {
                        "执行完成 · 自检通过"
                    } else {
                        "执行完成 · 自检提醒：${verification.warnings.joinToString("，")}"
                    }
                } else {
                    val retained = (currentSession.history().size - checkpoint).coerceAtLeast(0)
                    message = "执行失败：${result.errors.joinToString("；")}；已保留前 $retained 步"
                    planMessage = message
                    error = message
                }
            } catch (cancel: CancellationException) {
                val retained = (currentSession.history().size - checkpoint).coerceAtLeast(0)
                message = "已取消执行 · 已保留前 $retained 步"
                planMessage = message
                throw cancel
            } finally {
                image = currentSession.current
                history = currentSession.history()
                plan = null
                planProgress = ""
                busy = false
                planJob = null
            }
        }
    }

    fun undo() {
        if (busy) return
        val currentSession = session ?: return
        busy = true
        viewModelScope.launch {
            runCatching {
                withContext(ImagePipeline.dispatcher) {
                    val executionContext = coroutineContext
                    currentSession.undo(checkActive = { executionContext.ensureActive() })
                }
            }.onSuccess { context ->
                image = context
                history = currentSession.history()
                selection = context.regionRequest
                canvasDigest = analyzeDigest(context)
                message = "已撤销 · 剩余 ${history.size} 步"
            }.onFailure {
                if (it is CancellationException) throw it
                message = "撤销失败：${it.message}"
                error = message
            }
            busy = false
        }
    }

    fun export(targetUri: Uri, format: ExportFormat) {
        if (busy) return
        session ?: return
        val uri = sourceUri ?: return
        busy = true
        message = "正在准备导出 ${format.extension.uppercase()}…"
        viewModelScope.launch {
            withContext(Dispatchers.IO) { persistSessionNow(uri) }
            try {
                val intent = Intent(getApplication(), ExportService::class.java)
                    .putExtra(ExportService.EXTRA_SOURCE_URI, uri.toString())
                    .putExtra(ExportService.EXTRA_TARGET_URI, targetUri.toString())
                    .putExtra(ExportService.EXTRA_FORMAT, format.name)
                    .putExtra(ExportService.EXTRA_INSTRUCTION, instructionSnapshot)
                ContextCompat.startForegroundService(getApplication(), intent)
            } catch (failure: Throwable) {
                busy = false
                message = "导出启动失败：${failure.message}"
                error = message
            }
        }
    }

    private fun observeExportStatus() {
        viewModelScope.launch {
            ExportStatusBus.state.collect { state ->
                when {
                    state.running -> {
                        busy = true
                        message = state.progress.ifBlank { "正在导出…" }
                    }

                    state.result != null -> {
                        val result = state.result
                        busy = false
                        message = buildString {
                            append(
                                "已导出 ${result.width}×${result.height} · " +
                                    result.format.extension.uppercase(),
                            )
                            if (result.degraded) append(" · 内存保护降级")
                        }
                        planMessage = message
                        ExportStatusBus.reset()
                    }

                    state.error != null -> {
                        busy = false
                        message = state.error
                        error = state.error
                        planMessage = state.error
                        ExportStatusBus.reset()
                    }
                }
            }
        }
    }

    private fun restorePersistedSession() {
        busy = true
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { sessionStore.load() }
            val uri = saved?.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (saved == null || uri == null) {
                withContext(Dispatchers.IO) { sessionStore.clear() }
                busy = ExportStatusBus.state.value.running
                return@launch
            }
            message = "正在恢复上次会话…"
            UserUriAllowlist.clear()
            allowUserUris(saved.invocations)
            val restored = try {
                val preview = withContext(Dispatchers.IO) {
                    sessionStore.loadPreview()?.let { snapshot ->
                        ImageContext(
                            bitmap = snapshot,
                            sourceWidth = snapshot.width,
                            sourceHeight = snapshot.height,
                            workingScale = 1f,
                            colorSpaceName = "srgb",
                            sourceLabel = uri.toString(),
                        )
                    } ?: ColorPipeline.loadPreview(getApplication(), uri)
                }
                var historyRestored = true
                val restoredSession = withContext(ImagePipeline.dispatcher) {
                    runCatching {
                        EditSession(
                            original = preview,
                            initialHistory = saved.invocations,
                            onHistoryChanged = { persistSessionNow(uri) },
                        )
                    }.getOrElse {
                        historyRestored = false
                        EditSession(
                            original = preview,
                            onHistoryChanged = { persistSessionNow(uri) },
                        )
                    }
                }
                Triple(restoredSession, preview, historyRestored)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }

            if (restored == null) {
                withContext(Dispatchers.IO) { sessionStore.clear() }
                busy = ExportStatusBus.state.value.running
                message = "上次会话无法恢复，请重新选择图片"
                planMessage = "原图访问权限可能已失效"
                return@launch
            }

            val (restoredSession, preview, historyRestored) = restored
            if (!historyRestored) {
                withContext(Dispatchers.IO) { sessionStore.clear() }
            }
            session = restoredSession
            sourceUri = uri
            originalImage = preview
            image = restoredSession.current
            history = restoredSession.history()
            selection = restoredSession.current.regionRequest
            instruction = saved.instruction
            instructionSnapshot = saved.instruction
            localAssetDigest = null
            plan = null
            planMessage = if (historyRestored) {
                "已恢复上次会话 · ${history.size} 步"
            } else {
                "上次编辑历史无法重放，已保留原图"
            }
            val digest = analyzeDigest(restoredSession.current)
            canvasDigest = digest
            message = CanvasAnalyzer.describe(digest)
            busy = ExportStatusBus.state.value.running
        }
    }

    private fun persistSessionNow(uri: Uri? = sourceUri) {
        val current = session ?: return
        sessionStore.save(uri?.toString(), instructionSnapshot, current.history())
    }

    private fun allowUserUris(invocations: List<ToolInvocation>) {
        for (invocation in invocations) {
            when (invocation.tool) {
                "replace_background" -> {
                    if (invocation.args["source"]?.toString() == "user_local") {
                        UserUriAllowlist.allow(invocation.args["background_uri"]?.toString())
                    }
                }

                "apply_texture" -> {
                    if (invocation.args["texture"]?.toString() == "user_local") {
                        UserUriAllowlist.allow(invocation.args["texture_uri"]?.toString())
                    }
                }
            }
        }
    }

    private suspend fun analyzeDigest(context: ImageContext): CanvasDigest =
        withContext(ImagePipeline.dispatcher) {
            val digestContext = coroutineContext
            CanvasAnalyzer.analyze(
                context.bitmap,
                context.regionRequest,
                checkActive = { digestContext.ensureActive() },
            ).copy(
                localAssetDigest = localAssetDigest,
            )
        }

    private fun applySequence(invocations: List<ToolInvocation>) {
        if (busy) return
        val currentSession = session ?: return
        busy = true
        viewModelScope.launch {
            runCatching {
                withContext(ImagePipeline.dispatcher) {
                    val executionContext = coroutineContext
                    for (invocation in invocations) {
                        executionContext.ensureActive()
                        currentSession.apply(
                            invocation,
                            checkActive = { executionContext.ensureActive() },
                        )
                    }
                    currentSession.current
                }
            }.onSuccess { context ->
                image = context
                history = currentSession.history()
                selection = context.regionRequest
                canvasDigest = analyzeDigest(context)
                message = "已应用 ${invocations.joinToString(" → ") { it.tool }} · ${history.size} 步"
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                image = currentSession.current
                history = currentSession.history()
                selection = currentSession.current.regionRequest
                message = "操作失败：${failure.message}"
                error = message
            }
            busy = false
        }
    }

    private fun apply(invocation: ToolInvocation) {
        if (busy) return
        val currentSession = session ?: return
        busy = true
        viewModelScope.launch {
            runCatching {
                withContext(ImagePipeline.dispatcher) {
                    val executionContext = coroutineContext
                    currentSession.apply(
                        invocation,
                        checkActive = { executionContext.ensureActive() },
                    )
                }
            }.onSuccess { context ->
                image = context
                history = currentSession.history()
                selection = context.regionRequest
                canvasDigest = analyzeDigest(context)
                message = "已应用 ${invocation.tool} · ${history.size} 步"
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                image = currentSession.current
                history = currentSession.history()
                selection = currentSession.current.regionRequest
                message = "操作失败：${failure.message}"
                error = message
            }
            busy = false
        }
    }
}
