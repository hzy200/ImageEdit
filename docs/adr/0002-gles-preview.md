# ADR-0002：GLES 预览层（P0 + P1）

- 状态：已接受（P1 完成，真机帧率待验收）
- 日期：2026-11-05
- 范围：GlEngine + 全部 P1 渲染 Pass + 后台快照 + 视口/选区同步 + 对话框草稿

## 1. 背景

现有预览由 Compose `Image`/`Canvas` 绘制 CPU `Bitmap`，显示本身已 GPU 化；瓶颈在于每次编辑都重新分配 Bitmap 并由 CPU/OpenCV 计算。P1 引入 GL 预览层，验证：

- 真机帧率与纹理上传成本；
- 前后台切换、Surface 重建时的上下文恢复；
- Compose `AndroidView` + `SurfaceView` 的集成与回退路径；
- 调色/选区、换色、面料、背景的实时草稿效果。

P1 **不改变** EditSession、CPU 工具链与导出结果；所有“应用”仍走 CPU 路径。

## 2. 决策

1. 使用 **SurfaceView + EGL14 + GLES 3.0**，自建 `GlEngine` 单线程渲染。
2. 预览与 CPU 工具链解耦：GPU 显示当前 `ImageContext.bitmap` 与“草稿效果”。
3. CPU 仍是唯一真值与导出路径；任何 GL 失败都回退到原 Compose 渲染。
4. Bitmap 快照在**独立后台线程**复制到直接 `ByteBuffer`，主线程不复制像素；
   GL 线程只负责上传与绘制。
5. P1 草稿支持：整图/仅选区调色、服装换色、面料纹理、纯色/预设背景、
   前后对比、透明棋盘格、缩放/平移。
6. 选区几何与 GL 视口共用 `PreviewGeometry`，避免缩放/平移后选区框错位。

## 3. 结构

```
core/render/
  RenderBackend.kt      后端开关（AUTO/GLES/CPU，SharedPreferences）
  GlCapabilities.kt     GLES 3.0 能力检测
  GlColorMatrix.kt      ColorMatrix(4x5) -> mat4 + 归一化 offset
  PreviewFrame.kt       Bitmap 快照（直接缓冲区，后台线程）
  PreviewDraft.kt       UI 草稿（Bitmap）
  PreviewRenderDraft.kt GPU 草稿（PreviewFrame）
  PreviewViewport.kt    zoom / pan / Fit-Cover
  PreviewGeometry.kt    GL 与 Compose 选区的共用视口计算
  PreviewRenderState.kt 渲染状态
  PreviewRenderer.kt    Pass 编排、FBO、模糊缓存
  gl/EglCore.kt         EGL 上下文/窗口/PBuffer
  gl/GlEngine.kt        GL 线程、上下文恢复、帧率日志
  gl/GlProgram.kt       Shader 编译与 Uniform/Attrib 缓存
  gl/GlQuad.kt          全屏四边形
  gl/GlTexture.kt       纹理上传（支持 rowStride）
  gl/GlFbo.kt           RGBA8 离屏目标
  gl/Shaders.kt         全部顶点/片元着色器
  gl/DisplayPass.kt     显示
  gl/ColorAdjustPass.kt 调色（可写 FBO）
  gl/MaskBlendPass.kt   选区调色的蒙版混合
  gl/ComparePass.kt     前后对比 + 分割线
  gl/GarmentHuePass.kt  服装换色草稿
  gl/TextureWarpPass.kt 面料纹理草稿
  gl/BackgroundPass.kt  纯色/纹理背景 + 接触阴影
  gl/BlurPass.kt        可分离高斯（背景虚化）
  gl/CheckerPass.kt     透明棋盘格
ui/GlCanvas.kt          Compose AndroidView 封装
ui/GlPreviewView.kt     SurfaceView 生命周期、后台快照、手势
core/image/ColorAdjustMatrix.kt  CPU/GPU 共用调色矩阵
```

渲染流程：

