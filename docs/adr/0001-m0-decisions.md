# ADR-0001：M0 决策记录

- 状态：已接受
- 日期：2026-11-03
- 适用版本：v1.2 个人自用

## 1. 项目定位

本期为**个人自用**，不对外分发；不做多用户、账号体系与自建转发代理。`IntentGateway` 保留可替换 base URL，未来分发时再补代理、鉴权与合规。

**影响**：合规按「个人自用最低要求 + 未来分发预留」执行；显式/隐式标识、单独同意与本地日志能力仍保留。

## 2. API Key

- 个人自用使用独立 API Key，存 Android Keystore / EncryptedSharedPreferences。
- 控制台设置额度上限与告警；定期轮换；禁止提交进版本库或写入 `BuildConfig` 明文。
- 本期不做代理；未来分发时切到服务端代理，客户端不持有长期 key。

## 3. 模型分发

- 个人自用优先「随包或侧载」；不做首次启动静默下载大模型。
- 所有模型必须带 `ModelManifest`（版本、量化、输入尺寸、后端、SHA-256、许可）。
- 未来分发改用 Play Asset Delivery 或自建下载服务。

## 4. 输入与导出上限

- 预览最长边：2048 px。
- 编辑工作图最长边：6000 px（可配置）。
- 12MP 为导出主目标；超大图先降采样或拒绝，禁止直接转 ARGB_8888。
- 全分辨率 `seamlessClone` 未实测达标前，默认走「预览融合 → 上采样」降级路径。

## 5. 工程基线

- Kotlin 2.2.10、Compose BOM 2026.02.01、AGP 9.5.0。
- `compileSdk 37`、`targetSdk 37`、`minSdk 24`。
- 仅 `arm64-v8a` 为发布目标；本地调试可使用模拟器 ABI。
- OpenCV 4.9.0 作为图像处理主干；首个垂直切片先用 Android `Bitmap`/`Canvas` 实现基础工具，OpenCV 在 M2 接入。
- 推理运行时：当前已落地 **ONNX Runtime Android 1.20.0**。MODNet 走 XNNPACK；SCHP-ATR INT8 QDQ 的 XNNPACK session 不兼容，自动回退 CPU EP，U8 功能已可用。
- **M3 模型矩阵冻结**：MODNet（人像 matting）+ SCHP-ATR（服装解析）+ 启发式 `CanvasAnalyzer`（scene/sky/皮肤显著性）+ `RegionSelector`（U3/U4 兜底）。M3 不引入专用开放词表检测/天空分割模型；对象/天空置信度不足时由用户框选。
- IntentGateway：DeepSeek 客户端 + 离线规则网关；无 Key 时规则网关直接工作，规则样本 375 条通过 ≥95% 门槛。
- **M4 护栏与降级**：`TemplateStore` 本地模板、`NetworkStatus` 离线判定、`ResultVerifier` 可计算自检、`ModelManager` 校验/下载/回滚接口、错误 Snackbar、能力边界文案、前后对比滑杆。自检只提示不阻断；模型完整性失败会显示明确错误。
- **M5 自用验收**：32 单测 + 32 设备测试通过；P3/EXIF/12MP 导出、中文标识/元数据、本地 JSONL 日志、连续 10 张导出均通过。验收报告见 `docs/m5-acceptance.md`；三档真机与对外分发仍未完成。
- **P0 缺陷修复**：几何变换同步 mask/选区；清除 Key 可真正禁用 `.env` 云端 Key；MODNet 增加 CPU 回退；工具 Schema 补全参数；区域校验只认 selection；引入可取消 `PlanJob`；ViewModel 入口防重入。
- **P1 缺陷修复**：去掉伪造 `garmentDetected`；接入 `LocalAssetDigest`；`user_local` 强制 URI；`maxInputPx` 生效；日志容错/加锁与模板限容；SCHP 输出长度校验；CanvasAnalyzer/ResultVerifier 移出主线程；选区外逐像素自检。
- NDK 27.0.12077973 + CMake 3.22.1 已安装；`-PnativeBuild=true` 构建验证通过。

## 6. 测试设备

用户需在 M1 前填入实际设备；建议至少三档：

| 档位 | 设备 | 用途 |
|---|---|---|
| 旗舰 | 待填 | 性能上限、GPU delegate、画质基线 |
| 中端 | 待填 | 主目标、CPU 推理、4GB/6GB 内存 |
| 低端/无 NPU | 待填 | 降级路径、OOM、热稳定 |
| 验证环境（非验收） | Medium_Phone_API_37.0 AVD（x86_64 / SwiftShader） | 仅用于功能与回归，不作为性能结论 |

## 7. 当前未决项

1. 测试设备型号与系统版本（当前仅 AVD，无真机）。
2. ~~MODNet 运行时~~：已用 ONNX Runtime 1.20 + XNNPACK；仍需真机性能复核。
3. ~~主体/多目标/天空模型~~：M3 冻结为启发式 `CanvasAnalyzer` + `RegionSelector`；后续如引入专用检测/天空模型，需重新跑回归。
4. 模型包具体分发方式（随包/侧载）。
5. SCHP-ATR 已转为 INT8 ONNX，U8 功能链路通过。QOperator 候选（76.8 MB）可启用 XNNPACK，但模拟器首次解析 49.2s，慢于 QDQ CPU 的 32s；暂保留 QDQ 默认，QOperator 留作真机 A/B。NCNN 暂未执行。
6. `seamlessClone` 已接入；阈值 2MP，超过（含 12MP 导出）自动降级为 alpha 合成。12MP 导出回归已通过。
7. 背景/纹理素材库初始规模与分类维度（当前为生成的渐变/格纹/印花预设）。
8. DeepSeek Key 来源：`.env` 构建时注入 BuildConfig，UI 可用 SharedPreferences 覆盖；`.env` 已加入 gitignore。Key 会进入 APK，分发前必须改为 Keystore/服务端代理并轮换 Key。
9. `ModelManager` 下载/回滚接口已实现，但当前使用随包模型，尚未做真实下载服务器联调。
10. 计划卡、选区框选、前后对比滑杆尚未做完整 UI 手工回归。

## 8. 后果

- 首个可用版本是手动编辑器，不是一句话修图；M1 不稳不进入 M3。
- 模型、包体、内存与导出指标必须用目标机实测，不能只引用二手数据。
- 个人自用不等于可以随意处理他人照片；涉及他人肖像时仍需取得同意。
