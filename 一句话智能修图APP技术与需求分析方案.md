# 一句话智能修图 APP — 技术与需求分析方案

| 项目 | 内容 |
|---|---|
| 文档版本 | **v1.3** |
| 编制日期 | 2026-11-03 |
| 项目定位 | 本期为**个人自用**，不对外分发；架构中预留未来分发所需的代理、鉴权与合规接口。**当前状态：模拟器上个人自用可交付，三档真机与对外分发待完成。** |
| 适用形态 | Android 个人 APP（Kotlin / Compose / C++ NDK；已从 greenfield 完成 M1–M5 垂直切片） |
| 核心命题 | 用户用一句自然语言指令，完成人像 / 自然图像的定制化编辑与抠图 |
| 架构定调 | **云端只出「脑」，端侧全出「手」**：大模型通过 API 提供意图识别与工作流编排，全部像素操作与图像资产 100% 本地完成 |
| 结论 | **个人自用可交付（模拟器验收）**：M1–M5 已实现，32 单测 + 32 设备测试通过；三档真机、release 包体、模型下载/回滚联调与分发合规仍待完成。 |
| v1.1 变更 | 新增**非生成式合成能力**：以「大模型生成指令 + 确定性算法工具调用」实现**背景替换**与**服饰换色 / 面料图案替换**，在不引入任何生成式模型的前提下恢复原被砍功能的用户交互形态。涉及 §0、§1.3、§2.1/2.2/2.3/2.4/2.5、§3.3、§4、§5.1/5.2/5.5、**§7.4 / §7.5（新增）**、§7.1、§8.1、§9、§11、§12.1、§13、§14、§15 |
| v1.2 变更 | 按**个人自用**收敛定位；对齐当前 greenfield 仓库与工程基线；新增模型矩阵与 U3/U4 **RegionSelector 兜底**；拆分包体/模型/素材预算；重写内存、导出、确定性与标识实现口径；补充色彩管理、模型版本固定、验收样本与里程碑退出条件。涉及 §0、§1.1/1.3、§2.1/2.2/2.3/2.4/2.5、§3.1/3.3、§4、§5.1/5.2/5.3/5.4/5.5、§6.1/6.3、§7.1/7.2/7.3/7.4/7.6、§8.2、§9、§10.1/10.2、§11、§12、§13、§14、§15 |
| v1.3 变更 | 记录 M1–M5 实现与验收状态；补充 `.env`/BuildConfig 密钥注入、DeepSeek 在线 smoke、离线规则/模板、PlanExecutor 取消与防重入、LocalAssetDigest、`maxInputPx`、日志/模板上限、P0/P1 缺陷修复；更新模型矩阵、性能实测、风险与待确认事项。新增 §16 实现状态与验收记录。 |

---

## 0. 结论摘要（先读这一段）

前序讨论中逐层收敛出的三处关键限制，均已在最终架构中找到确定解，此处统一澄清：

| 疑点 | 结论 | 本方案的处理 |
|---|---|---|
| DeepSeek V4.1 Flash 能否塞进手机 | 不能，且不必 | 552B MoE，原生权重 510.3 GB、Q4_K_M 约 571.6 GB、FP16 约 1236.5 GB [R1][R2]。**不本地部署**，改走 API |
| 该模型能否直接「P 图」 | 不能，且不必 | 输入支持文本+图像，输出**仅文本**，不具备图像生成/编辑输出能力 [R3][R4]。方案只让它输出结构化 JSON 计划，像素由端侧执行 |
| 「完全纯客户端」是否成立 | 成立 | 取消端侧大模型、取消图片上传；出端数据只有一句用户指令 + 结构化画布摘要 + 工具 schema，**任何 Bitmap 字节不出设备** |

三个口径的最终取舍：

- **不采用**：全云端 P 图（照片上传）、端侧跑 500GB 级模型、**任何生成式模型凭空生成像素**（扩散 / I2I 重绘 / 文生图）。
- **采用**：`DeepSeek API（意图/编排） + OpenCV 确定性算法 + 轻量判别式定位模型（像素）` 的端云分工。
- **产品承诺锁死在**：确定性编辑、蒙版合成、光色和谐化（去背景、背景替换、调色、裁剪、局部消除、超分、图层）与**服饰换色 / 面料图案替换**。

**v1.2 定位与三项前置条件**：

- **个人自用优先**：本期不对外分发，不做多用户/账号体系，不强制自建转发代理；但 `IntentGateway` 保留可替换 base URL，未来分发时按 §10.1 加代理与鉴权。合规按「个人自用最低要求 + 未来分发预留」执行，C-01～C-11 在分发时全部生效。
- **前置条件一：模型矩阵**。`CanvasAnalyzer` 的主体/多目标/场景/天空定位目前只有一句描述，没有具体模型；U3/U4 必须先确定模型 + `RegionSelector` 兜底，不能只依赖模型，否则 planner 无法可靠定位。
- **前置条件二：预算闭合**。MODNet 约 26 MB + SCHP-ATR INT8 约 65 MB，再加 OpenCV、素材库与导出缓冲，包体/内存/全分辨率导出必须分别预算并实测，不能只给单点指标。
- **前置条件三：实现路径**。中文标识不能用 OpenCV `putText`；EXIF/XMP 不能靠 `imencode` 写入；全分辨率 `seamlessClone` 与 12MP 导出内存必须按 §7.6 的策略实现。

**v1.3 实现状态（截至 2026-11-03）**：

| 阶段 | 状态 | 说明 |
|---|---|---|
| M0 立项 | 完成 | 个人自用、模型/包体/输入预算、测试设备记录 |
| M1 编辑器地基 | 完成 | Compose 编辑器、ToolRegistry、撤销、色彩/导出、AssetLibrary |
| M2 抠图与合成 | 完成 | MODNet ONNX、背景替换、LAB、seamlessClone、LocalAssetDigest、PreviewReplay |
| M2.5 服饰 | 完成 | SCHP-ATR INT8、parse/refine/recolor/apply_texture |
| M3 智能化 | 完成 | CanvasDigest v2、规则网关、DeepSeek 客户端、PlanValidator、PlanExecutor、计划卡、选区 |
| M4 护栏与降级 | 完成 | 模板、离线、ResultVerifier、ModelManager、错误 UI、对比滑杆 |
| M5 自用验收 | 完成（模拟器） | P3/EXIF/12MP、中文标识、日志、连续 10 张；三档真机待补 |

- 测试：**32 单测 + 32 设备测试**全部通过；DeepSeek 在线 smoke 成功。
- P0/P1 审查缺陷已修复：mask 几何同步、Key 禁用、MODNet CPU 回退、完整工具 Schema、selection 区域校验、可取消 PlanJob、防重入、LocalAssetDigest、`maxInputPx`、日志/模板上限、SCHP 输出校验、主线程移出、逐像素自检。
- 未完成：三档真机性能/热稳定、release 包体裁剪、模型下载/回滚真实服务联调、SCHP QOperator/NCNN 真机 A/B、真实婚纱/玻璃/服装样张盲评。

> **v1.1 关键判断**：原 v1.0 把「换背景 / 换装」整体划入 Out of Scope，砍掉的是**生成式实现**，误伤了用户交互形态。v1.1 将其拆成两层——**交互层完全保留**（仍是一句话 → 自动分步执行），**语义层诚实降级**（素材必须来自预设库或用户本地选图，算法只能「替换」不能「创造」，服饰轮廓/款式不变）。详见 §7.4。
>
> 「不使用 AI」的准确工程口径：**不用生成式模型生成画面里不存在的像素**；判别式模型只输出 mask 与类别标签，像素合成全部走确定性算法。云端仍保留大模型做意图解析，因此不能在合规材料里表述为「完全不使用 AI」。

这条边界本身即是卖点，建议写进隐私说明：**「一句话上云，一张图不出端」**；功能命名同步改为「背景替换」「服饰配色 / 面料图案替换」，避免「AI 换装」这类会抬高期待的措辞。

---

## 1. 项目定义与边界

### 1.1 目标

**当前仓库现状（v1.3）**：项目已从空白 Compose 模板完成 M1–M5 垂直切片：编辑器、ToolRegistry/撤销/导出、MODNet/SCHP、背景替换、DeepSeek/规则意图、计划卡、选区、模板/离线、自检、标识、日志、前后对比均已落地。模拟器上个人自用可交付；三档真机与对外分发仍未完成。

目标保持不变：用户打开一张图，输入一句话，APP 自动解析意图、生成分步计划、调用本地图像工具、实时预览、导出成片。**M1 的交付不是「接一个 Agent」，而是「先有一个可用的编辑器，再接一个说话入口」**；这一原则已在实现中遵守。

### 1.2 出端 / 不出端清单（**强约束，不得突破**）

| 类别 | 数据 | 位置 |
|---|---|---|
| ✅ 允许出端 | 用户自然语言指令（脱敏后）、`CanvasDigest` 结构化文本摘要（含 `objects` / `RegionRequest`）、`LocalAssetDigest` 文本摘要、工具 schema 与 few-shot、会话上下文、计划 JSON、**`AssetLibrary` 素材枚举 ID**（NFR-14） | → `api.deepseek.com` |
| ❌ 禁止出端 | 原图、缩略图、alpha 蒙版、图层、中间产物、成片、预设素材图与面料纹理的字节、用户本地选图、任何 Bitmap / Base64 图像字节 | 仅设备内存与本地文件 |
| ✅ 本地执行 | 抠图、matting、调色、几何变换、inpaint、图层合成、**确定性合成算子（色彩迁移 / 泊松融合 / HSV 换色 / 纹理贴图）**、显式/隐式标识嵌入、导出、撤销重做 | OpenCV + 端侧 DNN |

> V4.1 Flash 原生支持图像输入（单图 token 上限 1024，base64 单图 ≤ 32 MiB，请求体 ≤ 48 MiB，单边 ≤ 8192 px）[R5]。**技术上能传缩略图，但按本项目口径默认禁止**。若未来开放，须做成默认关闭的独立开关 + 单独告知 + 单独同意，不得默认启用。

### 1.3 术语

| 术语 | 含义 |
|---|---|
| `CanvasDigest` | 由端侧 CV 模型生成的结构化画布摘要（纯文本），替代「让大模型看图」 |
| `ToolRegistry` | 工具注册表：工具名 + JSON Schema 参数 + 合法性校验 + 依赖声明。**UI 按钮与 Agent 共用同一套代码路径** |
| `EditPlan` | 云端返回的结构化编辑计划（步骤数组 + 依赖 + 参数） |
| `PreviewReplay` | 预览在低分辨率上跑，导出时用**原分辨率重放同一套参数**的机制 |
| `Matting` | 输出软边 alpha 的抠图（区别于只给硬边界的 segmentation） |
| `AssetLibrary` | 离线预设素材库：背景图、色卡、面料纹理，全部带枚举 ID 打包进 APP |
| `ObjectRef` | `CanvasDigest.objects[]` 中的结构化目标引用：`id` + 类别 + bbox + 置信度，不含图像字节 |
| `RegionRequest` | 用户点选 / 框选 / 笔刷产生的区域请求，作为 U3/U4 定位失败时的兜底输入 |
| `LocalAssetDigest` | 对用户本地选图（背景 / 纹理）在端侧生成的结构化摘要，用于地平线、亮度、主色与构图匹配；仅文本出端 |
| `ModelManifest` | 模型名称、版本、量化方式、输入尺寸、后端、SHA-256 与许可信息的固定清单，用于可复现与回归 |
| **判别式定位** | 只输出 mask / bbox / 类别标签，不生成任何新像素的模型（MODNet、SCHP/ATR、OWLv2、GrabCut 等） |
| **确定性合成** | 所有输出像素均可由「原图像素 + 素材库像素 + 固定参数」复现的算法（色彩迁移、泊松融合、HSV 换色、透视贴图） |

---

## 2. 需求规格

### 2.1 用户场景

| 编号 | 场景 | 输入示例 | 期望结果 |
|---|---|---|---|
| U1 | 人像去背换底 | 「把背景抠掉换成浅灰，人物调亮一点」 | 软边 alpha → 纯色底合成 → 亮度调整 |
| U2 | 证件照 | 「抠图，换白底，切成一寸」 | 去背 → 白底 → 295×413 裁剪 → 原图分辨率导出 |
| U3 | 局部消除 | 「把右下角那个路人去掉」 | 多目标人物检测 / 用户指定 → 选区 → 小面积纹理填充 / OpenCV inpaint；**无法把文本映射到具体目标时转用户框选/笔刷**；大面积路人会糊，明确提示能力上限 |
| U4 | 自然图像调色 | 「天空调蓝一点，别动前面的花」 | 天空/区域 mask（端侧分割或用户选区）+ 参数限幅的局部调色；**无可靠天空 mask 时要求用户点选** |
| U5 | 批量复用 | 「这几张都按刚才那套来」 | 本地批处理队列 + 逐张内存释放 + 失败单张回滚；复用已沉淀的模板工作流，不再请求云端 |
| U6 | 离线 | 飞行模式 | 走内置规则模板，APP 不变砖 |
| **U7** | **背景替换（非生成式）** | 「换成海滩，人稍微暖一点」「用我相册那张当背景」 | 抠图 → 预设/本地素材 → 光色和谐化 → 接触阴影 → 泊松融合；**素材必须已存在**；用户本地背景先在端侧生成 `LocalAssetDigest`，用于地平线/光色匹配 |
| **U8** | **服饰换色 / 换面料（非生成式）** | 「把裙子换成红色」「这件上衣换成格纹」 | 服装 mask → HSV 只改 H 保留 V（褶皱阴影原样） / 纹理去光照 + 梯度弯折贴图；**轮廓与款式不变**；多色图案需先做颜色映射，不能只贴亮度 |
| **U9** | **定位失败兜底（v1.2）** | 模型无法确定「那个路人 / 那片天空」 | 进入 `RegionSelector`：点选、框选、笔刷 + GrabCut 精修；用户选区作为 `RegionRequest` 写入 `CanvasDigest`，不上传图像字节 |

> U7/U8 是 v1.1 新增，用于恢复 v1.0 被砍功能的**交互形态**。其能力上限与产品文案约束见 §7.4 与 §11 RK-10。

### 2.2 功能需求（FR）

