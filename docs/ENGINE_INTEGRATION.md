# 引擎集成说明（ENGINE_INTEGRATION）

> 本文说明四个端侧引擎的**依赖获取、编译开关、模型导出格式**。架构契约见 `DESIGN.md`，模型目录见 `MODEL_PATHS.md`。

## 总览

| 引擎 | 模块 | 依赖 | 打包形态 | 编译开关 |
|------|------|------|----------|----------|
| LiteRT-LM | `:engine:litert` | Maven `litertlm-android:0.11.0` | AAR 内 so | 无 |
| MNN | `:engine:mnn` | 预编译 `libMNN.so`（`droid.mnnRoot` / `MNN_ROOT`） | jniLibs 拷贝 | 路径不存在则跳过拷贝 |
| Genie | `:engine:genie` | QAIRT **2.50.0.260828**（`QAIRT_PATH`） | jniLibs 拷贝 | `-Pdroid.skipGenie=true` |
| llama.cpp | `:engine:llamacpp` | vendored `third_party/llama.cpp` | 静态编入 JNI | 无 |
| Fake | `:core:engine-api` | 无 | 纯 Kotlin | 始终可用（UI 勾选后才显示） |

**工具链硬约束**：Kotlin **2.2.21** + KSP **2.3.6**（litertlm 0.11.0 的 Kotlin metadata 为 2.3.0）。不要降级。

---

## 1. LiteRT-LM（Google）

- **依赖**：`com.google.ai.edge.litertlm:litertlm-android:0.11.0`（`gradle/libs.versions.toml` → `litertlm`）
- **编译**：纯 Kotlin 适配器，无 CMake。AAR 自带 `liblitertlm_jni.so` / `libLiteRt.so` / `libLiteRtClGlAccelerator.so`
- **Kotlin 约束**：编译器需 ≥ 2.2.x，否则 `metadata version 2.3.0` 读失败
- **模型导出**：
  - 官方/社区发布的 `.litertlm` 单文件；或 `.task`（MediaPipe GenAI 包）
  - 不要求与其它引擎同基座；用户自备文件即可
- **已知偏差**：`threads` / `seed` 不生效（LiteRT 自管线程池；适配器记 warning）。`OPENCL` backend 不支持
- **Backend**：CPU / GPU / NPU_HTP / AUTO

## 2. MNN（阿里）

- **依赖**：预编译 `libMNN.so`（须含 LLM 组件）。默认查找：
  1. Gradle `-Pdroid.mnnRoot=...`
  2. env `MNN_ROOT`
  3. 默认 `D:/3rd-party-projects/MNN` → `project/android/build_64/lib/libMNN.so`
- **编译**：`engine/mnn/src/main/cpp/` 编 `libmnn_chat_jni.so`；Gradle `copyMnnJniLibs` 把 `libMNN.so` 打进 `jniLibs`
- **模型导出**（目录型）：
  ```
  qwen1.5b-mnn/
  ├── config.json
  ├── llm.mnn          # 或 llm.mnn.part0 / .part1 分片
  └── ...
  ```
  转换工具见 MNN 官方 `transformers/` / `llmexport`。
- **已知偏差**：`seed` 不生效（记 warning）。`threads` → `thread_num` 生效
- **Backend**：CPU / GPU(→OpenCL) / OPENCL / NPU_HTP(QnnModule 可选) / AUTO

## 3. Genie / QNN（Qualcomm）

- **依赖**：QAIRT SDK **2.50.0.260828**（与 local-dream 一致）。路径解析顺序：
  1. Gradle `-Pdroid.qairtSdkRoot=...`
  2. env `QAIRT_PATH`
  3. env `QAIRT_SDK_ROOT`
  4. 默认 `D:/dev/qairt/2.50.0.260828`
- **编译开关**：
  - `-Pdroid.skipGenie=true` 或 `-PskipGenie=true`：跳过 so 打包；Kotlin 仍编译，`probe()` → `MissingDependency`，UI 灰显
  - QAIRT 路径无效时自动跳过并 warn
