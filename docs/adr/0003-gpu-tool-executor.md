# ADR-0003：GPU 工具执行器（P2）

- 状态：已接受（功能与一致性通过；真机性能待验收）
- 日期：2026-11-05
- 范围：`ImageProcessor` / `GpuToolPolicy` / 离屏 GL 工具执行 / CPU 回退 / 导出一致性

## 1. 背景

P1 已把预览搬到 GPU，但每次“应用”仍走 CPU：调色 58ms、裁剪/旋转 71–123ms、
换色/贴图约 2.2s、背景合成约 4.8s（AVD 历史数据）。P2 把可 GPU 化的工具接入
`ImageContext -> ImageContext` 契约，并保持 CPU 回退。

同时必须守住两条边界：

1. **导出仍为 CPU bit-exact**：导出重放使用 `GpuToolPolicy.withoutGpu`。
2. **一致性门槛**：普通工具 PSNR ≥ 40dB、单通道最大误差 ≤ 2；
   mask 对齐；`recolor_hsv` 的 V 必须精确保持。任一工具不过门槛则默认走 CPU。

## 2. 决策

1. 新增 `ImageProcessor` 接口与 `GpuToolProcessor` 单例；`BitmapToolExecutor`
   在参数/尺寸校验后优先尝试 GPU，失败返回 null 后执行原有 CPU 分支。
2. `GpuToolPolicy` 决定工具是否可 GPU 化：
   - 长边 ≤ 4096；
   - `adjust_color`、`crop`、`rotate`、`flip`、`recolor_hsv`、`apply_texture`；
   - `replace_background` 仅在 `source != user_local`、`edge_fusion == alpha`、
     `color_transfer == none` 时启用（seamless/reinhard 仍 CPU）。
3. GPU 执行器使用**私有 EGL 上下文 + 1×1 pbuffer + FBO**，与预览上下文分离；
   所有调用在 `@Synchronized` 下串行，可在 ImagePipeline 线程或测试线程
   `eglMakeCurrent`。
4. 结果通过 `glReadPixels` 读回可复用直接缓冲区。**Android Java GLES 绑定不允许
   在 PBO 绑定后传 `null` 像素指针**，因此当前未使用 PBO；JNI PBO 双缓冲作为
   后续优化（NDK 已具备）。
5. FBO 内容相对 Bitmap 上传是 bottom-up；统一通过一次 `DisplayPass` 拷贝到
   第二个 FBO 翻转，保证 `Bitmap.copyPixelsFromBuffer` 行序正确。
6. 几何变换的 mask/region 同步复用抽取出的 `GeometryTransforms`。
7. 换色在 Shader 内复刻 `forceValuePreserved`：缩放后把最大通道强制设为
   源 V，保证 `GarmentOpsTest` 的 V 精确要求。
8. 导出：`ExportPipeline` 的 `session.replayOn` 外层包 `withoutGpu`；
   线程内 GPU 全部禁用，导出保持 CPU。

## 3. 结构

```
core/render/
  ImageProcessor.kt          工具执行契约
  GpuToolPolicy.kt           GPU 开关 / 导出抑制 / 工具白名单
  GpuToolProcessor.kt        离屏 EGL、FBO、上传、回读、工具实现
  gl/TransformPass.kt        裁剪/旋转/翻转 UV 变换
  gl/GlFbo.kt                FBO（新增 clear）
  gl/GlTexture.kt            GLUtils 直接上传
core/image/GeometryTransforms.kt   CPU/GPU 共用 mask/region 变换
core/tools/BitmapTools.kt    优先 GPU，失败回退 CPU
core/export/ExportPipeline.kt  replay 期间禁用 GPU
```

## 4. 一致性实测

`GpuToolParityTest`（64×64 合成样张，CPU 强制 vs GPU）：

