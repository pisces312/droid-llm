# 引擎集成说明（ENGINE_INTEGRATION）

> 本文说明四个端侧引擎的**依赖获取、编译开关、模型导出格式**。架构契约见 `DESIGN.md`，模型目录见 `MODEL_PATHS.md`。

## 总览

| 引擎 | 模块 | 依赖 | 打包形态 | 编译开关 |
|------|------|------|----------|----------|
| LiteRT-LM | `:engine:litert` | Maven `litertlm-android:0.11.0` | AAR 内 so | 无 |
| MNN | `:engine:mnn` | 预编译 `libMNN.so`（`droid.mnnRoot` / `MNN_ROOT`） | jniLibs 拷贝 | 路径不存在则跳过拷贝 |
| Genie | `:engine:genie` | QAIRT **2.50.0.260828**（`QAIRT_PATH`） | jniLibs 拷贝 | `-Pdroid.skipGenie=true` |
| llama.cpp | `:engine:llamacpp` | vendored `third_party/llama.cpp` | 静态编入 JNI | 无 |
| Fake | `:core:engine-api`（`src/test`） | 无 | 纯 Kotlin | **仅单测**，不随 App 发布 |

Fake 只作 `LlmEngine` 契约的可执行规格（`FakeEngineTest`）与写新引擎时的参照，**不注入 DI、不出现在 UI**。
保留 `EngineId.FAKE` 是因为存储层的历史记录可能读到它，且「模型根目录选择器」复用了它「任意路径、不做格式校验」的规则。

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
- **多轮会话**（对齐 gallery `LlmChatModelHelper`）：
  - 一个 `Conversation` 长驻，每轮只 `sendMessageAsync` 新的 USER 文本；历史由 native 累积
  - 仅当 `GenerateRequest.messages` 与 `liveHistory` 不一致，或上一轮被取消（`liveHistory=null`）时，才用 `initialMessages` 重建
  - `enable_thinking` 走 `sendMessageAsync` 的 `extraContext`，**默认 false**（与 gallery LLM_CHAT 一致；强制 true 会让小模型刷 `众所周`）
  - `onMessage` 可能是 token 增量或累计全文；适配器两者都兼容；丢弃 `<ctrl…>` 控制片
  - 达到 `maxNewTokens` 或检测到短语重复循环时 `cancelProcess`，按「完成」而不是「已停止」上报

## 2. MNN（阿里）

- **依赖**：预编译 `libMNN.so`（须含 LLM 组件）。查找顺序：
  1. Gradle 属性 `-Pdroid.mnnRoot=...`
  2. 环境变量 `MNN_ROOT`
  3. 两者都没有 → **构建失败**（不再回退到任何本机绝对路径）
  产物路径固定为 `<MNN_ROOT>/project/android/build_64/lib/libMNN.so`
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

### 2.1 MNN 实测行为与排障（2026-09-27）

> **完整版见 `docs/mnn.md`** —— 空回复根因、计时字段可信度、`end_with` 语义、
> so 溯源与 MD5 比对、调试手法都在那里。本节只留结论速查。

排查「MNN 发 `hi` 助手回复为空」时逐项对照 MNN 源码与官方 Android demo
（`apps/Android/MnnLlmChat`）得出的结论。代码依据位置：

| 结论 | 依据 | 本仓库处理 |
|------|------|-----------|
| `set_config` 必须在 `load()` **之前** | `llm.cpp` 的 `Llm::load()` 里 `config.type = backend_type_convert(mConfig->backend_type())`、`config.numThread = mConfig->thread_num()` 在构建 runtime 时读取；`set_config` 只做 `config_.merge()`，load 之后再调只影响采样参数 | `MnnNative.nativeCreate(dir, configJson)` 内 `createLLM → set_config → load`（与 `llm_session.cpp::LlmSession::Load()` 一致） |
| `response()` 的 `end_with` 传 `nullptr` 会写出 `"\n"` | MNN 对 nullptr 默认 "\n"，命中停止符时把该串**原样写进流**；它**不参与停止判定**（判定走 tokenizer 的 stop 列表） | 传空串 `""`，避免只有换行符的假回复 |
| 每轮生成前需把 `context->status` 复位成 `RUNNING` | 上一轮结束后停在 `NORMAL_FINISHED` / `MAX_TOKENS_FINISHED`，下一轮 `response()` 直接不解码 | `nativeGenerate` 内取 `getContext()`，非 RUNNING 则置 RUNNING（对齐 demo 的 `restoreAndroidSteppingStatusIfNeeded`） |
| `createLLM` 要传 `config.json` 绝对路径 | 传纯目录会拼出 `<dir>tokenizer.txt`（缺分隔符），tokenizer 加载失败 | Kotlin 侧传 `File(dir, "config.json").absolutePath` |

**首句 `hi` 空回复的根因**：模型自带的 jinja `chat_template` 只在 `messages[0]["role"] == "system"`
时才渲染 system 段，而纯 `hi` 渲染出的 prompt 只有 9 token → LFM2-350M 第一步采样即落在 EOS。
MnnLlmChat 之所以不空，是因为它**无条件注入**了一条 system。对照实验与代码依据见 `docs/mnn.md` §2。

**本项目处理**：设置 → 系统提示词，默认注入 `"You are a helpful assistant."`，可改可清空。
**注**：模拟器为 x86_64 + `libndk_translation.so` 二进制翻译执行 arm64 产物，
数值与采样结果不可信——性能结论须在真机 arm64 上复核。

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
- **SoC → dsp_arch**（QAIRT SDK 支持表，非 chatapp 表 —— 两者对 SM8850 不一致）：
  | SoC | soc_id | HTP arch | htp_config asset |
  |-----|--------|----------|------------------|
  | SM8850 (8 Elite Gen 5) | 87 | V81 | `qualcomm-snapdragon-8-elite-gen5.json` |
  | SM8750 (8 Elite) | 69 | V79 | `qualcomm-snapdragon-8-elite.json` |
  | SM8650 (8 Gen 3) | 57 | V75 | `qualcomm-snapdragon-8-gen3.json` |
  | SM8550 (8 Gen 2) | 43 | V73 | `qualcomm-snapdragon-8-gen2.json` |
- **APK 只打一个 arch**：非 release variant 默认只保留 `libQnnHtpV81{Skel,Stub}.so`（dev 机 SM8850）；release variant 保留 SDK 全部 arch 供 GitHub Release。覆盖：`-Pdroid.qnnHtpVersions=all|79,81`
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