| ID | 优先级 | 需求 | 验收要点 |
|---|---|---|---|
| FR-01 | P0 | 统一 `ToolRegistry`，UI 与 Agent 共用 | 同一工具定义可分别由按钮点击与计划 JSON 触发，行为一致 |
| FR-02 | P0 | 画布摘要本地生成 | 纯文本输出，含主体类别/bbox/占比、**多目标 `objects[]`**、背景类型/主色调、**天空置信度**、用户选区、图层清单；不产生图像字节外传 |
| FR-03 | P0 | 一句话 → `EditPlan` | 返回合法 JSON；工具名存在、参数通过 schema、依赖无环、步数 ≤ 8 |
| FR-04 | P0 | 计划卡确认 | 多步计划先展示步骤卡片，用户确认后执行；单步可直接执行 |
| FR-05 | P0 | 分步进度与实时预览 | 每步完成即刷新画布，支持前后对比滑杆、单步撤销、整体回滚 |
| FR-06 | P0 | 抠图能力 | 人像输出软边 alpha（发丝可见），通用物体输出可用蒙版；**通用物体定位失败时可转入点选/框选/笔刷兜底** |
| FR-07 | P0 | 确定性编辑工具集 | 去背、换底、11 参调色、裁剪/旋转/翻转/缩放、**小面积**局部消除、图层拆分、超分、**背景替换 / 光色和谐化 / 接触阴影**（§7.4）、**服饰换色 / 面料贴图**（§7.4）；大面积语义消除不在范围内 |
| FR-08 | P0 | `PreviewReplay` | 预览降采样跑全链路，导出用原分辨率重放同一参数，成片不糊 |
| FR-09 | P0 | 导出与标识 | 导出 PNG/JPEG；文件含显式标识 + 元数据隐式标识 |
| FR-10 | P0 | 护栏 | 未实际调用工具不许结束任务；参数限幅；最大迭代强制终止；模型不决定像素值 |
| FR-11 | P1 | 离线降级 | 云端超时/429/无网时，命中内置模板直接执行，UI 明确标注「离线模式」 |
| FR-12 | P1 | 模板工作流沉淀 | 高频指令固化为本地模板，跳过云端，省 token 且更快 |
| FR-13 | P1 | 结果自检 | 端侧 CV 做**可计算**校验：边缘光晕强度、过曝/欠曝像素比例、蒙版尺寸与 alpha 范围、局部修改越界；阈值在 M1 冻结，异常时追加修复步或提示换素材，不做无法验证的「背景残留」主观判断 |
| FR-14 | P1 | 指令脱敏 | 证件号、手机号、邮箱等**结构化信息**正则替换；人名/地名中文 NER 为 best-effort，不承诺 100% 召回，UI 同时提示不要输入敏感信息 |
| FR-15 | P2 | 缩略图视觉自检（可选开关） | 默认关闭；开启需单独告知与同意，且只传低分辨率缩略图 |
| **FR-16** | **P1** | **背景替换（非生成式）** | 支持纯色 / 渐变 / `AssetLibrary` 预设图 / **用户本地相册选图** 四类来源；含 Reinhard LAB 色彩迁移、`seamlessClone` 边缘融合、接触阴影、浅景深；素材来源 100% 本地 |
| **FR-17** | **P1** | **服饰换色（非生成式）** | 服装区域 mask → HSV **只改 H（可微调 S）、V 保持原图**，褶皱/高光/阴影逐像素继承；mask 形态学开闭 + 羽化 |
| **FR-18** | **P1** | **面料图案替换（非生成式）** | 纹理去自带光照 → 无缝平铺 → 用原图亮度 Sobel 梯度做位移弯折 → 保留原 H/S、替换 V；mask 外逐像素不变 |
| **FR-19** | **P1** | **`AssetLibrary` 离线素材库** | 全部素材带枚举 ID、随包离线；模型只能输出 ID，`ToolRegistry` 做存在性校验；拒绝任何「凭空生成素材」的参数 |
| **FR-20** | **P0** | **区域定位兜底 `RegionSelector`** | 点选 / 框选 / 笔刷 + GrabCut 精修；U3/U4 在模型置信度不足时必须可用；选区只以结构化 `RegionRequest` 上云 |
| **FR-21** | **P1** | **本地素材分析** | 用户从相册选背景/纹理时，端侧生成 `LocalAssetDigest`（地平线、亮度、主色、构图），供 planner 做匹配；素材字节不出端 |
| **FR-22** | **P0** | **色彩管理** | 正确处理 EXIF 方向、ICC/Display P3、8/16 位策略；导出保留原图色彩配置；同设备同版本输出一致 |
| **FR-23** | **P0** | **模型版本固定** | 每个模型写入 `ModelManifest`；升级模型必须跑回归集；撤销/重放记录模型版本 |
| **FR-24** | **P0** | **标识写入** | 显式标识用 Android `Canvas/Paint` 渲染中文；JPEG 用 `ExifInterface` 写 EXIF/XMP；PNG 手写 `tEXt/iTXt` chunk；不提供去除/篡改入口 |
| **FR-25** | **P0** | **导出内存与分辨率策略** | 预览与导出分别预算；12MP 导出分块/分阶段释放；最大输入长边可配置；全分辨率 `seamlessClone` 不达标时降级为预览融合上采样并提示 |

### 2.3 非功能需求（NFR）

| ID | 指标 | 目标值 |
|---|---|---|
| NFR-01 | 云端意图请求 P95 | ≤ 1.2 s；超时 3 s 触发降级 |
| NFR-02 | 端侧 matting（512²） | GPU 档 ≤ 150 ms；中端 CPU 档 ≤ 300 ms（参考：MODNet LiteRT 在 Pixel 8a GPU 约 79 ms/帧 [R6]）；以目标机实测冻结 |
| NFR-03 | 确定性工具单步（预览尺寸） | ≤ 30 ms |
| NFR-04 | 端到端 | 单步 ≤ 2.5 s，3 步计划 ≤ 5 s（含一次云端意图请求与本地工具） |
| NFR-05 | 内存 | 预览最长边 ≤ 2048；**预览与导出分别预算**；12MP 导出分块/分阶段释放，进程峰值 ≤ 设备可用内存 60%；连续处理 10 张无 OOM；最大输入长边可配置（建议默认 ≤ 6000 px） |
| NFR-06 | 画质 | 中间产物用高质量/无损格式，仅最终导出时压缩；导出保持原分辨率 |
| NFR-07 | 包体 | 仅保留 `arm64-v8a`；**基础 APK ≤ 60 MB（不含模型与素材）**；模型包 ≤ 150 MB（已选 MODNet+SCHP 约 91 MB，候选天空/场景模型预留）；素材包单独预算；三者合计的「用户实际安装体积」在 M1 记录，不混为单一口径 |
| NFR-08 | 兼容 | 与当前工程一致：`minSdk 24`（Android 7.0）、`targetSdk 37`、仅 `arm64-v8a`；OpenCV 4.9.0 虽支持 21，但本项目不承诺 32 位 API 21–23 设备；无 NPU/GPU delegate 时自动降级 CPU/XNNPACK |
| NFR-09 | 线程 | 全部推理与像素操作离开主线程；交互式编辑用协程/线程池，长导出用 Foreground Service；WorkManager 仅用于可延迟任务，不用于阻塞式交互 |
| NFR-10 | 热稳定 | 连续 10 张编辑无明显降频导致的失败或超时 |
| NFR-11 | 成本 | **稳态缓存命中**下 P95 ≤ ¥0.005/次；冷启动首轮允许 ≤ ¥0.02；1 万次按 98% 命中估算 ≤ ¥50 |
| NFR-12 | 可用性 | 断网状态下 P0 功能（模板命中部分）100% 可用；U7/U8 等 P1 功能在模型未下载时明确不可用 |
| NFR-13 | 确定性可复现 | **同设备、同模型版本、同后端、同参数**下输出逐像素一致；跨设备以 PSNR ≥35 dB / ΔE00 ≤3 评价（阈值 M1 基线冻结）；INT8/GPU/多线程差异写入基线，不承诺跨设备 bit-exact；合成类 badcase 写成回归用例 |
| NFR-14 | 素材不外传 | `AssetLibrary` 与用户本地选图仅在设备内读取；素材 ID 与 `LocalAssetDigest` 上云但素材字节不上云 |
| **NFR-15** | **色彩一致性** | 处理 EXIF 方向、ICC/Display P3、8/16 位策略；导出保留原图色彩配置；同设备同版本输出一致 |
| **NFR-16** | **模型可复现** | 每个模型带 `ModelManifest`（版本、量化、输入尺寸、后端、SHA-256、许可）；升级须跑回归集 |
| **NFR-17** | **导出性能** | 12MP 导出 ≤ 2 s（旗舰）/ ≤ 4 s（中端）；全分辨率 `seamlessClone` 不达标时降级为预览融合上采样并提示 |
| **NFR-18** | **标识可解析** | 显式标识可见且中文正常渲染；JPEG EXIF/XMP、PNG `tEXt/iTXt` 可解析；日志本地留存 |

### 2.4 隐私与合规需求（**强制项，逐条对应**）

> **本期定位（v1.2）**：个人自用，不对外分发。C-01～C-11 在「分发或对外提供服务」时全部生效；个人自用阶段仍建议保留**单独同意、显式/隐式标识、本地日志**三项能力，作为未来分发的预留，避免上架前返工。个人自用不等于可以随意处理他人肖像：涉及他人照片时仍应取得同意并遵守平台与法律要求。
>
> 「服务提供者名称/编码」不能自行编造；个人自用可先用本地标识占位，分发前须以实际备案/注册主体为准（见 §15 #4）。

| ID | 要求 | 依据 |
|---|---|---|
| C-01 | 人脸属敏感个人信息，处理前取得权利人**单独同意**（不得以冗长用户协议中的概括授权替代） | 《个人信息保护法》第 28/29 条；[R8][R9] |
| C-02 | 提供人脸等生物识别信息编辑功能时，提示使用者依法告知被编辑个人并取得其单独同意 | 《互联网信息服务深度合成管理规定》第 13 条 [R8] |
| C-03 | 对「生成/编辑人脸等生物识别信息」「生成/编辑特殊物体场景」的模型、模板、工具，依法自行或委托专业机构开展**安全评估** | 同上第 15 条 [R8] |
| C-04 | 对生成/编辑内容添加不影响使用的标识，并保存日志信息 | 同上第 16 条 [R8] |
| C-05 | 人脸生成/替换/操控、显著改变身份特征等可能导致公众混淆的编辑，在合理位置**显著标识** | 同上第 17 条 [R8][R10] |
| C-06 | 导出/下载/复制的文件中必须含**显式标识**（图片适当位置的显著提示） | 《人工智能生成合成内容标识办法》第 4 条（2025-09-01 施行）[R10][R11] |
| C-07 | 文件元数据中添加**隐式标识**，含生成合成属性信息、服务提供者名称/编码、内容编号等制作要素 | 同上；央视网解读 [R11][R12] |
| C-08 | 不得提供删除、篡改、伪造、隐匿标识的工具或服务 | 深度合成规定第 18 条；标识办法第 10 条 [R8][R10] |
| C-09 | 履行算法备案、安全评估手续时提供标识相关材料 | 标识办法第 12 条 [R11] |
| C-10 | 上架前核对适用的 API 数据条款；区分公开隐私政策与单独商务约定，区分不同 endpoint 的实际语义 | 见 §10.3 |
| **C-11** | **不得以「实现上不用生成式 AI」为由免除标识义务**：规制落点是功能效果与是否可能致公众混淆，第 17 条含「其他具有生成或者显著改变信息内容功能的服务」兜底项；且云端仍有大模型参与意图解析，通过调用 API 提供 AIGC 服务仍可能被认定为服务提供者 | 《深度合成规定》第 17 条；[R30][R31][R32] |

> 适用范围提示：C-06~C-09 的义务主体是「服务提供者」。个人自用、不对外提供服务的阶段风险较低；**一旦分发或对外提供服务即全部生效**，建议在立项时同步启动，不要留到上架前补。
>
> **v1.1 反向收益**：v1.1 的素材全部来自预设库或用户本地、参数固定、效果完全可复现，安全评估材料与 badcase 回归测试反而比生成式方案更好写。

### 2.5 明确不做（Out of Scope）

**v1.1 修订原则**：砍掉的是**生成式实现**，不是功能入口。原 v1.0 第一条把「换背景 / 换装」整体划出范围，现拆成两栏重新表述。

| 类别 | 不做 | 做什么替代 |
|---|---|---|
| 生成式实现 | 凭空生成不存在的场景 / 光照 / 服装；扩散模型、I2I 重绘、文生图、风格化迁移；AI 换装（换款式） | §7.4 的**确定性合成**：背景替换、服饰换色 / 面料图案替换 |
| 语义越界 | 换服装款式 / 轮廓 / 袖长 / 领型 / 姿态；凭空补出被遮挡的身体部位 | 明确拒绝并提示「仅支持换色与换面料，不支持换款式」 |
| 能力边界 | 大面积路人/电线/文字消除、无纹理大面积补全、被遮挡身体补全 | 仅支持小面积纹理填充；超出时提示用户框选更小区域或放弃 |
| 分发形态 | 本期不做转发代理、多用户/账号体系、云端素材存储 | 个人自用；未来分发时按 §10.1 加代理与鉴权 |
| 其他 | 视频编辑、实时相机流处理（本期） | — |
| 数据 | 上传原图/缩略图到云端（默认禁止，见 §1.2）；上传素材库字节或用户本地选图 | 素材 ID 可上云，素材字节不出端（NFR-14） |
| 数据 | 云端保存用户编辑历史、素材、成片 | 全部本地 |
| 平台 | iOS / HarmonyOS / Web（本期仅 Android） | — |

> **不做的根本原因**：端侧扩散模型在手机上延迟 / 发热 / 画幅限制不可控，且生成式重绘会破坏「确定性可撤销、参数可复现」的产品质感。v1.1 的替代路径恰好把这些短板转成了优势（NFR-13）。

---

## 3. 总体架构

### 3.1 分层

