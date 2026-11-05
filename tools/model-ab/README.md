# 模型 A/B 测试计划（MODNet / SCHP）

## 状态

- MODNet ONNX 已接入（ONNX Runtime + XNNPACK），模拟器 A/B 已完成。
- SCHP-ATR 已从 PyTorch 导出为 ONNX 并 INT8 量化；XNNPACK 不支持 QDQ，已回退 CPU EP。模拟器功能验证通过。
- 三档真机性能验收仍未执行。
- 实测数据见 `results-2026-11-03.md`。

## 目标

在三档设备上比较：

1. MODNet：NCNN INT8、NCNN 混合精度、LiteRT/TFLite；
2. SCHP-ATR：ONNX INT8（ONNX Runtime Mobile）、转换后的 NCNN；
3. 运行时组合：NCNN 单运行时 vs LiteRT + ONNX Runtime 双运行时。

## 记录字段

| 字段 | 说明 |
|---|---|
| model | 模型名、版本、来源 |
| format | NCNN / TFLite / ONNX |
| quantization | FP32 / FP16 / INT8 / mixed |
| size_mb | 模型文件体积 |
| input | 输入尺寸（如 512²、473²） |
| latency_p50_ms | 预热后 50 次中位数 |
| latency_p95_ms | 95 分位 |
| peak_mem_mb | 进程内存峰值 |
| license | 许可（必须可个人使用；分发前复核） |
| notes | 转换问题、发丝/边缘质量、CPU/GPU 后端 |

## 测试流程

1. 将模型放入 `app/src/main/assets/models/` 或侧载目录，记录 `ModelManifest` 与 SHA-256。
2. 在 `PipelineBenchmarkTest` 中新增模型推理用例，预热 5 次，测量 50 次。
3. 三档设备各跑一次，记录 logcat 与内存。
4. 质量检查：人像发丝、逆光、婚纱/玻璃；服装解析检查 mask 边缘。
5. 许可检查：MODNet Apache 2.0；SCHP 需复核原始模型卡；商用权重（如 BRIA）分发前替换。

## 决策规则

- 若 NCNN 能同时稳定运行 MODNet + SCHP，优先单运行时，减少包体与集成复杂度。
- 若 SCHP ONNX 转 NCNN 失败，允许 LiteRT（MODNet）+ ONNX Runtime（SCHP）双运行时，但需评估包体。
- 任一模型中端机延迟超过预算，优先降采样/换轻量模型；不达标则 U3/U4 使用 `RegionSelector`，U8 延后。
- 所有结论写入 `docs/adr/0001-m0-decisions.md` 与 `ModelManifest`。

## 当前禁止事项

- 不默认首次启动静默下载大模型；
- 不把未验证的二手延迟数据写进验收结论；
- 不使用许可不明的权重。
