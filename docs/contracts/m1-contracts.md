# M1 契约冻结

版本：v1.3 / 2026-11-03

## 1. CanvasDigest v2

字段定义与实现见 `app/src/main/java/com/example/imageedit/core/contracts/CanvasDigest.kt`。

```json
{
  "schema": "canvas_digest/v2",
  "width": 4032,
  "height": 3024,
  "preview_long_edge": 2048,
  "subject": { "type": "portrait", "bbox": [0.18, 0.09, 0.79, 0.94], "ratio": 0.42 },
  "objects": [
    { "id": "o1", "type": "person", "bbox": [0.18, 0.09, 0.79, 0.94], "confidence": 0.96 }
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
  "available_tools": ["remove_background", "cutout_subject", "replace_background", "adjust_color", "crop", "rotate", "flip", "parse_garment", "refine_mask", "recolor_hsv", "apply_texture"],
  "garment_detected": [],
  "available_assets": { "background": ["preset:beach_03", "user_local"] },
  "note": "仅结构化文本 + 枚举 ID，不含图像字节"
}
```

规则：

- 只传文本/坐标/枚举 ID，不传 Bitmap 或 Base64。
- `objects[].confidence < 0.6` 时不得直接规划区域操作，转 `RegionSelector`。
- `region_request` 记录用户点选/框选/笔刷的结构化区域。
- `local_asset_digest` 只在用户选择本地背景/纹理后生成；实现见 `core/image/LocalAssetAnalyzer.kt`（宽高、地平线、亮度、主色、构图）。

## 2. ToolSpec

实现见 `core/tools/ToolContracts.kt` 与 `core/tools/ToolRegistry.kt`。

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val specVersion: String = "1",
    val params: Map<String, ParamSpec> = emptyMap(),
    val required: List<String> = emptyList(),
    val requiresRegion: Boolean = false,
    val maxInputPx: Int? = null,
)
```

M1 已注册工具：

| 工具 | 参数 | 备注 |
|---|---|---|
| `adjust_color` | brightness -100..100、contrast -100..100、saturation 0..2 | 纯 Android ColorMatrix |
| `crop` | left/top/right/bottom 0..1 | 归一化矩形 |
| `rotate` | degrees ∈ {90,180,270} | |
| `flip` | direction ∈ {horizontal,vertical} | |
| `remove_background` | model=modnet、edge_feather 0..8、input_size 256..768 | MODNet ONNX + ONNX Runtime |
| `cutout_subject` | model=modnet、feather_px 0..8、input_size 256..768 | 同 remove_background |
| `replace_background` | source、color、background_uri、fit、color_transfer、contact_shadow、bokeh、edge_fusion、padding | 纯色/渐变/预设/本地；alpha 合成 + Reinhard LAB + 接触阴影；≤2MP 可走 seamlessClone，超过降级 alpha |
| `parse_garment` | model=schp_atr、classes | SCHP-ATR INT8；输出 `ImageContext.masks["garment"]` |
| `refine_mask` | morphology、blur | 形态学开闭 + 羽化 |
| `recolor_hsv` | hue 0..360、sat_scale、preserve_v 受保护 | 只改 H，V 保持 |
| `apply_texture` | texture 枚举、warp_from、strength | 纹理亮度替换 V，保留 H/S |

规则：

- 越界数值截断到边界；非法枚举回落默认值；未知字段拒绝。
- 模型只做选择题，不做像素计算。
- UI 按钮与 Agent 必须走同一 `ToolRegistry`。

## 3. EditPlan

```json
{
  "schema": "edit_plan/v1",
  "understood": "提亮一点",
  "steps": [
    { "id": "s1", "tool": "adjust_color", "args": { "brightness": 12 }, "depends_on": [] }
  ]
}
```

- 云端只返回 `schema`、`understood`、`steps`。
- `estimated_ms` 由端侧按 `costMs` 计算。
- `confirm_required` 由端侧按步数/风险策略决定。
- 步数 ≤ 8；依赖必须存在且无环。

## 4. PlanValidator 12 项

1. 顶层 schema/version；
2. 工具存在性；
3. 参数类型/必需字段/枚举白名单；
4. 参数限幅；
5. `depends_on` 引用存在、无环；
6. 步数 ≤ 8；
7. 至少一个可执行步骤（执行器再校验实际调用）；
8. 单会话迭代上限（执行器实现）；
9. 素材 ID 枚举校验；
10. 语义越界拦截；
11. `requiresRegion` 工具的区域前提；
12. 输入分辨率/内存前提。

实现：`core/contracts/PlanValidator.kt`。

## 5. 8 个高频意图

| # | 意图 | 示例 | 工具链 | 需要/兜底 |
|---|---|---|---|---|
| 1 | 去背换纯色底 | 把背景去掉换成白色 | remove_background → replace_background | M2 抠图；M1 纯色底占位 |
| 2 | 证件照 | 抠图换白底切成一寸 | remove_background → replace_background → crop | 证件照尺寸参数后续补 |
| 3 | 整体调色 | 整体提亮一点、调暖 | adjust_color | M1 已可用 |
| 4 | 局部调色 | 天空调蓝，别动花 | region/sky mask → adjust_color | 天空模型或 `RegionSelector` |
| 5 | 小面积局部消除 | 把右下角小物体去掉 | region → inpaint | 仅小面积；大面积拒绝 |
| 6 | 裁剪/旋转/翻转 | 裁成 1:1 / 旋转 90° | crop/rotate/flip | M1 已可用 |
| 7 | 背景替换 | 换成海滩 / 用相册那张 | cutout → replace_background → harmonize | 素材枚举或 `LocalAssetDigest` |
| 8 | 服饰换色/面料 | 裙子换成红色 / 换成格纹 | parse_garment → refine_mask → recolor_hsv/apply_texture | SCHP + `RegionSelector` 兜底 |

当前状态：#1、#2（证件照中心裁剪）、#3、#4（选区局部调色）、#5（选区局部消除）、#6、#7、#8 均已接入。M3 使用规则意图网关 + DeepSeek 客户端；规则样本 15 类 × 25 变体 = 375 条，通过 ≥95% 门槛。U3/U4 在无可靠对象/天空 mask 时强制使用 `RegionSelector`。

## 6. v1.3 契约修正

- `CanvasDigest.objects` 只作规划提示，**不能替代执行用 selection mask**；区域工具必须携带 `regionRequest`/`selection`。
- `replace_background(source=user_local)` 必须提供非空 `background_uri`。
- 模型/服装/背景/换色/贴图工具带 `maxInputPx=6000`；预览校验 + 全分辨率执行前检查。
- `recolor_hsv.preserve_v` 为受保护字段；`false` 被 PlanValidator 拒绝。
- 计划执行可取消：`PlanExecutor` 每步 `ensureActive()`，ViewModel `planJob` 取消后最多延迟一个原子步。
- 工具 JSON Schema 已补全参数、类型、范围、枚举、默认值与 required。