```
UI 层  Kotlin / Jetpack Compose
  对话输入框 │ 计划卡 │ 分步进度 │ 画布(对比滑杆) │ 区域选择 │ 撤销栈 │ 导出
        │ 共用同一 ToolRegistry
        ▼
编排层  Orchestrator
  IntentGateway → PlanValidator → PlanExecutor → ResultVerifier
  · 参数限幅 / 工具存在性 / 依赖补全 / 环检测 / 迭代上限
  · RegionSelector（点选/框选/笔刷兜底）
  · ModelManager（模型清单/版本/下载校验）
  · 离线降级路由器 · 模板工作流缓存 · 指令脱敏
        │ 仅文本                              │ 仅本地调用
        ▼                                     ▼
云端 Planner                             端侧像素引擎
  DeepSeek API                             OpenCV 4.9+ (C++ NDK)
  deepseek-flash                           · 调色/几何/形态学/inpaint/融合
  意图 → EditPlan JSON                     · 图层合成 · 分块导出 · 色彩管理
  (无图像输入)                             LiteRT / NCNN
                                           · MODNet 人像软 alpha matting
                                           · SCHP-ATR 服装 / 天空 / 多目标定位
                                           本地资产：原图/蒙版/图层/成片
```

### 3.2 主数据流

```
① 用户打开图片（本地） + 输入一句话
② 端侧 CV 生成 CanvasDigest（纯文本：objects/sky/背景/选区） ──┐
③ 指令脱敏 ────────────────────────────────────────────────┼→ ④ HTTPS → DeepSeek API
   固定前缀：system + tool schemas + few-shot ┘              （仅文本，无图像）
⑤ 返回 EditPlan JSON
⑥ PlanValidator 校验（不过则修正/拒绝/降级）
   └→ objects/sky 置信度不足 → RegionSelector（点选/框选/笔刷）→ 更新摘要
⑦ 多步 → 计划卡确认；单步 → 直接执行
⑧ PlanExecutor 按拓扑序调用 ToolRegistry
        └→ 预览尺寸跑全链路，每步回调刷新画布
⑨ ResultVerifier 本地可计算自检
⑩ 用户满意 → 原分辨率参数重放 → 嵌显式+隐式标识 → 分块/降级导出
```

### 3.3 模块职责

| 模块 | 职责 | 是否联网 |
|---|---|---|
| `CanvasAnalyzer` | 读本地图 → 输出 `CanvasDigest`；主体/多目标检测、场景分类、天空置信度、主色统计；对用户本地素材生成 `LocalAssetDigest` | 否 |
| `IntentGateway` | 组装请求、指令脱敏、超时/重试、429 退避、密钥读取 | 是 |
| `PlannerClient` | 调用 `deepseek-flash`，启用 JSON Output，读 `usage` 命中率 | 是 |
| `PlanValidator` | schema 校验、工具存在性、参数限幅、依赖补全、环检测、步数上限 | 否 |
| `ToolRegistry` | 工具定义与执行入口，UI / Agent 共用 | 否 |
| `PlanExecutor` | 拓扑推进、分步回调、慢任务异步、撤销点记录 | 否 |
| **`RegionSelector`** | **点选 / 框选 / 笔刷 + GrabCut 精修**，产生 `RegionRequest`；U3/U4 模型置信度不足时的兜底 | 否 |
| **`ModelManager`** | **模型清单 / 版本 / SHA-256 / 下载校验 / 回滚**；模型不可用时明确提示 | 视分发方式 |
| **`ColorPipeline`** | **EXIF 方向、ICC/Display P3、8/16 位策略、Bitmap↔Mat 色彩一致性** | 否 |
| **`IdentifierWriter`** | **中文显式标识、JPEG EXIF/XMP、PNG `tEXt/iTXt`、本地日志** | 否 |
| `ResultVerifier` | 蒙版尺寸对齐、边缘光晕、过曝/欠曝比例、alpha 范围、局部越界；阈值可配置，必要时追加修复步 | 否 |
| `ExportPipeline` | `PreviewReplay` 原图重放、分块/降级导出、显式角标、EXIF/XMP 与 PNG chunk、格式编码 | 否 |
| `TemplateStore` | 高频指令模板 / 上次成功计划缓存 / 离线兜底 | 否 |
| **`AssetLibrary`** | **离线预设素材库**：背景图 / 色卡 / 面料纹理，按枚举 ID 索引；提供素材字节与元数据，**不联网** | 否 |
| **`CompositeOps`** | **确定性合成算子集**：Reinhard LAB 色彩迁移、直方图匹配、`seamlessClone` / `illuminationChange`、接触阴影、HSV 换色、纹理平铺与梯度弯折（§7.4） | 否 |

---

## 4. 技术选型

| 层 | 选型 | 版本 / 许可 | 理由 | 备选 |
|---|---|---|---|---|
| 云端 Planner | **DeepSeek V4.1 Flash**（`deepseek-flash`，当前 V4.1-Flash-1017） | API；权重 MIT | 原生多模态、Tool Calls、JSON Output、1M 上下文、响应快、价格低；输出仅文本恰好匹配「只出计划」[R3][R13] | `deepseek-v4-pro`（不支持图像理解、更贵、并发 500 vs 2500）[R13] |
| 工程基线 | Kotlin 2.2.10 + Compose BOM 2026.02.01 + AGP 9.5.0；`minSdk 24` / `targetSdk 37` | — | **已落地**：NDK/CMake、OpenCV 4.9.0、Retrofit/OkHttp、Room、ExifInterface、ONNX Runtime 1.20、Compose | 若未来要兼容 Android 7 以下，另开分支；本期不承诺 |
| Android 语言 | Kotlin + C++ NDK | — | 像素管线在 native 层一次跑完，避免 JNI 往返 | — |
| 图像处理主干 | **OpenCV for Android** | 4.9.0+ / Apache 2.0 | Maven Central 一行依赖（`org.opencv:opencv:4.9.0`），`OpenCVLoader.initLocal()`；需确认 AAR 包含 `photo` 模块，否则自编 SDK [R7][R14] | 自编 SDK（需 contrib / 定制 CMake 时） |
| **图像解码 / 色彩** | **ImageDecoder/BitmapFactory + AndroidX ExifInterface + `ColorSpace`** | 系统 / Apache 2.0 | 处理 EXIF 方向、ICC/Display P3、8/16 位策略；避免偏色与方向错误 | 自建色彩管线；第三方解码库仅作备选 |
| 人像抠图 | **MODNet ONNX FP32**（ONNX Runtime 1.20） | Apache 2.0 | **已实装**：约 25 MB，512²；XNNPACK 优先、创建失败自动回退 CPU EP；模拟器 512 稳态约 5.4s，384 约 2.5s | 发丝/纱/玻璃场景：混合精度量化或专用 matting 模型 |
| **主体 / 多目标定位** | **`CanvasAnalyzer` 启发式 + `RegionSelector`** | — | **M3 冻结**：皮肤显著性 bbox + 场景/天空启发式；不引入开放词表检测；objects 置信度 0.5 仅作提示，不替代执行 mask | ML Kit / 自训检测候选，需真机实测后另开版本 |
| **天空 / 区域分割** | **启发式 sky confidence + `RegionSelector`** | — | U4 无可靠天空 mask 时强制用户选区；M3 不引入专用天空模型 | DeepLabV3+ / BiSeNet 候选，后续评估 |
| 通用物体定位 | **默认 `RegionSelector`（点选/框选 + GrabCut）**；OWLv2 仅作实验 | OpenCV / 视模型 | 移动端 OWLv2 重、慢；用户选区最稳、可控 | OWLv2 + GrabCut（仅旗舰实验，不作为默认） |
| **服装区域定位** | **SCHP-ATR INT8 ONNX** | MIT（复核） | **已实装**：约 79 MB、512²、固定词表；QDQ 只能走 CPU EP，QOperator 候选可走 XNNPACK 但模拟器更慢 | OWLv2 定位 + GrabCut 精修；用户点选 / 笔刷兜底 |
| **色彩和谐化 / 融合** | **OpenCV `photo` 模块** | Apache 2.0 | `seamlessClone`（NORMAL/MIXED/MONOCHROME_CLONE）、`colorChange`、`illuminationChange` 现成可用 [R35] | 自建 Reinhard LAB 迁移 + 直方图匹配 [R36] |
| **素材库** | **`AssetLibrary`**（离线打包 + 用户本地相册选图） | 视素材许可 | 所有像素来源可枚举、可复现；支持「用我相册那张当背景」，不破图像不出端 | — |
| 端侧推理 | **ONNX Runtime Android 1.20**（XNNPACK / CPU EP） | MIT | **已实装**：MODNet 走 XNNPACK；SCHP QDQ 自动回退 CPU；统一运行时减少包体 | LiteRT / NCNN 作为后续 A/B |
| 网络 | OkHttp + Kotlin Coroutines | — | **已实装**：DeepSeek JSON Output、3s 连接 / 10s 读取、失败回退规则 | Retrofit 可后续接入 |
| 密钥存储 | **`.env` → BuildConfig + SharedPreferences** | 个人自用 | 构建时注入 Key；UI 可覆盖/禁用云端；`.env` 已 gitignore；Key 会进入 APK | 分发前改 Android Keystore / 服务端代理并轮换 |
| **标识写入** | **Android `Canvas/Paint` + ExifInterface + PNG chunks** | 系统 / Apache 2.0 | 中文显式标识、JPEG EXIF/XMP、PNG `tEXt/iTXt`；OpenCV 只负责像素叠加 | 自写 PNG chunk 编码器 |
| **模型分发** | **随包 assets + `ModelManager`** | 个人自用 | `ModelManifest` + SHA-256 校验；预留文件覆盖、下载、回滚接口；未来分发用 Play Asset Delivery | 自建下载与完整性校验服务 |
| 本地存储 | SharedPreferences（模板/设置） + 应用私有目录（图像/JSONL 日志） | — | **已实装**：`TemplateStore` 最多 50 条；`LocalEditLog` 本地 JSONL；图像不进云端 | Room 可后续用于计划/撤销元数据 |

**模型矩阵（M3 冻结，v1.3 已实装）**

| 任务 | 实际方案 | 体积（约） | 许可 | 目标设备与延迟 | 状态 |
|---|---|---|---|---|---|
| 人像 matting | MODNet ONNX FP32 | 25 MB | Apache 2.0 | 模拟器 512 稳态约 5.4s；真机待测 | **已实装** |
| 服装解析 | SCHP-ATR INT8 QDQ ONNX | 79 MB | MIT | 模拟器加载+解析约 32s；真机待测 | **已实装（CPU EP）** |
| 主体 / 显著性 | `CanvasAnalyzer` 皮肤/颜色启发式 | 0 | 自研 | 预览 ≤400ms 目标 | **M3 冻结** |
| 多目标人物 | 未引入专用检测；U3 走 `RegionSelector` | 0 | — | 交互级 | **兜底** |
| 天空分割 | 启发式 sky confidence；U4 走 `RegionSelector` | 0 | — | 交互级 | **M3 冻结** |
| 通用物体 | `RegionSelector` + GrabCut | 0（OpenCV） | Apache 2.0 | 交互级 | **默认** |
| 场景分类 | `CanvasAnalyzer` 颜色/亮度/皮肤规则 | 0 | 自研 | ≤50ms（预览采样） | **已实装** |

> 说明：M3 模型矩阵已冻结为“MODNet + SCHP + 启发式 CanvasAnalyzer + RegionSelector”。ML Kit/DeepLab/OWLv2 等候选未进入当前版本；如后续引入，需重新跑 U3/U4 回归与真机性能验收。

**选型排除说明**：

- 不选端侧小 VLM（FunctionGemma / MiniCPM-V 等）：本方案「看图」由 `CanvasAnalyzer` 用 CV 完成并输出文本摘要，云端模型做的是**意图分类 + 槽位填充**，不必带视觉编码器，省内存、省发热、省包体。
- **不选任何生成式模型**：见 §2.5 与 §7.4。注意区分——v1.1 允许的是**判别式定位模型**（输出 mask / bbox / 类别标签），它不生成画面里不存在的像素；这与「使用 AI 生成内容」不是一回事，也不改变「所有输出像素来自原图 + 素材库」的事实。
- **不把 OWLv2 作为 U3 默认方案**：OWLv2 参数量与 CPU 延迟不适合作为移动端日常路径；U3 默认走 `RegionSelector`（点选/框选 + GrabCut），OWLv2 仅作旗舰实验项。U4 的天空 mask 同理：模型不可靠时要求用户选区，不硬出图。
- **模型分发与版本固定**：个人自用可随包或侧载模型；所有模型必须带 `ModelManifest` 与 SHA-256，升级跑回归集。未来分发改用 Play Asset Delivery 或自建下载服务，不能默认首次启动静默下载大模型。
- **素材许可单独核查**：预设背景图、面料纹理、色卡若取自第三方图库，需确认可随 APP 分发；抠图模型方面，部分商用权重（如 BRIA RMBG 系列）带非商用条款，个人自用无碍，**分发前须换成 MIT / Apache 2.0 的权重**[R16]。
- **色彩管理单独列项**：图像编辑器最常见的「看起来没问题但导出偏色」来自 EXIF 方向与 ICC/P3 处理；M1 就应把 `ColorPipeline` 做成公共入口，不允许各工具各自 `BitmapFactory.decode`。

---

## 5. 核心协议与接口定义

### 5.1 `CanvasDigest`（端侧生成，纯文本）

```json
{
  "schema": "canvas_digest/v2",
  "width": 4032,
  "height": 3024,
  "preview_long_edge": 2048,
  "subject": { "type": "portrait", "bbox": [0.18, 0.09, 0.79, 0.94], "ratio": 0.42 },
  "objects": [
    { "id": "o1", "type": "person", "bbox": [0.18, 0.09, 0.79, 0.94], "confidence": 0.96 },
    { "id": "o2", "type": "person", "bbox": [0.82, 0.61, 0.95, 0.98], "confidence": 0.71 }
  ],
  "background": {
    "type": "outdoor_grass",
    "dominant_hue": "green",
    "sky": { "present": true, "confidence": 0.88 }
  },
  "selection": null,
  "region_request": null,
  "local_asset_digest": null,
  "layers": ["background", "subject"],
  "available_tools": ["remove_background","cutout_subject","replace_background","adjust_color",
                      "crop","inpaint","rotate","upscale","split_layers","refine_mask",
                      "parse_garment","recolor_hsv","apply_texture",
                      "harmonize_edge","contact_shadow","select_region"],
  "garment_detected": ["dress"],
  "available_assets": { "background": ["preset:beach_03","preset:studio_gray",
                                       "preset:gradient_01","user_local"],
                        "texture": ["preset:floral_02","preset:plaid_01"],
                        "swatch": ["#D0021B","#2E5BFF","#F5F5F5"] },
  "note": "仅结构化文本摘要 + 枚举 ID，不含任何图像字节；objects 至少覆盖主体与显著干扰物"
}
```

