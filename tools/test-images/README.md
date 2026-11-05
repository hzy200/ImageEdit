# 测试图集

## 已生成

运行：

```powershell
conda run -n agent python tools/test-images/generate_test_images.py
```

输出目录：`tools/test-images/out/`，共 27 张合成图，覆盖：

- 多种尺寸：320×240 到 6000×4000（24MP）；
- 12MP 横/竖图；
- 纯色条、棋盘格、噪声、纹理、文字、透明 PNG；
- 两张带 EXIF Orientation 6/8 的 JPEG；
- 宽幅、竖幅、奇数尺寸。

`manifest.json` 记录每张图的尺寸、图案、EXIF 方向与体积。

## M2 补充样张

运行：

```powershell
conda run -n agent python tools/test-images/generate_m2_samples.py
```

新增：

| 文件 | 用途 |
|---|---|
| `28_srgb_icc_1600x1200.jpg` | 嵌入 sRGB ICC |
| `29_displayp3_1600x1200.jpg` | 嵌入 Display P3 ICC |
| `30_complex_portrait_1600x2000.png` | 发丝/复杂人像的 matting smoke test |
| `31_glass_1200x900.png` | 半透明玻璃/alpha 合成 |
| `32_garment_plaid_1200x1600.png` | 格纹服装换色/贴图 |
| `33_garment_floral_1200x1600.png` | 印花服装换色/贴图 |

ICC 文件来自 Compact-ICC-Profiles（CC0），放在 `tools/test-images/profiles/`。

## 仍需补充的真实照片

合成图不能替代真实画质验收。进入 M2 验收前仍需补充以下真实样张（建议各 2–3 张）：

1. 人像：发丝、逆光、侧光、复杂背景；
2. 婚纱/玻璃杯等真实半透明材质；
3. 真实格纹/印花服装；
4. 真实手机拍摄的 Display P3/ICC 照片；
5. 真实 EXIF 方向照片；
6. 高像素原图（12MP、24MP、48MP）；
7. 含多个显著目标的照片（U3/U4 定位兜底）。

合成图只能验证链路与回归，不能作为画质评分依据。真实照片仅放在本地测试目录，不提交到任何云端。