- **模型导出**（目录型）：
  ```
  qwen1.5b-genie/
  ├── genie_config.json
  ├── tokenizer.json
  ├── *.bin            # HTP context binary（一个或多个）
  └── metadata.json    # 可选：genie.chat_template 角色前后缀
  ```
  导出工具：Qualcomm AI Hub / `qnn-*` 转换脚本（见 `D:\3rd-party-projects\ai-hub-apps\chatapp_android`）。
- **SoC 限制**：仅骁龙 HTP。非骁龙 `probe()` → `UnsupportedSoc`，UI 灰显不崩
- **已知偏差**：`threads` / `seed` 不适用；`backend` 仅 `NPU_HTP` / `AUTO`
- **Jinja/minja**：未做 JNI。走 `metadata.json` 角色标签 + `ChatTemplate.format` fallback（与 chatapp 一致）

## 4. llama.cpp

- **依赖**：vendored 源码 `third_party/llama.cpp/`（tag `b9294`+ 快照；仅 `src/include/ggml/common/cmake/vendor` + CMakeLists + LICENSE）。`add_subdirectory` 静态编入
- **编译**：`engine/llamacpp/src/main/cpp/` → `libllamacpp_chat_jni.so`（含 libllama/libggml）
- **符号隐藏**：`-fvisibility=hidden` + version script / `-Wl,--exclude-libs,ALL`，只导出 `Java_*`（防四 so 符号冲突）
- **模型导出**：
  - 单文件 `.gguf`（魔数 `GGUF`）
  - 常用量化：`Q4_K_M` / `Q4_K_S` / `Q5_K_M` 等（`llama-quantize`）
- **已知偏差**：当前未链 GGML OpenCL，实际以 CPU 为主；契约上若请求不支持的 backend 会 `UnsupportedBackend` 拒绝而非静默回退
- **Backend**：CPU / GPU(→OpenCL) / OPENCL / NPU_HTP(可选) / AUTO
- **模板**：`llama_chat_apply_template`（JNI `nativeApplyChatTemplate`）

---

## 符号冲突与共存

四家 so 同进程是头号风险（protobuf / abseil / ggml 等 vendored 符号）。

1. 分 module 仅编译期隔离，运行时仍可能撞符号
2. 各 JNI 封装层：`-fvisibility=hidden` + `-Wl,--exclude-libs,ALL`
3. **每接入一个引擎立刻跑** `EngineCoexistenceTest`（不同加载顺序），不要攒到最后
4. `jniLibs.useLegacyPackaging = true`（Genie/llama.cpp/MNN 需 so 落盘 dlopen）

## 计时口径（统一，不得各自为政）

| 指标 | 起止 |
|------|------|
| TTFT | `generate()` 发出 → 首个 token 回调。**不含** load 与模板格式化 |
| prefill_tps | `promptTokens / (ttftMs/1000)`（promptTokens>0） |
| decode_tps | `(generatedTokens-1) / decodeSeconds`（generatedTokens>1） |

不适用的 `InferenceConfig` 字段：忽略 + 记入 `EngineMetrics.warnings`，`effectiveConfig` 标注生效值。

## 许可摘要

| 组件 | 许可 |
|------|------|
| LiteRT-LM / litertlm-android | Apache-2.0 |
| MNN | Apache-2.0 |
| QAIRT / Genie | Qualcomm 专有 SDK 条款（需自行获取授权） |
| llama.cpp | MIT |
| Jetpack Compose / Hilt / Room / DataStore | Apache-2.0 |

---

## 构建速查

```powershell
# 完整（含 Genie，需 QAIRT）
$env:JAVA_HOME = "D:\dev\AndroidStudio\jbr"
$env:ANDROID_HOME = "D:\dev\android_sdk"
.\gradlew.bat :app:assembleDebug

# 开发机 / 无 QAIRT
.\gradlew.bat :app:assembleDebug -Pdroid.skipGenie=true
```