> 该摘要替代「把缩略图传给 VLM」；V4.1 Flash 拿到「用户原话 + 摘要 + 工具清单」即可完成规划，省掉图片 token [R5]。
>
> **v1.2 约束**：
> - `objects[]` 至少包含主体与显著干扰物；置信度 <0.6 时 planner **不得**直接规划区域操作，必须转 `RegionSelector`。
> - `region_request` 记录用户点选/框选/笔刷产生的结构化区域；只传坐标/类型，不传图像字节。
> - `local_asset_digest` 仅在用户从相册选背景/纹理后生成，包含地平线、亮度、主色、构图；同样只传文本。



### 5.2 `ToolRegistry`（Kotlin 骨架）

```kotlin
data class ParamSpec(
    val type: String,                    // "number" | "integer" | "string" | "enum"
    val min: Double? = null,
    val max: Double? = null,
    val enumValues: List<String>? = null,
    val default: Any? = null
)

data class ToolSpec(
    val name: String,
    val description: String,             // 必须写清「什么时候用 / 什么时候不要用」
    val specVersion: String,             // schema 版本，参与缓存前缀稳定性
    val params: Map<String, ParamSpec>,
    val required: List<String>,
    val dependsOn: List<String> = emptyList(),
    val requiresRegion: Boolean = false, // U3/U4 类工具必须由 selection/region_request 提供区域
    val model: ModelManifest? = null,    // 依赖的模型版本，写入重放记录
    val maxInputPx: Int? = null,         // 超过则先降采样或分块
    val costMs: Int,                     // 端侧预计耗时，仅用于同步/异步分流
    val execute: suspend (Map<String, Any>, ImageContext) -> ImageContext
)

object ToolRegistry {
    private val tools = linkedMapOf<String, ToolSpec>()
    fun register(spec: ToolSpec) { require(spec.name !in tools); tools[spec.name] = spec }
    fun get(name: String): ToolSpec? = tools[name]
    fun schemasAsJson(): String = /* 序列化为云端 tool schemas，逐字节稳定 */ ""

    fun validate(name: String, args: Map<String, Any>): ValidationResult {
        val spec = get(name) ?: return ValidationResult.Fail("unknown_tool:$name")
        // 1. 必需字段  2. 类型  3. 限幅（越界直接回落 default，不让模型做计算题）
        // 4. enum 白名单 5. 未知字段拒绝
        return ValidationResult.Ok(coerce(spec, args))
    }
}
```

**设计原则**：让模型做选择题和判断题，不做计算题。羽化几个像素、亮度加多少，由工具默认值与合法性校验兜底；模型给出的颜色值超色域直接用默认值 [R17]。

**v1.2 计划耗时口径**：`EditPlan` 不再包含模型生成的 `estimated_ms`；端侧由 `ToolSpec.costMs` + 拓扑序计算。`confirm_required` 也由端侧策略决定（例如计划步数 ≥3 或含高风险工具时确认），不由模型自由决定，避免越权与误导。

**v1.1 追加约束（素材枚举化）**：`replace_background` / `apply_texture` 的 `source` 参数类型必须是 `enum`，取值只能来自 `AssetLibrary.listIds()` 或字面量 `user_local`。`PlanValidator` 需新增一条：**素材 ID 不在枚举表中即拒绝**，绝不把模型生成的文件名、URL、颜色描述串当作素材来源。这是「算法只能替换、不能创造」在协议层的落点，也是 FR-19 的验收依据。

### 5.3 `EditPlan`（云端返回）

```json
{
  "schema": "edit_plan/v1",
  "understood": "抠掉背景换成浅灰，人物调亮一点",
  "steps": [
    { "id": "s1", "tool": "remove_background", "args": { "model": "modnet", "edge_feather": 1.5 }, "depends_on": [] },
    { "id": "s2", "tool": "replace_background", "args": { "color": "#D9D9D9" }, "depends_on": ["s1"] },
    { "id": "s3", "tool": "adjust_color", "args": { "target": "subject", "brightness": 12 }, "depends_on": ["s2"] }
  ]
}
```

> **v1.2 口径**：云端只返回 `schema`、`understood` 与 `steps`；`estimated_ms` 由端侧按 `costMs` 计算，`confirm_required` 由端侧按步数/风险策略决定，二者不再由模型生成。
>
> **v1.1 补充**：以上为最简三步骤示例。含素材枚举 ID、色彩迁移、泊松融合、`preserve_v` 受保护字段的合成类 `EditPlan` 完整示例见 §7.4.4；相应校验规则见 §5.2 与 §5.5 #9 / #10。

### 5.4 API 请求报文骨架

```http
POST https://api.deepseek.com/chat/completions
Authorization: Bearer ${DEEPSEEK_API_KEY}
Content-Type: application/json

{
  "model": "deepseek-flash",
  "stream": false,
  "max_tokens": 1024,
  "temperature": 0.1,
  "thinking": { "type": "disabled" },
  "response_format": { "type": "json_object" },
  "messages": [
    {
      "role": "system",
      "content": "<STABLE_PREFIX>\n你是修图意图解析器。只输出 json，不要解释。\n可用工具 schema：...（固定，逐字节不变）\nfew-shot 示例：...（固定）\n输出格式示例：{\"schema\":\"edit_plan/v1\",\"steps\":[...]}\n</STABLE_PREFIX>"
    },
    {
      "role": "user",
      "content": "用户指令：抠掉背景换成浅灰，人物调亮一点\n画布摘要：{...CanvasDigest...}"
    }
  ]
}
```

**四条硬规则**：

1. **JSON Output** 必须同时满足三点：`response_format={"type":"json_object"}`、prompt 中出现 "json" 字样、给出目标格式示例；并控制 `max_tokens` 防截断 [R18]。
2. **即使开启 JSON 模式，应用侧仍须做 schema 校验** —— 模型可能漏字段或类型不符 [R19]。
3. **缓存布局**：`system`、tool schemas、few-shot 组成逐字节稳定的大前缀放最前；用户指令、时间戳、session id 等易变内容一律放最后。**绝不在 system 里插值「当前时间是…」**，一个变量就让命中率归零 [R20][R21]。
4. **关闭 thinking / 压低 `reasoning_effort`**：意图→计划是短分类任务，思维链照烧输出 token 与延迟，缓存救不了 [R20]。
5. **检查 `finish_reason`**：8 步计划在 512 token 下可能被截断；本项目 `max_tokens` 设为 1024，收到 `length` 时直接降级/重试，不解析不完整 JSON [R18]。

监控：`usage.prompt_cache_hit_tokens / (hit + miss)`，长期接近 0 就先查前缀是否字节级稳定 [R20]。冷启动首轮 20K 前缀约 ¥0.02，成本指标按 §2.3 NFR-11 的稳态口径考核。

### 5.5 `PlanValidator` 校验清单

| # | 检查 | 失败动作 |
|---|---|---|
| 1 | 顶层 schema 与版本 | 拒绝 → 降级模板 |
| 2 | 每个 `tool` 在 `ToolRegistry` 中存在 | 剔除该步并提示 |
| 3 | 参数类型 / 必需字段 / 枚举白名单 | 修正或回落 default |
| 4 | 参数限幅（亮度 ±50、羽化 0–8 px、缩放 0.25–4× 等） | 截断到边界 |
| 5 | `depends_on` 引用存在、无环 | 拓扑排序失败则拒绝 |
| 6 | 步数 ≤ 8 | 截断并提示用户拆分 |
| 7 | **计划中至少包含一个可执行工具步骤**；执行器另行校验「实际调用」 | 拒绝（防「已处理」幻觉式确认）[R22] |
| 8 | 迭代上限：单次会话 ≤ 8 轮工具调用 | 强制终止（防死循环）[R22] |
| **9** | **素材 ID 枚举校验**：`source` / `texture` / `swatch` 必须命中 `AssetLibrary` 或为 `user_local` | 拒绝该步并提示用户从素材库选择（FR-19、§5.2） |
| **10** | **语义越界拦截**：意图含「换款式 / 换件外套 / 生成不存在的场景」等超出确定性合成能力的表述 | 不执行，直接返回「仅支持换色与换面料 / 背景素材替换」的能力说明（§2.5、RK-10） |
| **11** | **区域前提校验**：`requiresRegion=true` 的工具必须有 `selection` / `region_request`，或 `objects`/`sky` 置信度 ≥0.6 | 转 `RegionSelector` 等待用户选区，不强行执行 |
| **12** | **分辨率 / 内存前提**：输入长边、目标分辨率、模型 `maxInputPx` 在预算内 | 降采样/分块，或拒绝并提示上限 |

---

## 6. 关键流程

### 6.1 一句话编辑（主流程）

```
用户 ── 指令+图片（本地）
  │
  ├─ CanvasAnalyzer → CanvasDigest ─────────────┐
  ├─ 指令脱敏 ─────────────────────────────────┤
  │                                             ▼
  │                              IntentGateway → DeepSeek API（仅文本）
  │                                             │ EditPlan JSON
  │                                             ▼
  │                                      PlanValidator（§5.5）
  │                                             │
  │                        ┌────────────────────┴───────────────┐
  │                   校验失败 / 超时 / 429              校验通过
  │                        ▼                                   ▼
  │              TemplateStore 离线降级              计划卡（多步）/ 直接执行
  │              UI 标注「离线模式」                          │
  │                                                          ▼
  │                                            PlanExecutor 按拓扑推进
  │                                             每步：预览尺寸执行 → 回调刷新画布
  │                                             同步/异步按 costMs 分流
  │                                                          ▼
  │                                            ResultVerifier 本地自检
  │                                             （异常 → 追加修复步或提示）
  │                                                          ▼
  └────────────── 用户满意 → ExportPipeline：原图重放 → 嵌标识 → 分块/降级导出
```

> **U3/U4 定位兜底**：若 `CanvasDigest` 的 `objects` 或 `sky` 置信度不足，UI 不把「猜区域」交给 planner，而是直接进入 `RegionSelector`（点选/框选/笔刷 + GrabCut），把 `RegionRequest` 写入摘要后再规划。用户选区是结构化坐标，不上传图像字节。

### 6.2 抠图链路（三步，别指望单模型通吃）

```
语义定位（轻量分割 / 用户点选）
   → 粗蒙版（框 / 点 / mask）
软边 matting（MODNet，trimap-free）
   → 软 alpha，保留发丝、纱、半透明材质
蒙版对齐 + 羽化（OpenCV）
   → resize 回原图分辨率 → 形态学 / 高斯羽化
合成（OpenCV）
   → out = fg * alpha + bg * (1 - alpha)
```

要点：

- **尺寸对齐必做**：抠图节点内部会重采样，输出蒙版尺寸未必等于原图，进合成前必须把蒙版拉回原图分辨率 [R23]。
- **量化精度**：NCNN INT8 在发丝区 PSNR 约 31.2 dB，混合精度（敏感层保 FP16）约 35.8 dB，模型体积 21 MB → 35 MB、耗时 32 ms → 45 ms [R15]。人像场景建议直接用混合精度档。
- **segmentation ≠ matting**：SAM 类负责「选哪个」，matting 负责「抠得干净」，两者串联 [R16]。

### 6.3 `PreviewReplay`（内存与画质的关键）

```
原图(12MP) ─inSampleSize→ 预览图(最长边≤2048)
                              │
             全链路在预览图上执行，记录 {tool, args} 序列
                              │
用户确认 → 用原图重新执行同一 {tool, args} 序列
        → 蒙版按原图分辨率重算/对齐
        → 嵌显式角标 + 元数据隐式标识
        → 编码导出（仅此处做有损压缩）
```

- 12MP 的 ARGB_8888 Bitmap ≈ 48 MB/张，多份中间产物直接 OOM；预览降采样是硬性要求。**导出阶段同样要预算**：原图、前景、背景、蒙版、临时 Mat、输出可能同时存在，必须分块/分阶段释放，不能假设「预览能跑，导出也能跑」。
- 中间产物用高质量/无损格式传递，**只在最终输出时压缩**，否则多步后画质持续劣化 [R22]。
- 撤销栈记录的是「参数序列」而非「位图快照」，内存可控且支持任意步回滚；每条记录同时保存 `ModelManifest` 版本，避免模型升级后重放结果漂移。
- **全分辨率 `seamlessClone` 先实测再承诺**：12MP 泊松求解可能超过 2 s。未达标时降级为「预览分辨率融合 → 上采样回原图」，并在导出前提示；不能为了指标硬扛 OOM/超时。
- 导出前统一走 `ColorPipeline`：EXIF 方向 → ICC/Display P3 → 8/16 位策略 → 编码，避免「预览正常、导出偏色」。

### 6.4 离线降级

| 触发 | 策略 |
|---|---|
| 无网络 | 意图文本与 `TemplateStore` 中的模板做本地规则匹配（关键词 + 正则 + 别名表） |
| 超时 3 s / 429 / 5xx | 指数退避 1 次；仍失败则降级模板，或复用「上一次成功的同意图计划」 |
| API 余额耗尽 | 全量模板模式，UI 明确提示 |
| 模板未命中 | 明确告知「该指令需要联网解析」，并把用户引导至手动工具栏（APP 不变砖） |

---

## 7. 端侧图像管线详细设计

### 7.1 OpenCV 覆盖矩阵