```
ImageContext.bitmap / mask / texture / background
  → PreviewFrame.from（后台线程，主线程不阻塞）
  → GlEngine 合并状态并调度渲染
  → PreviewRenderer
      ├─ 后台快照 → GL 线程上传纹理（按 generation 去重）
      ├─ PreviewGeometry 计算 Fit/Cover + zoom/pan 视口
      ├─ CheckerPass（透明区域）
      ├─ 草稿 Pass（Color/MaskBlend、GarmentHue、TextureWarp、Background）
      ├─ ComparePass（左侧原图 + 黄色分割线）
      └─ FBO：选区调色、背景高斯模糊
  → eglSwapBuffers
```

## 4. 关键实现细节

- 纹理坐标：顶点着色器翻转 V，使 Bitmap 第 0 行显示在顶部。
- 预乘 Alpha：Bitmap 纹理为预乘；显示使用
  `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`。调色 Pass 先反预乘、应用矩阵、再预乘。
- 调色一致性：CPU 与预览共用 `ColorAdjustMatrix.build()`；
  `GlColorMatrix` 将 4×5 行主序矩阵转换为 GLSL 列主序 mat4，并把
  ColorMatrix 的 0..255 offset 归一化到 0..1（否则整图会被推到过曝/白色）。
- 调色一致性实测：`GlColorParityTest` 64² 渐变样张、brightness=40、
  contrast=15、saturation=1.3，max channel diff=1，mean=0.004。
- 选区调色：ColorAdjust 先渲染到同尺寸 FBO，再由 `MaskBlendPass`
  按 selection mask 混合原图；mask 只取 R 通道。
- 服装换色/面料：直接采样 garment mask；色相/纹理为预览近似，CPU 结果仍为真值。
- 背景：纯色或背景纹理 + 前景 alpha 合成；接触阴影用 9 tap 近似；
  bokeh 走两 Pass 可分离高斯，并按背景 generation/半径缓存。
- 透明区域：`CheckerPass` 在图像视口下绘制屏幕对齐棋盘格。
- 视口：`PreviewGeometry` 同时供 GL 与 Compose 使用；GL 的 y 轴转换为
  bottom-left，Compose 使用 top-left。Fit/Cover、zoom 1–8、pan 均在此裁剪。
- 手势：双指缩放、放大后单指平移、双击 1x/2x；选区模式下禁用。
- 纹理上传：`GL_UNPACK_ALIGNMENT=1`，`rowStride != width` 时设置
  `GL_UNPACK_ROW_LENGTH`。
- 后台快照：单线程 `GlPreviewSnapshot`（后台优先级）；结果/原图/mask/
  纹理/背景各自按 Bitmap 身份去重，快照期间保留旧帧避免闪黑。
- 上下文丢失：`eglSwapBuffers` 返回失败且错误为 `EGL_CONTEXT_LOST` 时重建
  EGL 上下文与 Renderer，并从保留的 `PreviewRenderState` 重新上传纹理。
- 失败回退：EGL/Shader/GL 任一步失败 → `GlPreviewView.onGlFailure` →
  `EditorScreen` 切回 Compose 渲染。
- 调试开关：Debug 包 TopAppBar 的 `GL`/`CPU` 按钮写入
  `render_settings` SharedPreferences；Release 不显示。

## 5. 验证清单（真机）

### 5.1 基础

```bash
adb logcat -s GlPreview:I GlPreviewPerf:I GlBenchmark:I
```

- 打开图片后出现 `EGL context ready`、`surface created`；
- 无 `GL preview failed` / `Shader compile failed`；
- `snapshot ... thread=GlPreviewSnapshot` 出现，主线程不执行像素复制。

### 5.2 帧率

- 打开“调色”，拖动三个滑杆，观察：
  - `GlPreview` 的 `fps` / `frameMs` / `drawMs`；
  - `GlPreviewPerf` 的纹理上传耗时（>2ms 才打印）。
- 目标：滑杆拖动期间 `fps ≥ 55`，帧时间 p95 ≤ 8ms；记录设备型号与分辨率。
- 选区调色会多一次 FBO Pass，需单独记录。

### 5.3 实时草稿

- 调色：整图/仅选区滑杆实时变化；应用后 CPU 结果与预览一致。
- 选区：先框选，再打开调色；默认勾选“仅选区”，效果只在框内；
  缩放/平移后选区框与效果仍对齐。
- 换色：无 garment mask 时点击“解析服装以预览”（一次解析），
  之后拖动色相实时预览；应用后 CPU 落历史。
