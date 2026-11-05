# 设备基准测试

## 目的

在目标设备上测量 M1 垂直切片的关键路径：

- 12MP 图片预览解码（最长边 2048）；
- 调色、裁剪、旋转、翻转、纯色底占位；
- 12MP 全分辨率重放；
- 显式标识渲染。

输出在 logcat 的 `PipelineBenchmark` tag 中。

## 运行

1. 连接设备或启动模拟器：

```powershell
adb devices
```

2. 执行：

```bash
bash tools/benchmark/run_benchmark.sh
```

或在 Windows：

```powershell
.\gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.PipelineBenchmarkTest
adb logcat -d -s PipelineBenchmark:I
```

3. 记录设备型号、系统版本、结果与内存峰值，填入 `docs/adr/0001-m0-decisions.md` 的测试设备表。

## 当前测试图

`app/src/androidTest/assets/benchmark/test_12mp.jpg`（4032×3024，来自 `tools/test-images/out/15_12mp_4032x3024.jpg`）。

## 未完成项

- MODNet / SCHP 模型延迟 A/B：需要先完成模型集成，见 `tools/model-ab/README.md`。
- 真实 P3/ICC 与复杂人像样张：需要用户补充，见 `tools/test-images/README.md`。