| 能力 | OpenCV 实现 | 备注 |
|---|---|---|
| 色彩空间 / 11 参调色 | `cvtColor`、`LUT`、通道运算、直方图均衡 | 确定性、可参数化 |
| 几何变换 | `warpAffine`、`warpPerspective`、`resize`、`flip` | 证件照比例裁剪 |
| 蒙版后处理 | 形态学、`GaussianBlur`、距离变换 | 羽化 / 边缘平滑 |
| 局部消除 | `inpaint`（Telea / NS）、`seamlessClone` | **仅小面积纹理**；大面积路人消除需分割 + 邻近纹理克隆，不承诺生成式重绘 |
| 图层合成 | 加权合成、`copyTo(mask)` | 主体层 / 背景层独立操作 |
| **光色和谐化 / 融合（v1.1）** | `seamlessClone`、`illuminationChange`、`colorChange`、LAB 色彩迁移、直方图匹配、接触阴影 | 背景替换与服饰换色/贴图的核心算子，详见 §7.4 [R35][R36] |
| 标识嵌入 | **OpenCV 仅负责像素叠加**；中文文字用 Android `Canvas/Paint` 预渲染；JPEG EXIF/XMP 用 `ExifInterface`；PNG 写 `tEXt/iTXt` chunk | 显式 + 隐式，见 §10.2；`putText` 不支持中文 |
| 色彩管理 | `ExifInterface` 读方向/ICC、`ColorSpace` 转换、`Bitmap.copy` 到统一工作空间 | 避免偏色与方向错误，见 §7.6 |
| 导出 | `imencode` + `BitmapFactory` 回写；分块/降级路径 | 仅此处压缩；元数据不在 OpenCV 层写 |

**OpenCV 扛不了的**：发丝级软边 alpha（GrabCut 交不出）、大面积语义填充、任何生成式变换 → 由端侧 DNN、`RegionSelector` 用户选区或产品范围裁剪解决。

### 7.2 Android 性能优化清单

| 项 | 做法 | 依据 |
|---|---|---|
| 内存分配 | 预分配并复用 `cv::Mat`，避免重复分配与拷贝 | OpenCV 官方把「内存效率」列为移动端第一原则 [R24] |
| JNI 开销 | 整条像素管线在 native 层一次跑完；不在循环里调 Java API；避免 Bitmap↔Mat 来回拷贝 | 官方明确 JNI 调用昂贵 [R24] |
| Bitmap 访问 | NDK 直接访问而非 `Bitmap.getPixels()` | 640×480 实测：8.7 ms → 2.1 ms [R25] |
| 废弃 API | 不再新用 RenderScript（API 31 起废弃） | [R25] |
| 异构降级 | GPU delegate 不可用时回落 CPU/XNNPACK；注意 GPU delegate 在 Android 12+ 后台服务不可用、持续负载会降频，长任务优先 CPU 后端 | [R26] |
| 线程 | `Dispatchers.Default`；长任务 Foreground Service / WorkManager；双缓冲队列 | — |
| ABI | 仅 `arm64-v8a`；静态链接 `.so`，不依赖 OpenCV Manager | [R14] |
| 输入尺寸 | 限制最长边（默认 ≤6000 px，可配置）；超大图先降采样或分块，禁止把 48MP 原图直接转 ARGB_8888 | [R22] |
| 导出内存 | 分块/分阶段释放；12MP 导出单独预算；全分辨率 `seamlessClone` 不达标时走预览融合上采样 | 见 §7.6 |
| 模型版本 | 每个模型带 `ModelManifest` + SHA-256；下载后校验、失败回滚；模型升级跑回归 | NFR-16 |
| 色彩管理 | 统一走 `ColorPipeline`，不在各工具内各自 decode | NFR-15 |
| 版本 | `implementation 'org.opencv:opencv:4.9.0'`，初始化 `OpenCVLoader.initLocal()` | [R7][R14] |

### 7.3 常见故障与对策（工程复盘项）

| 现象 | 根因 | 对策 |
|---|---|---|
| 模型回「已处理」但图没变 | 幻觉式确认，未实际调用工具 | 强制工具调用校验（§5.5 #7）[R22] |
| 「天空调蓝」变整图变蓝 | 区域定位失败 | 参数限幅 + 语义分割区域限制 + 前后对比校验 [R22] |
| 反复执行同一工具 | 缺全局规划，陷入局部最优 | 最大迭代 8 次强制终止 [R22] |
| 多步后越来越糊 | 每步有损压缩 | 中间产物高质/无损，仅导出压缩 [R22] |
| 大图进程被杀 | 未压缩直接处理 | 限制输入长边 + 预览降采样 + 导出分块/分阶段释放 [R22] |
| 多用户/多任务排队 | 同步处理阻塞 worker | 本地异步任务队列 + 多 worker 并发（单 APP 内为多协程/多线程池）[R22] |
| 标识中文变问号/乱码 | OpenCV `putText` 只支持 ASCII/Hershey 字体 | 用 Android `Canvas/Paint` 或 FreeType 预渲染中文标识 |
| 导出后 EXIF/XMP 丢失 | `imencode` 不负责自定义元数据 | JPEG 用 `ExifInterface`，PNG 手写 `tEXt/iTXt` chunk |
| 预览正常、导出偏色/方向错 | EXIF 方向、ICC/P3、位深未统一 | 统一 `ColorPipeline`；导出前做色彩空间与方向回归 |
| 全分辨率导出超时/OOM | `seamlessClone` 与多份 12MP Mat 同时存在 | 先实测；不达标走预览融合上采样 + 分块导出；限制输入长边 |
| 模型升级后重放结果漂移 | 只记录 `{tool,args}`，未记录模型版本 | 撤销/重放记录 `ModelManifest`；升级跑回归集 |

### 7.4 非生成式合成管线（v1.1 新增）

> **本节回答的问题**：不用生成式模型，如何保住「换背景 / 换装」的一句话交互？
> **答案**：大模型只输出「选哪个素材 + 用哪组确定性算子 + 参数多少」，所有输出像素可还原为「原图像素 + `AssetLibrary` 像素 + 固定参数」。

#### 7.4.1 能力分级（「不使用 AI」的准确口径）

| 档 | 构成 | 结论 |
|---|---|---|
| L0 严格零神经网络 | GrabCut + HSV 阈值 + 色彩迁移 + 泊松融合 + 透视变换 | 技术上成立，但选区质量差，发丝 / 复杂服装交不出可用蒙版 |
| **L1 判别式定位 + 确定性合成（本方案）** | MODNet / SCHP-ATR / 轻量分割或 `RegionSelector` 只输出 **mask 与类别标签**，像素合成全部走 OpenCV 确定性算法 | ✅ **采纳**。判别式模型回答「哪里」，不回答「长什么样」，不生成画面里不存在的像素 [R33][R34][R37] |
| L2 生成式重绘 | 扩散 / I2I 凭空生成像素 | ❌ 排除（§2.5） |

> 已有同类实践可参照：OWLv2 定位 + GrabCut 精修 + HSV 换色，全程经典图像处理、不引入生成式模型、CPU 几秒出结果 [R37]。但 **OWLv2 不作为默认路径**：模型重、移动端慢，仅作旗舰实验；U3 默认 `RegionSelector` + GrabCut。
>
> 注意：云端仍有大模型做意图解析，所以「不使用 AI」**只能在像素实现层成立**，不能作为整体系统或合规材料的表述。

#### 7.4.2 背景替换（合成式，非生成式）

```
① 抠图：MODNet 软 alpha / GrabCut + 羽化
② 背景源（四选一，全部本地）：
     纯色 | 渐变 | AssetLibrary 预设图 ID | 用户本地相册选图
③ 几何对齐：按主体 bbox 缩放裁切、地平线对齐（horizon_align）
④ 光色和谐化：Reinhard LAB 均值-方差迁移 / 直方图匹配
⑤ 边缘：alpha 羽化 + seamlessClone(NORMAL_CLONE / MIXED_CLONE)
⑥ 接触阴影：形态学 + 仿射投影 + 高斯模糊，按估计光向投软阴影
⑦ 氛围：背景高斯模糊模拟浅景深、色调曲线统一、
        illuminationChange 局部调光
⑧ 高频细节回叠：原图 − 高斯模糊 = 高频层，低权重加回
⑨ 蒙版尺寸对齐 → out = fg·α + bg·(1−α)
```

> **v1.2 补充：用户本地背景**。选图后先由 `CanvasAnalyzer` 生成 `LocalAssetDigest`（地平线、亮度、主色、构图），再让 planner 用该摘要选择 `horizon_align`、色彩迁移与阴影方向；否则 planner 只拿到 `user_local` 枚举，无法知道背景内容，参数只能盲猜。素材字节仍不出端。

OpenCV `photo` 模块直接提供 `seamlessClone`、`colorChange`、`illuminationChange` 三种 flag（`NORMAL_CLONE` / `MIXED_CLONE` / `MONOCHROME_TRANSFER`）[R35]；Reinhard 色彩迁移在 LAB 空间几十行代码即可实现 [R36]。工程上被反复验证的顺序是 **几何对齐 → 颜色校正 → 泊松融合 → 细节修整**，合成质量是流程问题而不是单点技巧 [R38]。

**允许「用我相册那张当背景」**：素材来源从「生成」变成「选择」，用户感知的自由度其实不低，而且完全不破 §1.2 的图像不出端边界（NFR-14）。

#### 7.4.3 服饰换色 / 面料图案替换（重着色式，非换款式）

```
① 服装区域 mask：
     SCHP / ATR 人像解析（固定词表：upper_clothes / dress / skirt /
     pants / scarf），INT8 ONNX 约 65 MB、可 CPU 跑、确定性 [R33][R34]
     或 OWLv2 框 + GrabCut 精修 [R37]
     或 用户点选 / 笔刷（`RegionSelector`，作为兜底）
② mask 后处理：形态学开闭（去掉纽扣被误判的空洞）
              + GaussianBlur 羽化（领口袖口 2–3 px 过渡）[R39]
③ 【换色】HSV：只改 H（可微调 S），**V 保持原图**
④ 【换图案】纹理 ÷ 自身强模糊 = 去自带光照 → 无缝平铺（15% 重叠混合）
            → 用原图亮度 Sobel 梯度做位移图，让图案随褶皱弯折
            → 保留原图 H/S、用纹理亮度替换 V [R40]
⑤ 蒙版外逐像素不变
⑥ 全局曲线 / 色彩平衡对齐环境色温
```

> **v1.2 补充：多色图案**。只替换 V 会丢掉纹理本身的多色信息。若用户要「红色碎花」，应先把图案颜色映射到目标色域，再用纹理亮度做 V 替换；或先 `recolor_hsv` 再 `apply_texture`。验收时需明确「图案颜色保留」还是「只保留明暗纹理」，不能默认两者兼得。

**「保留 V」是整套里最值钱的一行**：只换色相、保留原像素的饱和度与明度，褶皱、阴影、高光、布料质感全部逐像素继承，看起来像重新染了色而不是贴了一层塑料板 [R41][R39]。贴图案同理——先剥掉纹理自带的光照，再用原图亮度梯度弯折图案，就不会和衣服本身的明暗打架 [R40]。

**能力天花板（必须写进产品文案）**：手工换装最难的三件事是版型透视、面料记忆、光影匹配 [R42]。算法式能解决后两件的观感，但**第一件天然解决不了**——袖长、领型、裙摆、姿态一律不变。能做的是「同一件衣服换个颜色 / 面料」，不是「换一件衣服」。

#### 7.4.4 大模型只做选择题：`EditPlan` 示例

**示例 A —「换成海滩，人稍微暖一点」**

```json
{ "schema":"edit_plan/v1", "steps":[
  { "id":"s1","tool":"cutout_subject",
    "args":{"model":"modnet","feather_px":1.5},"depends_on":[] },
  { "id":"s2","tool":"replace_background",
    "args":{"source":"preset:beach_03","fit":"cover","horizon_align":0.62,
            "color_transfer":"reinhard_lab","std_ratio_clamp":[0.5,2.0],
            "contact_shadow":true,"bokeh_px":8},"depends_on":["s1"] },
  { "id":"s3","tool":"harmonize_edge",
    "args":{"method":"seamless_clone","flags":"MIXED_CLONE",
            "detail_blend":0.15},"depends_on":["s2"] }
]}
```

**示例 B —「把裙子换成红色碎花」**

```json
{ "schema":"edit_plan/v1", "steps":[
  { "id":"s1","tool":"parse_garment",
    "args":{"classes":["dress"],"model":"schp_atr_int8"},"depends_on":[] },
  { "id":"s2","tool":"refine_mask",
    "args":{"morphology":"open_close","blur":5},"depends_on":["s1"] },
  { "id":"s3","tool":"recolor_hsv",
    "args":{"hue":352,"sat_scale":1.1,"preserve_v":true},"depends_on":["s2"] },
  { "id":"s4","tool":"apply_texture",
    "args":{"texture":"preset:floral_02",
            "warp_from":"luminance_sobel","strength":30},"depends_on":["s3"] }
]}
```

三条协议约束：素材 ID 必须枚举化（§5.2 / §5.5 #9）；`preserve_v` 设为受保护字段、不允许模型改为 `false`；「换款式」类意图由 §5.5 #10 直接拦截。

#### 7.4.5 「功能不变」的诚实对照表

| 用户说 | 生成式能给 | 本方案能给 | 差在哪 |
|---|---|---|---|
| 换成海滩 | 凭空生成场景 + 重打光 | 预设 / 本地图 + 光色匹配 | 素材必须已存在，不能被「想象」出来 |
| 霓虹夜景，光打在脸上 | 语义级重绘 + 重光照 | 预设图 + 色彩迁移 + 局部调光 | 光源方向冲突时只能换素材 |
| 换成红色碎花裙 | 换色 + 换纹理 + 形变 | 只换 H / 贴图案，裙型袖长全不变 | 轮廓不动 |
| 换件皮夹克 | 换款式 | ❌ 拦截并说明 | 需要无中生有的轮廓 |
| 文生图 | 从零生成 | ❌ 完全不做 | — |

**一句话：能做「替换」，不能做「创造」。** 功能命名同步改为「背景替换与光色匹配」「服饰配色 / 面料图案替换」，边界即自洽。

### 7.5 合成类特有工程坑（v1.1 新增）

| 现象 | 根因 | 对策 |
|---|---|---|
| 融合后出现色带 / 量化噪点 | 在 8 位 uint8 上直接做 LAB 均值方差运算 | **全程 float32 计算，最后才转 uint8**；标准差比值裁剪到 0.5–2.0 防色带 [R38] |
| 侧光主体贴到顺光背景，怎么调都假 | 梯度方向冲突 | 泊松融合救不了；换素材，或 `illuminationChange` + 手动加深减淡 [R38] |
| `seamlessClone` 报错或产生光晕 | mask 太小 / 边界紧贴下巴轮廓 | mask 留出余量 + 羽化；遮挡物（手、头发、眼镜）先从 mask 剔除 [R38] |
| 换色后领口白边、背景同色物体被误染 | HSV 范围硬编码、单一容差 | H/S/V **三个独立容差**（紧 H 带 + 宽 V 带）；mask 形态学开闭 + 高斯羽化 [R41][R39] |
| 衣服变成一块「塑料板」 | 直接覆写 RGB，丢了明度 | 必须 `preserve_v=true`；可选叠加高频层补质感 [R41] |
| 贴图案像浮在表面 | 纹理自带光照与原衣服明暗叠加 | 纹理 ÷ 自身强模糊去光照；用亮度 Sobel 梯度做位移弯折 [R40] |
| 换装结果边缘生硬 | mask 二值化无过渡 | 二值化后 Gaussian 羽化 2–3 px 再合成 [R39][R40] |