- 面料：选择格纹/印花并拖动强度；需要同一 garment mask。
- 背景：选择纯色/预设查看预览（前景有透明区域才可见）；
  应用时会自动抠图并合成。
- 缩放/平移：双击 1x/2x、双击后单指平移、双指缩放；
  选区模式下手势禁用。

### 5.4 前后对比

- 执行一次编辑并应用，开启“对比”，拖动分割条：
  - 左侧为原图、右侧为结果，分割线为黄色；
  - 缩放/平移后分割线仍落在可见图像区域内；
  - 拖动调色/换色滑杆时右侧草稿实时变化，左侧保持原图。

### 5.5 上下文恢复

- 按 Home 再回前台；
- 旋转屏幕（若未锁定）；
- 分屏/多窗口切换。
- 预期：`surface created` 重新出现，画面/对比/草稿恢复；无花屏/黑屏。

### 5.6 回退

- 点击 TopAppBar 的 `GL`/`CPU` 切换；
- 预期：切到 CPU 后恢复原 Compose 渲染，功能不受影响；
- 强制 CPU 后重启应用，仍保持 CPU。

### 5.7 设备测试

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GlPreviewSmokeTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GlColorParityTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GlDraftPassSmokeTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GlPreviewBenchmarkTest
```

### 5.8 当前模拟器结果（非性能结论）

- AVD Medium_Phone_API_37.0 / SwiftShader / 1080×2400
- 2048×1366 预览：
  - 后台快照约 16–112ms（受系统负载影响，主线程不阻塞）
  - 纹理上传约 9–13ms（GL 线程）
  - 调色滑杆拖动 `fps≈26–29`，`drawMs≈0.12–0.22ms`
    （SwiftShader 限制，不能作为真机结论）
- 选区仅调色在 Fit 与 2x 缩放下均与黄色选区框对齐；
  `PreviewGeometryTest` 覆盖 Fit/Cover/zoom/pan 裁剪边界；
- 双击缩放、放大后平移、缩放状态下前后对比正常；
- 背景纯色预览、换色/面料对话框 UI 正常；
- `GlDraftPassSmokeTest` 覆盖换色、选区混合、纹理、纯色背景、模糊背景；
- `GlPreviewBenchmarkTest` 2048²→1080×1920：copy 约 43–500ms、
  frame 约 4–50ms（SwiftShader 负载波动大，仅作功能验证）；
- GL↔CPU 调试切换：SurfaceView 正确移除/重建。
- `PipelineBenchmarkTest` 在 192MB heap growth limit 的 AVD 上会因
  `replace_background` 全分辨率内存预算被拦截；与 GL 预览无关，
  需真机或更大 heap 的 AVD 复跑。

## 6. 已知限制

- **真机帧率尚未验收**：当前只有 AVD/SwiftShader 数据。
- 换色/面料预览需要 garment mask；首次需点击“解析服装以预览”（较慢）。
  若未解析直接应用，CPU 路径仍会自动解析。
- 背景预览只有前景已有透明区域时才明显；应用时会自动抠图。
- 换色/面料/背景为预览近似，CPU/OpenCV 结果仍是权威；
  导出保持 CPU bit-exact。
- 对比模式左侧原图会被拉伸到结果视口，与原 Compose 行为一致。
- 视口状态保存在 `GlPreviewView`；View 重建（切换 GL/CPU、旋转重建）会回到 1x。
- 快照仍会分配直接缓冲区；后续可引入引用计数/双缓冲池。
- GPU 导出、PBO 回读、显存预算留给 P2。

## 7. 后续（P2）

P2 已落地，详见 `docs/adr/0003-gpu-tool-executor.md`：

- GPU 工具执行器与 CPU 回退；导出一律 CPU；
- `adjust_color`、`crop`、`rotate`、`flip`、`recolor_hsv`、`apply_texture`、
  `replace_background(alpha/none)` 已接入；
- 服装 mask 复用与实时换色/贴图（预览 + 应用）；
- PBO 回读因 Android Java GLES 绑定限制暂用直接缓冲区，JNI PBO 作为后续；
- 真机性能仍需按 ADR-0003 的基准验收。
