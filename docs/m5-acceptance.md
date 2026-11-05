# M5 个人自用验收报告

- 日期：2026-11-03
- 范围：M1–M4 功能、M5 自用验收
- 环境：Medium_Phone_API_37.0 AVD（x86_64 / SwiftShader / Android 17）
- 性质：**模拟器验收，不是三档真机验收**；不能作为对外分发的性能结论。

## 1. 结论

个人自用在模拟器上**可交付**：核心编辑、抠图、背景替换、服装换色/贴图、一句话计划、离线规则、模板、自检、标识、日志、前后对比均已跑通。

对外分发**不可交付**：三档真机压测、模型下载/回滚联调、release 包体裁剪、分发合规仍未完成。

## 2. 自动验收证据

- 单元测试：**32/32 通过**
- 设备测试：**32/32 通过**
- M5 专项：
  - `M5AcceptanceTest.exportsP3And12MpWithMetadata`：P3 输入转工作空间、12MP 导出、JPEG EXIF / PNG `tEXt` 可解析。
  - `M5AcceptanceTest.chineseIdentifierChangesPixelsAndWritesLocalLog`：中文标识实际改变像素，本地 JSONL 日志可读。
  - `M5AcceptanceTest.tenConsecutiveExportsSucceed`：连续 10 张编辑 + 导出，全部成功。
- 其他验收：
  - `ColorPipelineColorTest`：Display P3 / EXIF Orientation。
  - `FullResolutionExportTest`：4032×3024 背景替换导出，seamless 超阈值自动降级 alpha。
  - `DeepSeekOnlineSmokeTest`：App 端在线计划解析成功。
  - `TemplateStoreTest`、`ResultVerifierTest`、`ModelManagerTest`。

## 3. 连续 10 张压测（模拟器）

每张：加载 1600×1200 → 调色 → 裁剪 → 旋转 → PNG 导出。

| 次数 | 耗时 |
|---:|---:|
| 1 | 6.70 s |
| 2 | 4.43 s |
| 3 | 4.52 s |
| 4 | 2.49 s |
| 5 | 2.20 s |
| 6 | 1.80 s |
| 7 | 1.53 s |
| 8 | 1.97 s |
| 9 | 1.73 s |
| 10 | 1.34 s |

10/10 成功，无 OOM。首轮含 JIT/缓存预热，后续趋稳；**热稳定与中低端真机仍需实测**。

## 4. §2.3 / §9 / §12 达标情况

| 项 | 状态 | 说明 / 降级 |
|---|---|---|
| NFR-01 云端 P95 ≤1.2s | 未完成 | 在线 smoke 约 4s（含首次连接）；需 20+ 次 P95 测量 |
| NFR-02 matting ≤150/300ms | 未达标（模拟器） | 512 稳态约 5.4s；真机待测；低端可切 384 |
| NFR-03 工具单步 ≤30ms | 部分 | 模拟器 crop 约 123ms；真机待测 |
| NFR-04 端到端 | 未达标（模拟器） | 依赖云端+matting；离线规则/模板可用 |
| NFR-05 内存 | 通过（模拟器） | 12MP 导出未 OOM；导出分块/降级已实现 |
| NFR-06 画质 | 通过（流程） | 中间无损、仅导出压缩 |
| NFR-07 包体 | 未达标 | Debug APK 约 252MB；需 release arm64-only + 按需模型/素材 |
| NFR-08 兼容 | 部分 | minSdk24；debug 含 x86_64，release arm64-only 待构建 |
| NFR-09 线程 | 通过 | 模型/像素在后台；UI 仅绘制 |
| NFR-10 热稳定 | 未完成 | 模拟器 10 张通过，真机热稳定待测 |
| NFR-11 成本 | 部分 | 单次在线 usage 约 106 tokens；未核对实际账单 |
| NFR-12 离线 | 通过（逻辑） | 规则/模板/手动工具；飞行模式手工回归待做 |
| NFR-13 确定性 | 部分 | 同设备回归通过；跨设备 bit-exact 不承诺 |
| NFR-14 素材不外传 | 通过（代码路径） | 仅文本/ID 上云；网络抓包未做 |
| NFR-15 色彩 | 通过 | P3/EXIF 测试通过 |
| NFR-16 模型可复现 | 通过 | SHA-256/ModelManager |
| NFR-17 导出性能 | 未达标 | 12MP 背景替换导出约 11.6s；已自动降级 alpha |
| NFR-18 标识可解析 | 通过 | 中文显式标识 + JPEG/PNG 元数据 |

## 5. 三档设备矩阵

| 档位 | 设备 | 状态 |
|---|---|---|
| 旗舰 | 待填 | 未执行 |
| 中端 | 待填 | 未执行 |
| 低端/无 NPU | 待填 | 未执行 |
| 模拟器（非验收） | Medium_Phone_API_37.0 | 已执行 |

## 6. 未达标项与降级方案

| 项 | 降级 / 处理 |
|---|---|
| matting 延迟高 | 512 → 384；仍不达标时 U3/U4 用 RegionSelector，U8 延后 |
| seamlessClone 慢 | 2MP 阈值；超阈值走 alpha；12MP 导出已降级 |
| 包体 252MB | release arm64-only；模型/素材按需分发；个人自用可接受侧载 |
| 端到端慢 | 模板命中跳过云端；离线规则；分步执行 |
| SCHP QDQ 只能 CPU | QOperator 候选已生成；真机复测后再决定 |
| 云端不可用 | 规则网关 + 模板 + 手动工具 |

## 7. 未来分发清单（可选）

1. API Key：撤销当前 `.env` Key，改服务端代理；客户端不再持有长期 Key。
2. 合规：单独同意、显式/隐式标识、算法备案/安全评估、隐私政策；C-01～C-11 逐项。
3. 许可：MODNet Apache-2.0、SCHP MIT、OpenCV Apache-2.0、ONNX Runtime MIT、ICC CC0；第三方素材单独核查。
4. 包体：release arm64-only；模型/素材按需下载；Play Asset Delivery 或自建服务。
5. 稳定性：三档真机、10 张连续、热稳定、低内存、飞行模式。
6. 模型分发：SHA-256 校验、失败回滚、版本日志；`ModelManager` 已预留接口。
7. 隐私：保持「一句话上云，一张图不出端」；日志本地化。
8. 性能：P95 云端延迟、matting 延迟、导出耗时按真机重新冻结。

## 8. 证据文件

- `tools/benchmark/results-2026-11-03-emulator.md`
- `tools/model-ab/results-2026-11-03.md`
- `app/build/outputs/androidTest-results/connected/debug/`
- 本地日志：应用私有目录 `files/logs/edit.log`（JSONL）