### 7.6 内存、导出与标识实现（v1.2 新增）

**内存与导出策略**

| 项 | 策略 |
|---|---|
| 输入上限 | 默认最长边 ≤6000 px；更大图先降采样或分块；禁止把 48MP 原图直接转 ARGB_8888 |
| 预览 | 最长边 ≤2048；单张 RGBA 约 12.6 MB；工具间复用 `cv::Mat`，用完立即 `release()` |
| 导出 | 12MP 单张 RGBA 约 48 MB；原图/前景/背景/蒙版/临时 Mat/输出**不假设可同时常驻**；按阶段释放，必要时分块 |
| 全分辨率融合 | 先实测 12MP `seamlessClone`；不达标时「预览分辨率融合 → 上采样回原图」，并在 UI 说明细节可能略软 |
| 失败兜底 | 捕获 `OutOfMemoryError`/native 分配失败 → 降级分辨率或提示用户；不允许进程被 LMK 直接杀掉 |

**导出与标识**

1. 统一走 `ColorPipeline`：`ExifInterface` 读方向/ICC → 转到工作色彩空间 → 处理 → 导出前转回目标空间。
2. 显式标识：Android `Canvas/Paint` 渲染中文「AI 编辑」角标，再叠加到 Bitmap；OpenCV 只做像素合成。
3. 隐式标识：JPEG 用 AndroidX `ExifInterface` 写 EXIF/XMP；PNG 按规范写 `tEXt`/`iTXt` chunk；记录生成属性、服务提供者占位、内容编号。
4. 日志：本地记录编辑时间、工具序列、模型 `ModelManifest`、标识写入结果；不上传。
5. 不提供去除/篡改标识的入口（C-08）。

**模型与重放**

- 撤销/重放记录 `{tool, args, modelManifest, specVersion}`；模型升级后旧记录标记为「历史版本」，不静默套用新模型。
- 模型下载/更新必须校验 SHA-256；失败回滚到上一版本，并在 UI 提示。
- 同一设备、同模型版本、同后端、同参数下要求逐像素一致；跨设备按 NFR-13 的容差评价。

---

## 8. 云端 Planner 详细设计

### 8.1 职责边界（四条）

1. **意图识别**：把口语指令映射到一个或一组受限域工具名。
2. **工作流搭建**：输出带依赖的 `EditPlan`，做拓扑可执行的步骤编排。
3. **辅助**：可选地做结果自检的「文本层判断」（依据 `ResultVerifier` 回传的结构化指标，而非图片）。
4. **v1.1 追加**：在 `CanvasDigest.available_assets` 的**有限枚举内**做素材选择（背景图 / 色卡 / 纹理 ID），并组合确定性合成算子（§7.4）。

**不做**：决定像素值、生成图片、**生成或指定任何素材库之外的素材**、输出自由文本解释、读取任何图像字节；**不决定用户选区**（区域置信度不足时由 `RegionSelector` 处理）；**不生成 `estimated_ms` / `confirm_required`**（由端侧计算）。

### 8.2 成本模型

官方中文定价（`deepseek-flash`，¥ / 百万 tokens，高峰为北京时区周一至周五 9:00–12:00、14:00–18:00，其余为空闲时段且价格减半）[R13]：

| 项 | 空闲 | 高峰 |
|---|---:|---:|
| 输入（缓存命中） | 0.02 | 0.04 |
| 输入（缓存未命中） | 1 | 2 |
| 输出 | 4 | 8 |

典型单次编辑估算：稳定前缀 20K tokens + 本轮增量 300 tokens + 输出 200 tokens。

| 项 | 空闲 | 高峰 |
|---|---:|---:|
| 20K 命中 | ¥0.0004 | ¥0.0008 |
| 300 未命中 | ¥0.0003 | ¥0.0006 |
| 200 输出 | ¥0.0008 | ¥0.0016 |
| **合计** | **≈ ¥0.0015** | **≈ ¥0.0030** |

即约 **1.5–3 厘/次，1 万次约 ¥15–30**，满足 NFR-11。注意首轮为冷启动（整个 20K 前缀未命中），后续轮次才命中；生产 Agent 实测命中率可达 98%+，账单约为无缓存的 3–4% [R21]。缓存命中与未命中单价差约 50 倍，**前缀布局比 token 绝对数量更决定成本** [R20][R27]。

> **v1.2 成本口径**：NFR-11 的 ¥0.005/次只适用于**稳态缓存命中**；冷启动首轮约 20K × ¥1/M ≈ ¥0.02，允许短暂超标但需监控。98.7% 命中率来自特定业务，不可直接外推；个人自用建议每周看一次 `usage`，命中率长期低于 80% 时先查前缀是否字节级稳定。`max_tokens` 已从 512 调到 1024，若仍频繁 `length` 则降级模板而不是盲目加长。
>
> 价格为编制时的官方文档值，实际以 `usage` 回传与官方最新价格页为准 [R13]。

### 8.3 模型能力核对表

| 能力 | `deepseek-flash` | 本方案是否使用 |
|---|---|---|
| Json Output | ✅ | ✅ 主用 |
| Tool Calls | ✅ | ✅ 可选：也可直接用 JSON Output 出计划 |
| Responses API / Anthropic API | ✅ | 视客户端 SDK 选择 |
| 图像理解（Vision） | ✅ | ❌ **不使用**（按 §1.2 边界） |
| 图像生成 / 编辑输出 | ❌ | ❌ 恰好不需要 |
| 上下文 / 最大输出 | 1M / 384K | 只用很小一段 |
| 并发限制 | 2500 | 个人 APP 远不需要 |

对比：`deepseek-v4-pro` 不支持图像理解、价格显著更高、并发 500 [R13]，本方案不选。

---

## 9. 性能与验收预算

| 阶段 | 预算 | 说明 |
|---|---|---|
| `CanvasAnalyzer`（轻量检测/分割 + 颜色统计） | ≤ 400 ms | 预览图；不含 MODNet/SCHP；多模型串联（主体/多目标/天空）按目标机实测，超预算时只保留主体+天空 |
| 指令脱敏 + 请求组装 | ≤ 20 ms | 本地正则 / 词典；人名/地名 NER 若引入模型另计 |
| 云端意图请求 | P95 ≤ 1.2 s | 超时 3 s 降级 |
| `PlanValidator` | ≤ 10 ms | 纯 JSON 校验 |
| MODNet 512² | GPU ≤150 ms / CPU ≤300 ms | 参考 79 ms（Pixel 8a GPU）[R6]；中端 CPU 目标单独验收 |
| 主体/多目标/天空模型 | ≤150 ms/项 | 启发式 `CanvasAnalyzer`；不达标/低置信度则降级为 `RegionSelector` |
| OpenCV 单步（预览尺寸） | ≤ 30 ms | native 层复用 Mat |
| **背景合成全链路（预览尺寸）** | **≤ 250 ms** | LAB 迁移 + `seamlessClone` + 接触阴影 + 高频回叠；`seamlessClone` 为大头（§7.4.2） |
| **服饰换色 / 贴图（预览尺寸）** | **≤ 180 ms** | SCHP-ATR INT8 mask + HSV + 纹理梯度弯折（§7.4.3）；CPU 降级路径另行压测 |
| **SCHP-ATR INT8 服装解析（473²）** | **≤ 300 ms** | CPU 可跑，无 GPU 依赖；INT8 权重约 65 MB [R33][R34] |
| **Reinhard LAB 色彩迁移（预览尺寸）** | **≤ 25 ms** | float32 逐通道均值方差，一次遍历 [R36][R38] |
| **`seamlessClone` 泊松求解（预览尺寸）** | **≤ 80 ms** | 求解规模随 mask 像素数变化；大 mask 走异步，预览阶段可降采样求解再放大 [R35] |
| **接触阴影 + 浅景深 + 高频回叠** | **≤ 40 ms** | 形态学 + 仿射 + 高斯，全部 native 层 [R35] |
| **HSV 换色 / 纹理平铺弯折** | **≤ 35 ms** | 只改 H 通道 / Sobel 位移图，逐像素查表 [R39][R40][R41] |
| 分步回调 → 画布刷新 | ≤ 50 ms | 主线程只做绘制 |
| 导出（12MP 原图重放 + 标识） | 旗舰 ≤ 2 s / 中端 ≤ 4 s | 后台线程；含标识；**全分辨率 `seamlessClone` 不达标时降级预览融合上采样**（NFR-17） |
| 导出内存峰值 | ≤ 设备可用内存 60% | 12MP 多缓冲；分块/分阶段释放；低端机允许降分辨率 |
| 端到端单步 / 3 步 | ≤ 2.5 s / ≤ 5 s | NFR-04；含一次云端请求 |
| **端到端合成类计划（3–4 步）** | **旗舰 ≤ 6 s / 中端 ≤ 9 s** | 含抠图 + 色彩迁移 + 泊松融合 + 换色/贴图；以 M2 / M2.5 目标机实测为准 |

### 9.1 v1.3 模拟器实测（非验收）

| 项 | 实测 | 说明 |
|---|---:|---|
| MODNet 512 稳态 | 约 5.4 s | XNNPACK，x86_64 AVD |
| MODNet 384 稳态 | 约 2.5 s | 低端快速档候选 |
| SCHP-ATR QDQ parse | 约 32 s | 模型加载 + 推理，CPU EP |
| SCHP-ATR QOperator parse | 约 49 s | XNNPACK session 成功但更慢，暂不采用 |
| seamlessClone 2016×1512 | 约 26 s | 超 2MP 阈值改为 alpha；12MP 导出约 11.6 s |
| 连续 10 张（1600×1200，3 工具+PNG） | 1.34–6.70 s/张 | 首轮预热，无 OOM |
| DeepSeek 在线 smoke | 约 4 s | `remote plan parsed`，仅文本 |
| 12MP 导出 + 元数据 | 通过 | 4032×3024；EXIF/PNG tEXt 可解析 |

> 模拟器数字不作为 NFR 验收结论；三档真机仍需按 §12.2 重测并冻结阈值。

---

## 10. 安全、隐私与合规落地

### 10.1 API Key 保护（纯客户端架构的固有矛盾）

APP 直连 `api.deepseek.com` 时，密钥必然在包内，反编译可取出、存在盗刷风险。

| 阶段 | 措施 |
|---|---|
| 个人自用（本期，已实装） | 根目录 `.env` 构建时注入 `BuildConfig`；`ApiKeyStore` 优先本地覆盖，其次构建时 Key；UI 可“禁用云端”，清除后不再回退 `.env` Key；`.env` 已加入 `.gitignore`；控制台设置额度上限与告警、定期轮换 |
| 分发给他人（演进） | **必须改用自建轻量转发代理 + Keystore/短期令牌**：代理只转发意图文本与 `CanvasDigest`，不接收任何图像；代理侧做鉴权、限流、额度、日志最小化和条款合规 |

> **v1.3 诚实口径**：个人自用采用 `.env` → BuildConfig 是为了开发便利，**Key 会进入 APK，反编译可取出**。当前依靠“禁用云端”开关、额度上限与轮换降低风险；分发前必须撤销该 Key、切服务端代理并改为 Keystore/短期令牌。

### 10.2 标识实现（本地可控，是本架构的加分项）

**v1.2 实现修正**：标识不能用 OpenCV 一次性完成，需分三层：

1. **显式标识**：Android `Canvas/Paint` 渲染中文「AI 编辑」角标（OpenCV `putText` 只支持 ASCII/Hershey 字体，中文会变乱码），再叠加到 Bitmap；位置避开主体面部，不影响正常使用。
2. **隐式标识**：JPEG 用 AndroidX `ExifInterface` 写 EXIF/XMP；PNG 按规范写 `tEXt`/`iTXt` chunk；字段包含生成合成属性信息、服务提供者名称/编码（个人自用先占位）、内容编号。
3. **本地日志**：记录编辑时间、工具序列、`ModelManifest`、标识写入结果；不上传。

**v1.3 实现状态**：`IdentifierWriter` 已实现中文角标、JPEG EXIF、PNG `tEXt`；`LocalEditLog` 已实现本地 JSONL（最多 1MB 轮转、写入加锁、失败不影响导出）；`M5AcceptanceTest` 验证中文标识改像素、元数据可解析、日志可读。

同时**不提供**任何去除/篡改标识的入口（C-08），且在算法备案、安全评估材料中提供标识相关材料（C-09）[R8][R10][R11][R12]。个人自用阶段可先保留能力，分发前再按实际主体补全编码。

### 10.3 数据条款尽调清单（分发前逐项确认；个人自用也建议执行）

官方隐私政策载明：在安全加密与去标识化前提下，**可能将服务所收集的输入及对应输出用于模型训练和服务优化**；用户可在产品内关闭「数据用于优化体验」以退出；并建议不要在对话中主动输入个人敏感信息，如含他人个人信息须事先取得合法授权 [R28]。据此：

| # | 动作 |
|---|---|
| 1 | 关闭账号侧的「数据用于优化体验」开关，并保留截图/日期记录 |
| 2 | 上传前对指令做本地脱敏：证件号/手机号/邮箱用正则；人名、地名为 best-effort，不承诺 100%；UI 明确提示不要输入敏感信息 |
| 3 | 核对**当前适用的 API 条款**，区分公开隐私政策与单独商务约定，区分不同账号类型 / endpoint / 功能的语义差异 |
| 4 | 若使用 Files API：注意文件**默认永久存储**，需显式设置过期与删除并跟踪删除责任 [R29] |
| 5 | 不要误读 `store: false`：Responses 兼容层会忽略 `store` / `prompt_cache_key` / `prompt_cache_retention` 等参数并返回 `store:false`，这**不等于全局零留存承诺** [R29] |
| 6 | 上下文缓存默认开启磁盘缓存、未使用条目数小时到数天自动清理；这是效率机制，不是删除机制，也不能当作「数据未存储」的证据 [R29] |
| 7 | 保留一份带日期的「所依赖的政策与文档版本」记录，供后续合规复核 |

