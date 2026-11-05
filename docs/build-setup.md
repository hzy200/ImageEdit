# 构建环境与依赖说明

## 已确认环境

- JDK：17
- Android SDK：`C:\Users\hong\AppData\Local\Android\Sdk`
- `compileSdk` / `targetSdk`：37
- `minSdk`：24
- Gradle：9.6.0（wrapper）
- Android Studio：`D:\Android Studio`

## 已加入的 Gradle 依赖

- OpenCV 4.9.0：`org.opencv:opencv:4.9.0`
- AndroidX ExifInterface 1.3.7
- Retrofit 2.11.0 + Gson converter
- OkHttp 4.12.0 + logging interceptor
- Room runtime/ktx 2.6.1（暂未启用 KSP，数据库在后续里程碑加入）
- Lifecycle ViewModel Compose 2.8.7
- Kotlin Coroutines Android 1.9.0

## DeepSeek API Key

根目录 `.env`（已加入 `.gitignore`，不要提交）：

```dotenv
DEEPSEEK_API_KEY=sk-...
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-flash
```

`app/build.gradle.kts` 在构建时读取并注入 `BuildConfig`。`ApiKeyStore` 优先使用应用内覆盖值，其次使用构建时 Key；UI 顶部 `AI Key` 可覆盖/清除。

> 这是个人自用的便利方案：Key 会进入 APK，反编译可取出。分发前必须改为 Keystore/服务端代理，并撤销已暴露的 Key。

## 权限

`AndroidManifest.xml` 已声明：

- `INTERNET`
- `READ_MEDIA_IMAGES`
- `READ_EXTERNAL_STORAGE`（maxSdk 32）
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`

首个垂直切片使用 Photo Picker 与 `ACTION_CREATE_DOCUMENT`，不依赖存储写权限。

## NDK / CMake

已安装并验证：

- NDK `27.0.12077973`
- CMake `3.22.1`

```powershell
& "D:\Android\android-sdk\cmdline-tools\12.0\bin\sdkmanager.bat" `
  --sdk_root="C:\Users\hong\AppData\Local\Android\Sdk" `
  "ndk;27.0.12077973" "cmake;3.22.1"
```

启用 native 构建：

```powershell
.\gradlew assembleDebug -PnativeBuild=true
```

`app/build.gradle.kts` 中 `-PnativeBuild=true` 才会启用 `ndkVersion` 与 `externalNativeBuild`；默认构建不要求 NDK。已验证 `assembleDebug -PnativeBuild=true` 成功。

## ONNX Runtime 与模型

已加入 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`，模型位于 `app/src/main/assets/models/`：

- `modnet_photographic_portrait_matting.onnx`（约 25 MB，XNNPACK）
- `schp_atr_int8.onnx`（约 79 MB，INT8 QDQ；XNNPACK 不兼容时自动回退 CPU EP）
- `schp_atr_labels.json`

SCHP 导出脚本：`tools/model-ab/export_schp_onnx.py`；原始 PyTorch 权重放在 `models/`，不要打包进 APK。

## 构建与测试

本机验证时使用 Android Studio 自带 JBR（JDK 25）：

```powershell
$env:JAVA_HOME = "D:\Android Studio\jbr"
.\gradlew :app:assembleDebug
.\gradlew :app:testDebugUnitTest
```

如果 Gradle 下载超时，且系统使用本地代理，可给 JVM 加代理参数（端口以实际代理为准）：

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<port> -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<port>"
```

如首次构建需要下载依赖，保持网络可用；OpenCV AAR 体积较大，首次同步会较慢。

设备基准：

```powershell
.\gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.PipelineBenchmarkTest
.\gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.ExportPipelineTest
```

## 说明

- 首个垂直切片的基础工具使用 Android `Bitmap`/`Canvas` 实现；OpenCV 依赖已加入，M2 的 matting/融合管线再启用。
- 测试图生成脚本见 `tools/test-images/generate_test_images.py`。
- 设备基准说明见 `tools/benchmark/README.md`。