| 工具 | 最大通道误差 | 平均误差 | PSNR |
|---|---:|---:|---:|
| `adjust_color`（整图） | 1 | 0.003 | 73.20 dB |
| `adjust_color`（仅选区） | 1 | 0.002 | 74.59 dB |
| `crop` | 0 | 0 | 100 dB |
| `rotate 90/180` | 0 | 0 | 100 dB |
| `flip H/V` | 0 | 0 | 100 dB |
| `recolor_hsv` | 2 | 0.251 | 54.04 dB |
| `apply_texture` | 2 | 0.262 | 53.92 dB |
| `replace_background`（solid/alpha/none） | 0 | 0 | 100 dB |

`recolor_hsv` 逐像素 V 精确保持通过；所有工具满足 PSNR ≥ 40 / max ≤ 2 门槛。

## 5. 性能（AVD SwiftShader，非真机结论）

`GpuToolBenchmarkTest`（2048×1366，3 次取最优）：

| 操作 | CPU（AVD） | GPU（AVD） | 目标 |
|---|---:|---:|---:|
| adjust_color | 128ms | 188ms | ≤10ms |
| crop | 40ms | 125ms | ≤25ms |
| rotate 90 | 308ms | 186ms | ≤25ms |
| selection color | 逐像素 | 278ms | ≤12ms |
| recolor（mask 已有） | ~2.2s（历史） | 376ms | ≤30ms |
| texture（mask 已有） | ~2.2s（历史） | 482ms | ≤40ms |
| background solid | ~4.8s（历史全链路） | 198ms | ≤60ms |

> SwiftShader 是软件 GL，GPU 数值没有参考意义；小工具在 AVD 上甚至慢于 CPU。
> 真机必须用 `GpuToolBenchmarkTest` 与 `GlPreview` 日志重新验收。
> 若某工具在真机低于目标或慢于 CPU，将其加入 `GpuToolPolicy` 排除列表，
> 或使用 Debug 顶部 `GL/CPU` 按钮整体回退。

## 6. 真机验证

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GpuToolParityTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.GpuToolBenchmarkTest
adb logcat -s GpuToolProcessor:I GpuToolParity:I GpuToolBenchmark:I
```

- `GpuToolProcessor: <tool>=xx.xms`：每次 GPU 工具耗时；
- `GpuToolProcessor: <tool> failed; CPU fallback`：确认回退路径；
- 重点记录：adjust/selection/crop/rotate/flip/recolor/texture/background solid
  在目标中间档真机上的耗时与内存峰值。

其它回归：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.MaskTransformTest,com.example.imageedit.RegionSelectionTest,com.example.imageedit.PlanExecutorTest,com.example.imageedit.SchpGarmentTest,com.example.imageedit.MattingPipelineTest,com.example.imageedit.ExportPipelineTest,com.example.imageedit.FullResolutionExportTest
```

以上 7 个设备测试在当前 AVD 全部通过。

## 7. 已知限制

- **真机性能未验收**：AVD 数据不可作为结论；小工具可能因回读/同步开销不划算。
- 未实现 PBO 回读：Android Java 绑定限制；后续可用 JNI `glReadPixels(..., 0)` +
  `glMapBufferRange` + 双 PBO 进一步降低同步开销。
- `refine_mask` 仍走 CPU（形态学 GPU 近似未实现）。
- `apply_texture(texture=user_local)`、`replace_background(user_local)`、
  `replace_background` 的 seamless/reinhard 路径仍走 CPU。
- 背景 bokeh/接触阴影为预览近似；GPU 工具的白名单仅覆盖 alpha/none 路径。
- GPU 结果与 CPU 有 ±1–2 的量化差；导出保持 CPU，因此预览/导出可能有极小差异。
- GPU 执行器与预览各自持有 EGL 上下文与 FBO；显存预算（P3）尚未实现。
- 工具执行是同步读回；后续要异步管线时才需要双 PBO/多帧流水。

## 8. 回滚

- 单工具：从 `GpuToolPolicy.eligible` 移除即可立即回退 CPU。
- 整体：Debug 顶部 `GL/CPU` -> CPU，同时禁用预览与工具 GPU。
- 导出：`ExportPipeline` 的 `withoutGpu` 保证即使工具默认 GPU 也不会影响成片。