**为什么这些仍重要**：虽然本方案不传图片，但用户随口写进指令里的信息（「把张三身份证那张图的背景换白」）仍是数据处理。把数据最小化做在端侧，是这套架构最实际的合规杠杆。

### 10.4 人像单独同意的交互设计

> **v1.2 口径**：个人自用阶段可先做轻量告知与本地记录；一旦分发，再按 C-01～C-03 补全独立同意弹窗、告知被编辑个人与安全评估。不要把「个人自用」当成永久免做合规的理由。

- 首次触发人像编辑能力时，弹出**独立**的单独同意弹窗（不与用户协议捆绑），说明编辑对象、范围、标识与本地处理方式。
- 弹窗内提示：如图片中包含他人，使用者应依法告知该被编辑个人并取得其单独同意（C-01/C-02）。
- 同意状态本地持久化，可撤回；撤回后禁用相关工具。
- 该能力对应的模型/模板纳入安全评估范围（C-03）。

---

## 11. 风险登记册

| ID | 风险 | 等级 | 对策 |
|---|---|---|---|
| RK-01 | API Key 从客户端泄露盗刷 | 高 | 个人自用：Keystore + 额度上限 + 轮换 + 不提交版本库；分发时上代理（§10.1） |
| RK-02 | 断网 / 超时 / 429 导致功能不可用 | 高 | 模板降级 + 复用上次计划 + 明确离线态 UI（§6.4） |
| RK-03 | 大模型输出非法计划 / 幻觉式确认 | 高 | §5.5 十二项校验 + 强制工具调用 + 迭代上限 |
| RK-04 | 大图 OOM / 进程被杀 | 高 | 预览降采样 + `PreviewReplay` + Mat 复用 + 最长边限制 + 导出分块（§7.6） |
| RK-05 | 发丝级抠图质量不达标 | 中 | 混合精度量化、语义定位 + matting 串联、蒙版对齐、专用 matting 兜底 |
| RK-06 | 中低端机 / 无 NPU 机型性能不达标 | 中 | CPU/XNNPACK 降级、模型分级、按设备能力动态启用；不达标转 `RegionSelector` |
| RK-07 | 人像合规（单独同意 / 标识 / 备案）未落实 | 高 | 个人自用先做轻量告知/标识；分发前按 §2.4 + §10.2 + §10.4 补全 |
| RK-08 | 数据条款理解偏差 | 中 | §10.3 尽调清单 + 保留版本记录 |
| RK-09 | 模型/价格/接口变更 | 中 | `IntentGateway` 抽象层隔离、固定 `deepseek-flash` 别名、监控定价页与 changelog |
| RK-10 | **语义期待管理**：用户按生成式能力期待「换背景 / 换装」，实际只能替换素材与换色，落差转化为差评 | **高** | 功能命名改为「背景替换」「服饰配色 / 面料图案替换」；输入框给指令示例与能力说明；§5.5 #10 拦截越界意图并给出明确提示（§2.5、§7.4.5） |
| RK-11 | 抠图模型许可不适配分发 | 中 | 分发前换成 MIT / Apache 2.0 权重（§4） |
| RK-12 | 持续推理导致发热降频 | 低 | 长任务优先 CPU 后端、任务间让出、连续 10 张压测（NFR-10） |
| **RK-13** | **预设素材库覆盖度不足**，用户意图（如「赛博朋克夜景」）无对应枚举 ID，计划被 §5.5 #9 拒绝 | 中 | 素材库按场景/色调/风格三维建索引并给别名表；未命中时引导「从相册选一张」；把高频未命中词回流为下一版素材（§7.4.2、FR-19） |
| **RK-14** | **误以为「实现上不用生成式 AI」即可免除标识 / 单独同意义务** | **高** | C-11：规制落点是功能效果与是否致混淆，且云端仍有大模型参与；§2.4 全部合规项一条不减 |
| **RK-15** | **合成质量不稳定**：侧光冲突、色带、光晕、塑料板质感 | 中 | §7.5 七项对策；固定参数 + 典型光照组合做回归测试（NFR-13）；失败时提示换素材而非硬出图 |
| **RK-16** | 预设素材的第三方许可不适配随包分发 | 中 | 素材单独做许可核查，优先自制 / CC0 / 已授权图库（§4） |
| **RK-17** | **U3/U4 定位模型缺失或不达标**：没有多目标/天空 mask，「那个路人/那片天空」无法可靠规划 | **高** | 默认 `RegionSelector` 兜底；模型候选 M1 实测；不达标就明确要求用户点选/框选（§2.1 U9、§4） |
| **RK-18** | **全分辨率导出超时/OOM**：12MP `seamlessClone` 与多份 Mat 同时存在 | **高** | 先实测；不达标降级为预览融合上采样 + 分块导出；限制输入长边（§7.6、NFR-17） |
| **RK-19** | **标识实现踩坑**：中文 `putText` 乱码、EXIF/PNG 元数据丢失 | 高 | Canvas/Paint + ExifInterface + PNG chunk（§10.2、FR-24） |
| **RK-20** | **色彩管理缺失**：预览正常、导出偏色/方向错 | 中 | 统一 `ColorPipeline`，M1 做 EXIF/ICC/P3 回归（NFR-15） |
| **RK-21** | **模型分发/版本漂移**：模型未下载、升级后重放结果变化 | 中 | `ModelManifest` + SHA-256 + 回滚；个人自用随包/侧载，分发用 Play Asset Delivery（§4、FR-23） |
| **RK-22** | **个人自用导致合规松懈**：编辑他人照片、未来分发时返工 | 中 | 保留单独同意/标识/日志能力；M0 记录「自用」决定，分发前重走 §2.4 |

---

## 12. 测试与验收标准

### 12.1 功能验收

| 项 | 标准 |
|---|---|
| 意图覆盖 | **≥200 条分层样本**（8 类高频意图 × 多种措辞/噪声/否定表达），准确率 ≥95% 并报告置信区间；不接受 40 条样本的结论 |
| 计划合法性 | 100% 通过 `PlanValidator`；非法计划 100% 被拦截或修正，不进入执行器；#11/#12 区域/分辨率前提必须覆盖 |
| 抠图 | 人像发丝、婚纱、玻璃杯样本，人工评分 ≥4/5；蒙版与原图尺寸 100% 对齐；定位失败可转 `RegionSelector` |
| **U3 局部消除** | 仅小面积样本；人工评分 ≥3.5/5；大面积样本必须明确提示不支持，不得强行输出糊图 |
| **U4 区域调色** | 天空 mask 可靠时选区外像素变化 = 0；mask 不可靠时必须转用户选区，不得整图变蓝 |
| **背景替换** | 顺光 / 侧光 / 逆光各 5 组，**盲评** ≥4/5；无可见色带、光晕、接缝；接触阴影方向与原图光源一致；失败时提示换素材 |
| **服饰换色** | 纯色 / 格纹 / 印花各 5 组；褶皱与高光位置与换色前逐像素对应（V 通道差异 = 0）；mask 外像素变化 = 0 |
| **面料贴图** | 图案随褶皱弯折、无「浮在表面」感；mask 外像素变化 = 0；多色图案按「颜色映射」或「仅明暗纹理」两种策略分别验收 |
| **素材枚举** | 100% 拒绝非法素材 ID（含模型编造的文件名 / URL）；越界意图 100% 被 §5.5 #10 拦截 |
| **确定性复现** | 同设备、同模型版本、同后端、同参数连续 3 次输出一致；跨设备按 PSNR ≥35 dB / ΔE00 ≤3（NFR-13，阈值 M1 冻结） |
| 区域限定 | 「只调天空」类指令，选区外像素变化为 0 |
| 撤销 | 任意步可回滚，回滚后参数序列、模型版本与画面一致 |
| 导出 | 分辨率与原图一致；显式标识可见且中文正常；JPEG EXIF/XMP 与 PNG `tEXt/iTXt` 可解析 |
| 色彩管理 | 带 EXIF 方向、Display P3、ICC 的样张导出无偏色/方向错误 |
| 一致性 | 同一 `{tool, args, modelManifest}` 由 UI 按钮触发与由 Agent 触发，同设备输出逐像素一致 |

**v1.3 已执行**：

- **32 个单元测试 + 32 个设备测试全部通过**（模拟器）。
- 规则意图：15 类 × 25 变体 = **375 条样本**，通过 ≥95% 门槛；越界意图/非法素材/无选区 inpaint 均被拦截。
- P3/EXIF/12MP 导出、中文标识、JPEG EXIF / PNG `tEXt`、本地 JSONL 日志、连续 10 张导出均通过（`M5AcceptanceTest`）。
- DeepSeek 在线 smoke 成功；无 Key/无网络走规则/模板。
- 验收报告：`docs/m5-acceptance.md`；测试记录：`tools/benchmark/results-2026-11-03-emulator.md`。

**v1.3 未执行**：三档真机、48MP 输入、飞行模式手工回归、模型下载/回滚真实服务联调、婚纱/玻璃/服装真实样张盲评、SCHP QOperator/NCNN 真机 A/B。

### 12.2 非功能验收

按 §2.3 / §9 全部指标在**至少 3 档设备**（旗舰 / 中端 / 低端无 NPU）上实测；含弱网、飞行模式、连续 10 张、12MP 大图、48MP 输入降级/拒绝、后台切换、导出中断恢复。

补充必测项：
- EXIF 方向 + Display P3/ICC 样张的预览/导出一致性；
- 12MP 导出内存峰值与 `seamlessClone` 耗时，未达标时验证降级路径；
- 中文标识在 JPEG/PNG 中的可见性与可解析性；
- 模型未下载、下载失败、版本回滚时的 UI 与降级行为。

### 12.3 灰度观测指标

一次成片率、撤销/重做率、端到端 P99、峰值内存、连续编辑后的失败率、缓存命中率、单位成片成本、badcase 回流、**RegionSelector 触发率**、**导出降级率**、**模型加载失败率**。

---

## 13. 里程碑

> **v1.3 说明**：M1–M5 已完成，不再按周数排期；后续只按「三档真机、release 包体、分发合规」三类退出条件推进。

| 阶段 | 交付 | 关键产出 | 退出条件 |
|---|---|---|---|
| M0 立项 | 定位与边界确认 | 文档 v1.3 定稿；明确**个人自用**；§10.3 尽调记录；模型矩阵候选与包体/内存预算表 | 决策记录落盘；阻塞 M1 的未决项（模型分发方式、输入上限）有明确结论，其余记录到 §15 |
| **M1 编辑器地基（greenfield）** | 不含大模型的可用 P 图 APP | 补齐 NDK/CMake、OpenCV、Room、Retrofit/OkHttp、ExifInterface；`ToolRegistry` + 参数化工具 + 撤销栈 + `ColorPipeline` + 导出管线 + `AssetLibrary` 骨架 + 枚举 ID 规范 | UI 按钮可完成「去背/换底/调色/裁剪/导出」；12MP 输入按策略降采样/分块后预览无 OOM；EXIF/色彩/标识路径跑通 |
| M2 抠图与合成 | 端侧 matting + 确定性合成 | MODNet LiteRT/NCNN 集成；原图→软 alpha→换纯色底→导出；`PreviewReplay` 打通；§7.4.2 背景替换全链路 | 预览与导出均可用；全分辨率 `seamlessClone` 实测并落地降级路径；合成回归集通过 |
| **M2.5 服饰** | 换色 / 贴图可用 | SCHP-ATR INT8 集成 + `recolor_hsv` / `apply_texture`；§7.5 七项坑回归用例 | 换色 V 通道差异 = 0、mask 外变化 = 0；贴图无「浮在表面」感 |
| M3 智能化 | 一句话驱动 | `CanvasAnalyzer`（objects/sky）+ `RegionSelector` + `IntentGateway` + `PlanValidator`（#1–#12）+ 计划卡 + 分步预览；先开 8–10 个高频意图 | 模型矩阵冻结；≥200 条意图样本 ≥95%；U3/U4 兜底可走通 |
| M4 护栏与降级 | 稳定可用 | §5.5 十二项校验、模板工作流、离线降级、可计算自检、前后对比滑杆、能力边界文案、`ModelManager` | 断网 P0 可用；幻觉/越界/非法素材 100% 拦截；模型失败有明确 UI |
| M5 自用验收 | 个人自用可交付 | 显式+隐式标识、本地日志、3 档设备压测、色彩/EXIF/12MP 导出测试；分发清单（可选） | §2.3/§9/§12 全部达标；未达标项有记录与降级方案 |

**M1 是关键路径**：当前没有「现有编辑器」，必须先把 `ToolRegistry`、参数化工具、撤销栈、导出与色彩管线做稳，让 UI 按钮和 Agent 共用同一套代码路径；M1 不稳就不要启动 M3，否则会把地基问题带进智能化阶段。

**v1.3 状态**：M0–M5 均已完成；M5 为**模拟器自用验收**。三档真机压测、release arm64-only 包体、模型下载/回滚服务联调、对外分发合规仍未完成。

---

## 14. 附录 A：参考资料

> 说明：R1–R5、R7–R15、R17–R22、R24–R36、R38–R42 为可直接访问的原文链接；R6 / R16 / R23 / R37 来自模型卡与社区文章，此处给出**可复现的检索入口**（原文链接可能随平台改版失效），正文引用的数值以对应条目所述来源为准，落地前建议复核原始模型卡 / 文章。
> **R30–R42 为 v1.1 新增**，对应 §7.4 / §7.5 的非生成式合成管线、人像解析模型与合规边界。
> **R43–R50 为 v1.2 新增**，优先使用 Android/Google/W3C/OpenCV 官方文档，覆盖 EXIF、色彩、中文标识、PNG chunk、模型分发与分割候选。**R1–R5 的模型能力/价格、R6/R15 的延迟、R33/R34 的模型指标仍为二手来源，排期与验收前必须在目标机实测并核对官方模型卡。**

