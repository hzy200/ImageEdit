# M1/M2/M2.5/M3 验证记录

日期：2026-11-03
环境：Medium_Phone_API_37.0 AVD（x86_64 / SwiftShader / Android 17）
性质：**功能与回归验证，不作为性能验收结论**。

## Gradle

- `:app:assembleDebug`：成功
- `:app:testDebugUnitTest`：32 个单测全部通过
- `:app:connectedDebugAndroidTest`：32 个设备测试全部通过
  - `PipelineBenchmarkTest`
  - `ExportPipelineTest`
  - `FullResolutionExportTest`
  - `MattingPipelineTest`
  - `ColorPipelineColorTest`（2）
  - `CompositeOpsTest`（5）
  - `GarmentOpsTest`（2）
  - `LocalAssetAnalyzerTest`
  - `TextureOpsTest`
  - `SchpGarmentTest`
  - `CanvasAnalyzerTest`（2）
  - `PlanExecutorTest`
  - `RegionSelectionTest`
  - `DeepSeekOnlineSmokeTest`
  - `ExampleInstrumentedTest`
- Debug APK：约 252 MB（OpenCV + ONNX Runtime + MODNet 25MB + SCHP 79MB + x86_64/arm64；发布前需裁剪为 arm64-only 并评估 release 包体）
- NDK/CMake：`assembleDebug -PnativeBuild=true` 成功

## PipelineBenchmark（12MP 4032×3024）

| 阶段 | 耗时 |
|---|---:|
| load_preview_2048 | 51.4 ms（输出 2016×1512） |
| adjust_color | 58.4 ms |
| crop | 123.3 ms |
| rotate_90 | 71.6 ms |
| flip_h | 17.6 ms |
| replace_background（占位） | 9.1 ms |
| load_full_6000 | 48.3 ms |
| replay_all_on_full | 372.8 ms |
| explicit_watermark | 46.2 ms |

## UI 手动验证

- 空状态、Photo Picker、图片加载正常；
- 1600×1200 样张显示 `sRGB IEC61966-2.1`；
- 旋转 90° 应用成功，状态显示“已应用 rotate · 1 步”；
- 调色对话框可打开并显示三项滑杆；
- 撤销已移到状态栏，始终可见。

## M2 补充验证（同日）

- `MattingPipelineTest`：通过（MODNet 512/384、remove_background、纯色/预设背景合成）。
- `ColorPipelineColorTest`：通过（Display P3 → 工作空间、EXIF Orientation 6 尺寸交换）。
- `CompositeOpsTest`：5/5 通过（预设背景、纯色 alpha、Reinhard 色彩迁移、透明 alpha 保留、seamlessClone/fallback）。
- `SchpGarmentTest`：通过（真实人像 parse_garment / refine_mask / recolor_hsv / apply_texture）。
- `GarmentOpsTest`：通过（mask=255 时 V 精确等于原值、mask 外像素完全不变、mask 形态学）。
- `TextureOpsTest`：通过（纹理去低频光照）。
- `LocalAssetAnalyzerTest`：通过（地平线、亮度、主色、构图摘要）。
- `FullResolutionExportTest`：通过（4032×3024 背景替换导出，seamless 自动降级 alpha；导出约 11.6s）。
- `seamlessClone` 实测：2016×1512 预览约 26s，因此阈值收紧为 2MP；≤2MP 走 seamless，超过降级 alpha。
- MODNet 模拟器稳态：512 约 5.4 s，384 约 2.5 s；详见 `tools/model-ab/results-2026-11-03.md`。
- SCHP INT8：首次加载+解析约 32 s，换色约 2.2 s，贴图约 2.2 s（CPU EP；XNNPACK 不支持 QDQ）。
- 预设背景 + Reinhard LAB（1080×1920）：约 4.8 s。

**修复记录**：OpenCV 生成的 alpha 位图经 Canvas 绘制时出现透明区域被当作不透明白色的问题；已改为逐像素 alpha 合成，并由 `CompositeOpsTest` 覆盖。

## M3 补充验证（同日）

- `CanvasAnalyzerTest`：2/2 通过（天空/场景、人像主体/选区摘要）。
- `PlanExecutorTest`：通过（依赖拓扑执行、分步回调、裁剪后尺寸正确）。
- `RegionSelectionTest`：通过（选区外亮度不变；选区内调色/局部消除生效）。
- 规则意图测试：15 类 × 25 变体 = **375 条样本**，通过 ≥95% 门槛；越界意图与区域前提测试通过。
- `PlanValidatorTest`：11 项通过（含非法素材、`preserve_v` 受保护字段、无选区 inpaint）。
- DeepSeek：`.env` 已注入 BuildConfig；宿主端最小请求返回 HTTP 200、`deepseek-flash`、合法 JSON（`thinking.disabled` 生效）。
- `DeepSeekOnlineSmokeTest`：App 端在线请求成功，`remote plan parsed: [adjust_color]`（约 4s）；无 Key/网络失败时自动走离线规则网关。
- 计划卡、解析输入框、选区框选 UI 已编译；尚未做完整 UI 手工回归。