| 编号 | 来源 | 链接 |
|---|---|---|
| R1 | DeepSeek 官方发布公告（V4.1 Flash，2026-09-10，552B MoE / 原生多模态） | https://www.deepseek.com/en/news/deepseek-v4-1-flash/ |
| R2 | DeepSeek V4.1 Flash 1017 本地部署资源测算（510.3 GB / Q4_K_M ~571.6 GB） | https://canitrun.dev/models/deepseek-v4.1-flash-1017 |
| R3 | DeepSeek API 模型与价格（功能表：图像理解 ✓ / 图像生成无） | https://api-docs.deepseek.com/zh-cn/quick_start/pricing |
| R4 | DeepSeek 能生成图片吗 —— 输入文本+图像、输出仅文本 | https://bigolab.com/guides/3093-deepseek-generate-image |
| R5 | DeepSeek 图像理解文档（图片 token 上限 1024、体积限制） | https://api-docs.deepseek.com/zh-cn/guides/vision/ |
| R6 | MODNet — LiteRT 模型卡（26 MB / Apache 2.0 / Pixel 8a GPU ~79 ms） | 检索入口：`https://huggingface.co/models?search=MODNet%20LiteRT`（关键词 `MODNet LiteRT trimap-free portrait matting`） |
| R7 | OpenCV4Android 使用方式（4.9.0 起 Maven Central AAR / Apache 2.0） | https://opencv.org/?p=2318/ |
| R8 | 《互联网信息服务深度合成管理规定》（2023-01-10 施行，第 13/15/16/17/18 条） | https://www.cac.gov.cn/2022-12/11/c_1672221949354811.htm |
| R9 | 广州市工信局：AI 换脸/拟声的双重通行证（单独同意 + 全链条标识） | https://gxj.gz.gov.cn/zt/fzzz/content/post_10982069.html |
| R10 | 四部门联合发布《人工智能生成合成内容标识办法》（2025-09-01 施行） | https://www.cac.gov.cn/2025-03/14/c_1743654685899683.htm |
| R11 | 《人工智能生成合成内容标识办法》全文（中国政府网） | https://www.gov.cn/zhengce/zhengceku/202503/content_7014286.htm |
| R12 | 央视网：显式标识 / 隐式标识元数据要素解读 | https://news.cctv.com/2025/03/15/ARTI3wMX1ohsE7LsLT4qBVrv250315.shtml |
| R13 | DeepSeek API 模型 & 价格（中文，¥/百万 tokens、峰谷时段、并发 2500） | https://api-docs.deepseek.com/zh-cn/quick_start/pricing |
| R14 | OpenCV for Android 集成实战（Gradle 依赖、initLocal、静态链接；minSdk 21 为上游下限，本项目统一 24） | https://blog.gitcode.com/78e71c997ecbfbc11ea1b78b51c5a6fa.html |
| R15 | MODNet 量化部署实战（NCNN INT8 21 MB/32 ms，混合精度 35 MB/45 ms，发丝 PSNR） | http://www.bjdmk.com.cn/news/1927645/ |
| R16 | 2026 年背景移除模型横评（segmentation ≠ matting；BiRefNet/BEN v2 MIT、Bria 权重非商用） | 检索关键词：`AI Background Removal Models SAM 3.1 Bria BEN v2 BiRefNet Compared 2026` |
| R17 | 全栈 AI 修图 Agent 实战：让模型做选择题，参数合法性校验在工具侧 | https://blog.csdn.net/weixin_29061425/article/details/166188814 |
| R18 | DeepSeek JSON Output 官方文档（三要件 + max_tokens 防截断） | https://api-docs.deepseek.com/guides/json_mode/ |
| R19 | DeepSeek 工程实践：即使 JSON 模式仍需应用侧 schema 校验 | https://cloud.tencent.com/developer/article/2761881 |
| R20 | DeepSeek API 上下文缓存（稳定前缀在前、易变在后、usage 命中率） | https://deepseek.day/zh-tw/blog/deepseek-api-context-caching |
| R21 | 生产 Agent 缓存命中率实测（deepseek-flash 98.70%） | https://www.corpusiq.io/docs/hermes/best-practices/prompt-caching |
| R22 | 全栈 AI 修图 Agent：从意图理解到工具调用的常见故障速查表 | https://blog.csdn.net/weixin_28678151/article/details/166085421 |
| R23 | 《ComfyUI 语义抠像与合成实战》（蒙版与原图尺寸对齐、按材质选模型） | 检索关键词：`ComfyUI 语义抠像与合成实战 RMBG 3.3 尺寸对齐` |
| R24 | OpenCV Android Best Practices（预分配复用 Mat、减少 JNI 调用） | https://opencv.org?p=2312/ |
| R25 | OpenCV Android 性能优化实测（getPixels 8.7 ms vs NDK 2.1 ms；RenderScript 废弃） | https://devpress.csdn.net/avi/6988d0500a2f6a37c590b7e4.html |
| R26 | MediaPipe / LiteRT 端侧实践（GPU delegate 后台限制与持续负载降频，CPU/XNNPACK 降级） | https://dev.to/software_mvp-factory/wiring-androids-mediapipe-llm-inference-api-to-a-quantized-vision-language-model-for-real-time-45bc |
| R27 | DeepSeek V4.1 Flash 定价详解（命中/未命中价差 50 倍，缓存省 75% 账单） | https://apifox.com/apiskills/deepseek-v4-1-flash-pricing-cn |
| R28 | DeepSeek 隐私政策（训练优化用途、退出开关、敏感信息提示） | https://platform.deepseek.com/downloads/DeepSeek%20Privacy%20Policy.html |
| R29 | DeepSeek API 隐私与数据最小化尽调要点（Files API 默认永久存储、store:false 语义、缓存不等于删除） | https://deepseekagi.org/deepseek-api-privacy-data-minimization |
| **R30** | **《互联网信息服务深度合成管理规定》第 17 条**（含第（五）项「其他具有生成或者显著改变信息内容功能的服务」兜底项） | https://www.cac.gov.cn/2022-12/11/c_1672221949354811.htm |
| **R31** | **《人工智能生成合成内容标识责任的边界与合规要点》**（诺恒律师事务所：责任主体认定、显隐双标识机制；调用 API 提供 AIGC 服务仍被认定为服务提供者，参考 (2024)粤0192民初113号） | https://nuohenglaw.com/index.php/nuohengzixun/nuohengguandian/259.html |
| **R32** | **《人工智能生成合成内容标识办法》全文**（中国政府网，第 3 条定义、第 4 条显式标识、第 6 条传播核验） | https://www.gov.cn/zhengce/zhengceku/202503/content_7014286.htm |
| **R33** | **SCHP — Self-Correction Human Parsing (LIP, 20 classes) 模型卡**（MIT；INT8 ONNX 约 65 MB、99.09% 像素一致；`upper_clothes` / `dress` / `skirt` 等固定词表） | https://huggingface.co/pirocheto/schp-lip-20 |
| **R34** | **Cozy Human Parser ATR 节点说明**（SCHP 固定词表分类器、可 CPU 跑、确定性；ATR 在 fashion 场景 mIoU 82.29%） | http://comfy.icu/node/Cozy-Human-Parser-ATR |
| **R35** | **OpenCV 官方文档：无缝克隆**（`seamlessClone` / `colorChange` / `illuminationChange` 与三种 flag） | https://docs.opencv.ac.cn/4.10.0/df/da0/group__photo__clone.html |
| **R36** | **图像和谐化：Reinhard LAB 均值-方差色彩迁移与泊松融合**（含 OpenCV 示例代码） | https://www.ipipp.com/html/0826/45464.html |
| **R37** | **OWLv2 定位 + GrabCut 精修 + HSV 换色实践**（全程经典图像处理、不引入生成式模型、CPU 几秒出结果） | 检索关键词：`OWLv2 GrabCut HSV 换色 零新增依赖 非生成式` |
| **R38** | **泊松融合与颜色校正实战详解**（几何对齐→颜色校正→泊松融合→细节修整四步法；LAB float32、标准差比值裁剪 0.5–2、侧光/顺光梯度冲突、mask 光晕） | https://www.bbccb.com/html/20260914/47526.html |
| **R39** | **证件照换衣服颜色实战**（mask 形态学开闭去噪、高斯羽化消白边、只替换 H 保留 S/V） | http://www.mzlw.cn/news/12531 |
| **R40** | **Cloth Texture Replace (Masked) 节点说明**（纹理 ÷ 强模糊去光照、无缝平铺 15% 重叠、亮度 Sobel 位移弯折、保留 H/S 替换 V、mask 外逐像素不变） | https://comfy.icu/node/ClothTextureReplace |
| **R41** | **Building a color replacer: why RGB matching fails and how HSV fixes it**（H/S/V 三个独立容差、只换 H 保留 S/V 以继承阴影与质感） | https://dev.to/irrationalapps/building-a-color-replacer-why-rgb-matching-fails-on-photos-and-how-hsv-fixes-it-1n8 |
| **R42** | **PS 服装替换完整流程**（换装三大难点：版型透视、面料记忆、光影匹配；手工变形网格与补褶皱做法） | https://www.zcool.com.cn/article/ZMTgyNTU5Mg==.html |
| **R43** | **AndroidX ExifInterface**（读写 JPEG EXIF/XMP；方向与元数据） | https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface |
| **R44** | **Play Asset Delivery**（分发时按需下发模型/素材，避免首次静默下载大包） | https://developer.android.com/guide/playcore/asset-delivery |
| **R45** | **ML Kit Subject Segmentation**（端侧显著性分割候选；离线取决于 bundled） | https://developers.google.com/ml-kit/vision/subject-segmentation/android |
| **R46** | **Android `ColorSpace`**（ICC/P3 与色彩空间转换） | https://developer.android.com/reference/android/graphics/ColorSpace |
| **R47** | **Android `Canvas.drawText`**（中文标识渲染；替代 OpenCV `putText`） | https://developer.android.com/reference/android/graphics/Canvas#drawText(java.lang.String,%20float,%20float,%20android.graphics.Paint) |
| **R48** | **TensorFlow Lite Segmentation 概览**（DeepLabV3+/MobileNet 等候选） | https://www.tensorflow.org/lite/models/segmentation/overview |
| **R49** | **PNG 规范（`tEXt` / `iTXt` chunk）** | https://www.w3.org/TR/png/ |
| **R50** | **OpenCV `photo` 模块（seamlessClone / colorChange / illuminationChange）** | https://docs.opencv.org/4.x/df/da0/group__photo__clone.html |

---

## 15. 附录 B：待确认事项（阻塞 M1 的项已在 M0 标注）

| # | 事项 | 状态 / 影响 |
|---|---|---|
| 1 | 仅个人自用，还是未来分发？ | **已定（v1.2）**：本期个人自用，不做代理；未来分发再按 §10.1 切代理并重走 §2.4 |
| 2 | 抠图模型最终选 LiteRT 还是 NCNN？ | **已定（v1.3）**：ONNX Runtime 1.20；MODNet XNNPACK、SCHP QDQ CPU；真机性能复核待做 |
| 3 | 是否需要「点选交互抠图」作为 matting 失败时的兜底？ | **已定**：需要；`RegionSelector` 为 P0，U3/U4 默认兜底 |
| 4 | 标识中的「服务提供者名称/编码」如何取值 | 个人自用先占位；分发前按实际备案/注册主体补全，不影响 M1 |
| 5 | 是否预留 FR-15 缩略图视觉自检开关？ | 个人自用保持默认关闭；如未来分发再纳入单独同意流程 |
| **6** | **预设素材库的初始规模与分类维度**（场景 / 色调 / 风格） | 未决；直接决定 RK-13 的意图落空率；M1 先定别名表结构，M2 随管线填充 |
| **7** | **产品功能命名是否采用「背景替换」「服饰配色 / 面料图案替换」** | **已定**：采用；M4 落到输入框示例与能力说明文案 |
| **8** | **是否允许「用户本地相册选图当背景 / 当纹理」** | **已定**：默认允许；需处理相册权限与素材尺寸上限，素材字节不出端 |
| **9** | **服装定位选 SCHP-ATR INT8 还是 OWLv2 + GrabCut** | **已定（v1.2）**：SCHP-ATR INT8 为默认，`RegionSelector` 兜底；OWLv2 仅旗舰实验 |
| **10** | **主体/多目标/天空模型最终选型** | **已冻结（v1.3）**：启发式 `CanvasAnalyzer` + `RegionSelector`；不引入专用检测/天空模型 |
| **11** | **模型分发方式**：随包、侧载还是按需下载 | **已定（v1.3）**：随包 assets + `ModelManager`（SHA-256/下载/回滚接口）；真实下载服务联调待做 |
| **12** | **最大输入长边与 12MP 导出降级阈值** | **已定（v1.3）**：默认输入 ≤6000px；`seamlessClone` 2MP 阈值；12MP 导出已测试 |
| **13** | **三档真机与 release 包体** | 未完成；需旗舰/中端/低端实测、arm64-only release、按需模型/素材 |

---

## 16. 实现证据索引（v1.3）

| 证据 | 位置 |
|---|---|
| M0 决策、模型矩阵、密钥策略 | `docs/adr/0001-m0-decisions.md` |
| M5 自用验收报告、未达标项/降级方案 | `docs/m5-acceptance.md` |
| 测试与基准记录（32 单测 + 32 设备） | `tools/benchmark/results-2026-11-03-emulator.md` |
| MODNet/SCHP 导出、QOperator A/B、模型指标 | `tools/model-ab/results-2026-11-03.md` |
| 构建、`.env`、NDK/CMake | `docs/build-setup.md` |
| 契约冻结（CanvasDigest v2/ToolSpec/PlanValidator/意图） | `docs/contracts/m1-contracts.md` |
| 测试图与 ICC/人像样张 | `tools/test-images/`、`images/` |

> 说明：上述记录是工程验收证据，不替代三档真机与对外分发合规材料。

---

> **一句话总结（v1.3）**：把 V4.1 Flash 定位成「只在有限枚举里做选择题的云端大脑」，把 OpenCV 确定性合成 + MODNet / SCHP-ATR / `RegionSelector` 定位成「永不联网、不凭空造像素的本地双手」；生成式重绘仍然划掉，「换背景 / 换装」的一句话交互保留，但语义诚实降级为「替换」。M1–M5 已在模拟器完成个人自用验收（32 单测 + 32 设备测试），剩余风险集中在三档真机性能、release 包体、模型分发联调与对外分发合规。