## M4 补充验证（同日）

- `TemplateStoreTest`：通过（精确指令模板保存/命中）。
- `ResultVerifierTest`：3/3 通过（过曝、选区外变化、背景角透明）。
- `ModelManager`：已接入 MODNet/SCHP 加载；随包模型 SHA-256 校验；`ModelManagerTest` 校验已知 SHA-256。
- `MattingPipelineTest` / `SchpGarmentTest`：ModelManager 接入后仍通过。
- UI：错误 Snackbar、能力边界文案、前后对比滑杆已编译。
- 离线：无 Key / 无网络自动走规则网关；模板命中优先；手动工具始终可用。
- 自检接入计划执行：过曝/欠曝、mask 尺寸、选区外变化、服装 V 偏差、背景角透明会给出提示，不阻断用户。
- `PlanExecutor` 单会话 8 步上限已加入。
- 尚缺：模型下载/回滚的真机网络联调、前后对比 UI 手工回归、断网 UI 手工回归。

## M5 自用验收（同日）

- 单元测试 **32/32**，设备测试 **32/32**。
- `M5AcceptanceTest`：3/3 通过（P3+12MP 元数据、中文标识/本地日志、连续 10 张导出）。
- 连续 10 张压测：全部成功，单张 1.34–6.70s（首轮预热），无 OOM。
- 验收报告与未达标项：`docs/m5-acceptance.md`。
- 结论：**模拟器上个人自用可交付；对外分发仍需三档真机、release 包体与合规**。

## P0 缺陷修复验证（同日）

- **几何变换 mask/选区同步**：`BitmapTools` 对 crop/rotate/flip 同步变换 masks 与 `regionRequest`；新增 `MaskTransformTest`，裁剪/旋转/翻转后 mask 尺寸与选区仍对齐。
- **禁用云端**：`ApiKeyStore` 增加 `disable_remote`；清除 Key 会屏蔽 `.env` 的 BuildConfig Key；新增 `ApiKeyStoreTest`，可禁用/恢复。
- **MODNet CPU 回退**：XNNPACK session 创建失败时自动切 CPU EP（与 SCHP 一致）；该分支为防御性代码，未在模拟器强制触发。
- **工具 Schema**：`ToolRegistry.schemasAsJson()` 输出完整 `parameters/properties/minimum/maximum/enum/default/required`；`ToolRegistryTest` 已覆盖。
- **区域校验**：`PlanValidator.hasRegion` 只认 `regionRequest/selection`，不再用 objects 置信度替代执行 mask；`PlanValidatorTest` 覆盖 objects-only 拒绝。
- **可取消 PlanJob**：`PlanExecutor` 每步 `ensureActive()`；ViewModel 持有 `planJob`，关闭计划卡/取消执行会取消协程；阻塞中的模型调用会在当前原子步结束后停止。
- **防重入**：ViewModel 的 open/parse/apply/undo/export/execute 入口先检查并置位 `busy`，避免并发修改 `EditSession`。
- 完整回归：**32 单测 + 32 设备测试全部通过**。

## P1 缺陷修复验证（同日）

- `CanvasAnalyzer` 不再根据 scene 伪造 `garmentDetected`，服装类别只由 SCHP 解析产生。
- `LocalAssetDigest` 已接入：选择本地背景时先分析背景并把摘要写入 `CanvasDigest`；`LocalAssetAnalyzerTest` 覆盖序列化。
- `replace_background(source=user_local)` 强制要求 `background_uri`；缺失会被 PlanValidator 拦截，加载失败会明确报错，不再静默变纯色。
- `maxInputPx` 已对 MODNet/SCHP/背景/换色/贴图工具生效：预览校验 + 全分辨率执行前检查；`PlanValidatorTest` 覆盖。
- 本地日志 `append` 加锁并吞掉日志失败，不再让导出因日志失败而报错；TemplateStore 最多保留 50 条，`TemplateStoreTest` 覆盖。
- SCHP 输出长度校验：非 `18×512×512` 时明确失败，避免越界或错 mask。
- `CanvasAnalyzer` 与 `ResultVerifier` 已移到后台线程；`ResultVerifier` 选区外改为逐像素检查，单像素变化也能发现。
- 完整回归：**32 单测 + 32 设备测试全部通过**。

## 未完成

- 真实设备性能、内存与热稳定；
- U8 画质需更多真实服装样张与盲评；
- 婚纱/玻璃等真实半透明样张需用户补充；
- 导出按钮的完整 UI 手工流程（导出核心已由 instrumentation 测试覆盖）；
- SCHP QOperator/NCNN 加速评估。
