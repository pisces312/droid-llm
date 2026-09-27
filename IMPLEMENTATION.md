# droid-llm 实施计划（供独立会话执行）

> 本文档是 droid-llm 的可执行实施计划。执行前先通读 `DESIGN.md`（设计决策的唯一权威来源），本文档只做落地拆解，不重复论证设计。两者冲突时以 `DESIGN.md` 为准，并在 `DESIGN.md` 中回写偏差。

## 进度

| 阶段 | 状态 | 完成时间 | 备注 |
|------|------|----------|------|
| P0 | ✅ 完成 | 2026-09-26 | 工程骨架 + engine-api + FakeEngine + UI + smoke 框架（详见 git 历史 / 会话记录） |
| P1 | ✅ 完成，待审阅 | 2026-09-26 | llamacpp + mnn，见「P1 交付说明」 |
| P2 | ✅ 完成 | 2026-09-26 | litert，见「P2 交付说明」 |
| P3 | ✅ 完成 | 2026-09-26 | genie，见「P3 交付说明」 |
| P4 | ✅ 完成，待审阅 | 2026-09-26 | 核心 + UI 已通；见「P4 交付说明」 |
| P5 | ✅ 完成，待审阅 | 2026-09-26 | 打磨；见「P5 交付说明」 |

### P0 交付摘要（2026-09-26）

工程骨架（AGP 8.13.2 / Kotlin 2.1.20 / minSdk 31 / arm64 only）、`:core:engine-api`（LlmEngine 三契约 + FakeEngine）、`:core:common`（ModelPathStore / FileFormatValidator / DeviceProbe / MetricsCollector / ResultStore）、Compose 四页 UI、四引擎占位模块、`EngineCoexistenceTest` 骨架。`assembleDebug` 通过（APK ≈ 63MB）。

### P1 交付说明（2026-09-26）

**已完成**

1. **目录改名**：工程根为 `D:\my-projects\droid-llm`（会话外完成）。
2. **vendored llama.cpp**：`third_party/llama.cpp/`（源码树自本机 `D:\3rd-party-projects\llama.cpp`，tag `b9294`+ 后续提交快照；仅含 `src/include/ggml/common/cmake/vendor` + CMakeLists + LICENSE，约 26MB）。以 `add_subdirectory` 静态编入 JNI。
3. **`:engine:llamacpp`**：
   - `CMakeLists.txt`：`LLAMA_BUILD_*` 全关、`BUILD_SHARED_LIBS=OFF`、`CXX_VISIBILITY_PRESET hidden` + `-Wl,--exclude-libs,ALL`
   - `llamacpp_jni.cpp`：session 封装（model/ctx/batch/sampler）；`nativeLoad/Prefill/NextToken/ClearKv/Free`；UTF-8 断包拼接；`nativeApplyChatTemplate`（`llama_chat_apply_template`）
   - `LlamaCppEngine`：完整 `LlmEngine`；Backend 仅 CPU/AUTO（GPU/OpenCL/NPU 拒绝）；`MetricsCollector` 统一 TTFT 口径（含 prefill）；generate/unload 互斥
4. **`:engine:mnn`**：
   - 预编译 `libMNN.so`（`droid.mnnRoot` / `MNN_ROOT`，默认 `D:/3rd-party-projects/MNN`，`project/android/build_64/lib/libMNN.so`，已含 LLM 组件）；Gradle `copyMnnJniLibs` 打进 jniLibs
   - `mnn_chat_jni.cpp`：`Llm::createLLM` + `response(ChatMessages, ostream)` 流式 + `Utf8StreamProcessor`；`nativeReset/SetConfig/Generate`
   - `MnnEngine`：Backend CPU / OPENCL（GPU→OPENCL），NPU_HTP 拒绝；`set_config` 写 `backend_type/thread_num/max_new_tokens/temperature/top_k/top_p`
5. **chattemplate**：P1 两引擎均走各自原生模板（llama.cpp `llama_chat_apply_template`，MNN `Llm::response(ChatMessages)`）。minja JNI **推迟到 P3**（Genie 需要）；`ChatTemplate.format` 保留为 fallback。
6. **Smoke test**：`EngineCoexistenceTest` 已打开 llamacpp + mnn 三种加载顺序用例。
7. **验收**：`gradlew :app:assembleDebug` BUILD SUCCESSFUL；`app-debug.apk` ≈ 66.9 MB；内含 `libllamacpp_chat_jni.so` 7.5MB、`libMNN.so` 8.5MB、`libmnn_chat_jni.so` 0.09MB、`libc++_shared.so`/`libomp.so`；` :core:engine-api:testDebugUnitTest` 通过。

**已知偏差 / 待办**

| 项 | 说明 |
|----|------|
| llama.cpp OpenCL | 未链 GGML OpenCL，Backend 仅 CPU（契约：拒绝而非静默回退） |
| minja chattemplate | 推迟到 P3 Genie；P1 两引擎用原生模板 |
| 真机验证 | 构建/装包链路已通；流式聊天 DoD 需真机推模型后手测（见下方） |
| Settings 采样默认值 DataStore | P5 已绑（`AppSettingsStore`） |
| SAF 选择器 | 仍为可选占位（DESIGN §1.3 已降级） |

**P1 真机 DoD 检查单**

1. 推 `qwen1.5b-q4_k_m.gguf` → `Android/data/io.github.pisces312.droidllm/files/models/llamacpp/`
2. Models 页添加绝对路径并校验 GGUF 魔数
3. Chat 页选 llama.cpp + 该模型，流式对话，TTFT/tps 合理
4. 切到 MNN 目录模型（`config.json`+`llm.mnn`），同一聊天页来回切换不崩（单模型驻留）
5. `adb shell am instrument` 跑 `EngineCoexistenceTest`

**下一步（P2）**

1. 依赖 `com.google.ai.edge.litertlm:litertlm-android`（查 Maven 最新版本）
2. 纯 Kotlin 适配器（参照 gallery `LlmModelHelper`），跳过 chattemplate
3. `.litertlm` 模型可聊，三引擎切换

### P2 交付说明（2026-09-26）

**已完成**

1. **工具链升级**（litertlm 0.11.0 的 Kotlin metadata 为 2.3.0，2.1 编译器读不了）：
   - Kotlin `2.1.20` → `2.2.21`（与 gallery 一致）
   - KSP `2.1.20-1.0.32` → `2.3.6`（新版独立版本号，与 gallery 一致）
   - AGP 仍为 8.13.2，未动 SDK/JDK 路径
2. **依赖**：`com.google.ai.edge.litertlm:litertlm-android:0.11.0`（与 DESIGN.md 一致；gallery 同版本）
3. **`:engine:litert` 纯 Kotlin 适配器**（参照 gallery `LlmChatModelHelper`）：
   - `EngineConfig(modelPath, backend, maxNumTokens, cacheDir)` + `Engine.initialize()`
   - `createConversation(ConversationConfig(samplerConfig, systemInstruction, initialMessages))`
   - `sendMessageAsync` + `MessageCallback` 流式；`cancelProcess` / `close`
   - Backend 映射：`CPU→CPU()`、`GPU→GPU()`、`NPU_HTP→NPU(nativeLibraryDir)`、`AUTO→GPU()`（warning）；**OPENCL 拒绝**（LiteRT 走 GPU delegate）
   - NPU 路径 `SamplerConfig=null`（gallery 行为）；`threads`/`seed` 不适用时写入 warnings
   - 多轮：每次 generate 按 `GenerateRequest.messages` 重建 conversation（`initialMessages` 播种历史 + `sendMessageAsync` 最后一条 USER），保证历史权威
   - `MetricsCollector` 统一 TTFT/decode 口径；generate/unload 互斥
4. **chattemplate**：跳过（LiteRT 内部自带模板，与 PolyEngineInfer 一致）
5. **Smoke test**：`EngineCoexistenceTest` 增加 `touchLitertAar()`（`Class.forName("com.google.ai.edge.litertlm.Engine")`）；A /C 用例已打开 litert
6. **验收**：`:engine:litert:assembleDebug` + `:app:assembleDebug` BUILD SUCCESSFUL；`:core:engine-api:testDebugUnitTest` 通过；`app-debug.apk` ≈ 79.9 MB（新增 `liblitertlm_jni.so` 6.5MB、`libLiteRt.so` 2.1MB、`libLiteRtClGlAccelerator.so` 1.3MB）

**已知偏差 / 待办**

| 项 | 说明 |
|----|------|
| litertlm 版本 | 0.11.0（DESIGN 写死；Maven 查询超时，未写回“最新”） |
| Backend.AUTO | 解析为 GPU（gallery 默认）；CPU 可显式指定 |
| 生成期换 Backend | load 时绑定 backend；generate 内换 backend 仅影响 Sampler/警告，不重载引擎 |
| SAF | 仍拒绝，要求绝对路径 |
| 真机 DoD | 见下方检查单 |

**P2 真机 DoD 检查单**

1. 推 `.litertlm` / `.task` 模型 → `Android/data/io.github.pisces312.droidllm/files/models/litert/`
2. Models 页添加路径，校验扩展名
3. Chat 页选 LiteRT-LM，流式对话，TTFT/tps 合理
4. 三引擎（llamacpp / mnn / litert）同一聊天页来回切换不崩
5. `adb shell am instrument` 跑 `EngineCoexistenceTest`

**下一步（P3）**

1. Genie/QNN：`-PskipGenie=true` 门控 + QAIRT 本地路径 + SOC_MODEL HTP 配置（参照 chatapp_android）
2. minja chattemplate JNI（Genie 需要）
3. 四引擎真机可联，M2 达成

### P3 交付说明（2026-09-26）

**已完成**

1. **QAIRT 2.50 对齐 local-dream**：`QAIRT_PATH=D:/dev/qairt/2.50.0.260828`（env 优先，亦支持 `droid.qairtSdkRoot` / `QAIRT_SDK_ROOT`）。与 local-dream `AGENTS.md` / `rebuild-native.bat` 同一路径与变量名。
2. **编译门控**（三种跳过路径均验证）：
   - `-Pdroid.skipGenie=true` 显式跳过（并 `excludes **/*.so` + 清空 jniLibs.srcDirs，避免残留 so 入包）
   - QAIRT 路径不存在时自动跳过并 warn
   - 跳过时 Kotlin 仍编译，`probe()` 返回 `MissingDependency`，UI 可灰显
3. **JNI 薄封装** `libgenie_chat_jni.so`（`engine/genie/src/main/cpp/`）：
   - `nativeCreate(configJson)` → `GenieDialogConfig_createFromJson` + `GenieDialog_create`
   - `nativeGenerate` → `GenieDialog_query(COMPLETE, QueryCallback)` 流式 `onToken`
   - `nativeReset` / `nativeSetMaxNumTokens` / `nativeDestroy` / `nativeVersion`
   - CMake `IMPORTED libGenie.so` + `-Wl,--exclude-libs,ALL` 符号隔离
4. **QNN 运行时 so 打包**（`copyQnnJniLibs`，平铺进 `jniLibs/arm64-v8a/`）：
   - `libGenie.so` + `libQnnHtp.so` + `libQnnHtpPrepare.so` + `libQnnSystem.so` + `libQnnSaver.so`
   - `libQnnHtpV{68..81}Stub.so` + 对应 `hexagon-v*/unsigned/libQnnHtpV*Skel.so`（已 exclude CalculatorStub）
   - 与 chatapp_android `CopyQnnLibs` 同集合；jniLibs 已 gitignore
5. **HTP config 按 `Build.SOC_MODEL`**（chatapp 表）：SM8850/SM8750→8-elite、SM8650→8-gen3、QCS8550→8-gen2；assets `htp_config/*.json` 复制到 `filesDir/htp_config/` 后注入 `dialog.engine.backend.extensions`
6. **`GenieConfigResolver`**：`genie_config.json` 重写（tokenizer.path / ctx-bins 绝对路径 / extensions / sampler 覆盖 temp/top-k/top-p/seed），对应 chatapp `LoadModelConfig`；目录校验 `genie_config.json`+`tokenizer.json`+`*.bin`
7. **`GenieEngine`**：
   - Backend 仅 NPU_HTP / AUTO（AUTO→HTP + warning）；CPU/GPU/OPENCL **拒绝**
   - 多轮：`GenerateRequest.messages` 权威；历史一致则增量发最后一条 USER，否则 `reset` + 全量拼 prompt
   - Prompt tags：优先模型 `metadata.json` 的 `genie.chat_template`（AI Hub），否则 `user:/assistant:` fallback
   - 空回复：`GenieDialog_reset` + 重试，总尝试 ≤3（即额外 ≤2 次），仍失败则 `GenerateFailed`（DESIGN §6）
   - `MetricsCollector` 统一 TTFT；`generatedTokens≈回调次数`（Genie 流的是文本片段）记入 warnings；threads 忽略并警告
   - Hilt `@Binds @IntoSet` 注册
8. **Smoke test**：`EngineCoexistenceTest` A/C 用例在 `libgenie_chat_jni.so` 存在时断言加载成功
9. **验收**：`:engine:genie:assembleDebug` BUILD SUCCESSFUL；`:app:assembleDebug` BUILD SUCCESSFUL；`-Pdroid.skipGenie=true` 门控 BUILD SUCCESSFUL；APK ≈ **143 MB**（QNN HTP 全 arch Skel/Stub 体积大）

**已知偏差 / 待办**

| 项 | 说明 |
|----|------|
| QAIRT 版本 | DESIGN 写 2.45，本机/local-dream 用 **2.50.0.260828**；接口兼容（GenieDialog_*） |
| minja chattemplate | **未做 JNI**。Genie 走 metadata.json 角色标签 + ChatTemplate.format fallback，与 chatapp 一致；Jinja 模板模型留 P5 |
| promptTokens | Genie 无 tokenize 回调，写 0；prefill_tps 为空 |
| generatedTokens | 约等于流式回调次数（文本片段≠token） |
| APK 体积 | 143MB（含 V68–V81 全套 QNN HTP）；可按目标 SoC 裁剪 Skel/Stub |
| GenieProfile | 未接（chatapp 用它打 TTFT 日志）；我方 TTFT 统一走 MetricsCollector |
| 生成期换 Backend | load 时绑定；与 LiteRT 同限制 |
| SAF | 仍拒绝，要求绝对路径 |
| 真机 DoD | 见下方检查单 |

**P3 真机 DoD 检查单（M2）**

1. 推 AI Hub Genie 模型目录（`genie_config.json`+`tokenizer.json`+`*.bin`）→ `Android/data/io.github.pisces312.droidllm/files/models/genie/`
2. 骁龙真机（SM8850/8750/8650 等）上 Genie probe=`Available`；非骁龙=`UnsupportedSoc` 灰显不崩
3. Chat 页选 Genie 流式对话；偶发空回复能 reset+重试恢复
4. **四引擎**（llamacpp / mnn / litert / genie）同一聊天页来回切换不崩（单模型驻留）——**M2 达成**
5. `adb shell am instrument` 跑 `EngineCoexistenceTest` 全绿
6. 开发机/无 QAIRT 环境：`-Pdroid.skipGenie=true` 构建后 Genie 灰显 `MissingDependency`，其余三引擎正常

**下一步（P4）**

1. `:core:benchmark`：L/P/D（+可选 T）、warmup=1 + runs=3 中位数、RSS 三段 delta、电池温度
2. 跑某引擎前强制 unload 其他 Session；结果表含 engineId/modelName/modelPath/quantHint；JSON 导出
3. 结果页文案：跨模型/跨量化数字只作参考

### P4 交付说明（2026-09-26，核心 + UI 完成）

**已完成（核心）**

1. `SessionRegistry`（`:core:common`）：全局 Session 登记，bench 前 `unloadAll()` 保证 RSS 独占；ChatViewModel 已接入并修复「先切引擎再 unload」用错 engine 的问题
2. `:core:benchmark`：
   - `BenchmarkSpec` / `BenchCaseId(L/P/D/T)` / `BenchmarkPrompts`（中英代码总结 4 条）
   - `BenchmarkRunner`：warmup=1 + runs=3 中位数；L 测 load_ms；P 短输出 16 token 测 TTFT/prefill；D 短提示长输出测 decode_tps；T 多轮看掉速；超时 180s；失败样本保留 error
   - RSS 三段：baseline（unloadAll 后）→ load 后 → 生成峰值；电池温度起止记录
   - Room `ResultStoreDatabase`（Hilt 注入）落库；`JsonExporter` 导出 `getExternalFilesDir("benchmark")/`
   - 样本等待改 `CompletableDeferred` + `withTimeoutOrNull`（可取消/可暂停，不再阻塞 latch）
   - `run(..., awaitIfPaused)` 钩子：样本间可暂停，暂停区间不计时
3. 单测 4 个通过（JSON 必含 engineId/modelName/modelPath/quantHint）

**已完成（UI，对齐 [`UI_DESIGN.md`](UI_DESIGN.md)）**

1. **主题**：`ui/theme/{Color,Type,Theme}.kt` — StreamClip 紫/青 token + M3 双套 Light/Dark；`DroidExtraColors`（Accent/Warn/Ok）走 CompositionLocal；Metric 字号带 tabular `tnum`
2. **通用组件** `ui/components/UiComponents.kt`：EngineStatusCard / ModelPicker / MetricPill / PrimaryButton(52dp) / OutlinedToolButton / ProgressHeader / ResultTable（首列冻结+横滑）/ WarningBanner
3. **评测页** `BenchmarkViewModel` + `BenchmarkScreen`：
   - 引擎×模型卡片（可用默认勾选，勾选展开 ModelPicker，空态「去 Models 页」）
   - 提示词 4 Chip 单选；用例 Chip 默认 L/P/D；折叠参数 warmup/runs/maxNewTokens
   - 跑前 WarningBanner（插电/冷却提示）；进行中 `引擎 x/y · 用例 · 样本 i/n（含 warmup）` + 最近 decode tps + 取消
   - 退后台自动暂停 → 返回呈 Warn 描边暂停态，提供「继续 / 放弃本次评测」
   - 结果表列：引擎/模型/Quant/Load/TTFT/Prefill/Decode/RSS peak/温度；失败格 Error 短因；空值 `—`
   - 底部免责句 + 导出 JSON（Snackbar 路径提示）+ 历史列表可清空
4. **导航**：Tab 文案改为 聊天/模型/评测/设置（UI_DESIGN §4.3）
5. **验收**：`:app:assembleDebug` BUILD SUCCESSFUL；`:core:benchmark:testDebugUnitTest` 通过

**已知偏差 / 留给 P5**（P5 已完成，见「P5 交付说明」）

| 项 | 说明 |
|----|------|
| Chat/Models/Settings 视觉 | P5 已换 token |
| Chat「新建会话」溢出菜单 | P5 已补（`reset(handle)`） |
| 采样参数面板灰显不适用字段 | P5 已做（`ConfigApplicability`） |
| Settings 外观切换 / 多模型驻留开关 | P5 已接 DataStore |
| 内置文件浏览器 | P5 已做 |
| 进度条 fraction | 近似值（按引擎+样本估算），文案精确 |
| 真机 DoD | 见下方检查单 |

**P4 真机 DoD 检查单（M3）**

1. 配置 ≥1 个真实模型（或勾选 Fake）后一键跑 L/P/D
2. 进行中退后台再回：暂停态出现，可继续/放弃；暂停不计入样本耗时
3. 结果表出现 + 免责句完整；失败格显示短因非 0
4. 导出 JSON 到 `Android/data/.../files/benchmark/`，Snackbar 提示文件名
5. 历史列表出现本次记录，可清空

**阶段状态**：P0–P5 均已完成。P5+ 增量（debug/release 共存、正式版签名、模型路径安全迁移）已完成。剩余为真机 DoD 与 README 截图。

---

## 0. 前置上下文

- **项目名**：droid-llm（仓库 `droid-llm`，应用显示名 DroidLLM，包名 `io.github.<owner>.droidllm`）
- **工作目录**：`D:\my-projects\droid-llm`
- **定位**：单 APK 集成四端侧 LLM 引擎（LiteRT-LM / MNN / Genie / llama.cpp），统一聊天界面 + 轻量 benchmark。推理为主，评测为辅
- **参考项目本地副本**：`D:\my-projects\references\PolyEngineInfer`（浅克隆，无 submodule；`git submodule update --init <path>` 按需拉取）
- **四个原始参考工程**（gallery / MnnLlmChat / chatapp_android / ChatterUI）在真机上均已验证可跑，源码位置见执行者本地环境或重新拉取（GitHub 直连不稳时用 `https://gh-proxy.com/https://github.com/...` 镜像，已验证可用）

### 0.1 本机环境（来自 AGENTS.md，Windows 11）

| 项 | 位置/说明 |
|---|---|
| Android SDK / adb | `D:\dev\android_sdk`，adb 全路径 `D:\dev\android_sdk\platform-tools\adb.exe`，不在 PATH |
| Git Bash | 所有 shell 操作用 Unix 语法 |
| GitHub 访问 | 直连常被 reset，用 `gh-proxy.com` 前缀镜像 |
| pip | TUNA 镜像可用 |

### 0.2 不可变默认决策（DESIGN.md §10）

单 APK 全打、仅 `arm64-v8a`、模型一律外置、不做 DFM、不测功耗、Genie 带 `-PskipGenie` 门控、benchmark 默认 L/P/D（warmup=1, runs=3）、默认单模型驻留。

### 0.3 关键交叉引用（动手前先读）

- 统一接口契约：`DESIGN.md` §1.2（含 Backend 映射表、字段适用性约定、Session 线程安全契约）
- 模型路径与校验：§1.3
- Benchmark 用例与指标：§2
- 符号冲突对策（**P0 就必须做 smoke test**）：§6
- 参考清单（含 PolyEngineInfer 五个可参考点）：§7

---

## 1. 阶段总览与里程碑

| 阶段 | 内容 | 验收（DoD） | 估时 |
|------|------|------------|------|
| P0 | 工程骨架 + engine-api + FakeEngine + UI 导航 + Models 页 + **四 so 共存 smoke test 框架** | App 可安装，FakeEngine 可假聊 | 1–2 天 |
| P1 | `:engine:llamacpp` + `:engine:mnn` | 两引擎真机流式聊天，M1 达成 | 3–5 天 |
| P2 | `:engine:litert` | 三引擎可切换 | 1–2 天 |
| P3 | `:engine:genie`（可跳过编译） | 骁龙真机四引擎，M2 达成 | 2–4 天 |
| P4 | Benchmark L/P/D/T + Room + JSON 导出 | 一键出对比表，M3 达成 | 1–2 天 |
| P5 | 校验/错误提示/文档打磨 | 可交付 | 1 天 |

**顺序纪律**：llamacpp 先行（生态最成熟、调试最快），mnn 次之；litert 纯 Kotlin 最快；genie 最后且有跳过开关。每个引擎接入都走同一模板：probe → load → generate 流式 → metrics → smoke test。

---

## 2. P0 脚手架

### 2.1 工程初始化

- `settings.gradle.kts` 纳入模块：`:app`, `:core:engine-api`, `:core:common`, `:core:benchmark`, `:engine:litert`, `:engine:mnn`, `:engine:genie`, `:engine:llamacpp`, `:core:chattemplate`
  - `:core:chattemplate` 参照 PolyEngineInfer 的 `chattemplate/` 模块（minja + nlohmann/json，JNI），**P0 先建空壳**，P1 接 llama.cpp 时填充
- 版本：AGP 8.13.x、Kotlin 2.x、minSdk 31、target 35、NDK r27+、CMake 3.22+、`arm64-v8a` only
- `gradle/libs.versions.toml` 集中管理版本；依赖：Compose BOM、Hilt、Coroutines/Flow、Room、DataStore
- app 级配置：
  - `jniLibs.useLegacyPackaging = true`（Genie/llama.cpp 需 so 落盘 dlopen）
  - `noCompress += ["bin", "json", "mnn", "gguf", "litertlm", "task"]`（仅预留）
- 建 `scripts/build_native.ps1` 与 `scripts/export_benchmark.ps1` 空壳占位

### 2.2 `:core:engine-api`（纯 Kotlin，无 Android 依赖之外的重依赖）

按 `DESIGN.md` §1.2 原样落地：

- `EngineId / Backend / InferenceConfig / GenerateRequest / ChatMessage`
- `sealed class EngineEvent { Token, Done, Error }`
- `interface LlmEngine { probe / load / generate / reset / unload / lastMetrics }`
- `SessionHandle`、`GenerateJob`（支持 cancel）、`EngineMetrics`、`Availability`（`Available / MissingDependency / UnsupportedSoc / ModelNotConfigured`）、`EngineException`
- **把以下三条写成接口 KDoc 契约**（DESIGN.md §1.2 已定义，此处是执行提醒）：
  1. Backend 不支持的取值拒绝并提示，不静默回退
  2. `InferenceConfig` 不适用字段忽略并记 warning，指标里如实标注生效值
  3. 同一 `SessionHandle` 上 generate 与 unload/reset 互斥，generate 进行中 unload 阻塞或明确失败
- `FakeEngine`：固定延迟逐字吐 lorem ipsum，伪造 TTFT/tps 指标——UI 联调用，也是接口行为的可执行样例

### 2.3 `:core:common`

- `ModelPathStore`（DataStore）：`LocalModel` 列表持久化（字段见 §1.3，`location: FilePath | SafUri | AppPrivate`）
- `ModelFormatValidator`：四种格式的快速探测（扩展名 + 魔数/特征文件，规则见 §1.3）
- `DeviceProbe`：`Build.SOC_MODEL`、SDK、`libOpenCL.so`/`libcdsprpc.so` 探测、RAM/存储
- `MetricsCollector`：TTFT/prefill_tps/decode_tps 统一计时口径（计时点在 engine-api 层，**不要**像 PolyEngineInfer 那样把模板格式化算进 TTFT）
- `ResultStore`（Room）：benchmark 结果表，字段见 §2.3（含 baseline→加载后→峰值三段 RSS delta）

### 2.4 `:app` UI 骨架（Compose + Hilt 导航）

四个页面占位即可，FakeEngine 驱动 Chat 页全流程：

- Chat：引擎选择器（显示 Availability）+ 模型下拉 + 流式气泡 + TTFT/tps 角标 + 采样参数面板
- Models：四张引擎卡片（路径/添加/校验/删除）+ 同引擎多模型收藏
- Benchmark：页面骨架 + 「P4 实现」占位
- Settings：默认采样参数、后端偏好、数据目录

### 2.5 四 so 共存 smoke test（P0 必做，DESIGN.md §6）

- 建 `app/src/androidTest/EngineCoexistenceTest.kt`：按四种顺序排列组合 `System.loadLibrary` 真实 so（P0 阶段 so 还不存在，测试先 skip 并留 TODO 钩子，每接入一个引擎打开一组）
- 每个引擎 JNI 封装层的 CMake 从第一天就带：
  ```cmake
  set_target_properties(<jni_lib> PROPERTIES CXX_VISIBILITY_PRESET hidden)
  target_link_options(<jni_lib> PRIVATE "-Wl,--exclude-libs,ALL")
  ```
  或用 version script 只导出 `Java_*`

**P0 验收**：`gradlew assembleDebug` 通过；adb 安装后 FakeEngine 完整聊一轮；Models 页能对四种格式各校验一个样本文件。

---

## 3. P1 `:engine:llamacpp`

参照 PolyEngineInfer `llamacpp/` 模块与 ChatterUI。

1. llama.cpp 源码作为 submodule 或 vendored 快照（镜像拉取），钉住一个 release tag（PolyEngineInfer 用 b6018，执行时选当月稳定 tag）
2. `engine/llamacpp/src/main/cpp/CMakeLists.txt`：编 `libllama/libggml*` + `libllamacpp_chat_jni.so`（符号隐藏配置按 §2.5）
3. JNI 薄封装：load_model / new_context / decode 循环 / kv_cache_clear / perf_context；流式回调注意 **UTF-8 断包拼接**（参考 MnnLlmChat `llm_session.cpp` 的处理）
4. Kotlin 侧 `LlamaCppEngine : LlmEngine`：实现全部接口；backend 支持 CPU/OPENCL（GPU 映射 OpenCL），HTP 可选
5. `.gguf` 魔数校验接入 `ModelFormatValidator`
6. chattemplate 模块填充：用 minja 格式化 prompt（llama.cpp 不自带模板应用时）
7. 打开 smoke test 中 llamacpp 相关用例

**验收**：真机推一个 `qwen1.5b-q4_k_m.gguf` 到 `Android/data/<pkg>/files/models/llamacpp/`，流式聊天正常，TTFT/decode tps 显示合理，切模型即 unload 上一个（单模型驻留）。

## 4. P1 `:engine:mnn`

参照 MnnLlmChat 与 MNN 官方 Android 构建文档。

1. MNN 预编译 so（`MNN_SOURCE_ROOT` 指向本地 MNN 源码/产物，需含 LLM 组件：`MNN_BUILD_LLM=true` 配置编译，或用官方 release 的 llm 库）
2. `libmnn_chat_jni.so` 封装 `Llm::createLLM` / 流式 `Response()` / 新会话 reset
3. backend：CPU / OpenCL（GPU 映射）；`threads` 生效
4. 目录型模型校验：`config.json` + `llm.mnn`(+分片)
5. 生命周期竞态处理照抄 MnnLlmChat（`@Volatile` + synchronized），并实现 §2.2 的互斥契约

**验收**：MNN 目录模型流式聊天；与 llamacpp 在同一聊天页来回切换不崩——**M1 达成**。

## 5. P2 `:engine:litert`

1. 依赖 `com.google.ai.edge.litertlm:litertlm-android`（DESIGN.md 写 0.11.0；执行时先查 Maven 实际最新版本并回写文档）
2. 纯 Kotlin 适配器，参照 gallery 的 `LlmModelHelper`/`MetricsTracker`
3. 注意：LiteRT-LM 内置 chat template（PolyEngineInfer 代码里 `engine !is LiteRtLmInference` 才走 minja），适配器里跳过 chattemplate
4. backend：CPU / GPU(delegate) / NPU（若机型支持）

**验收**：`.litertlm` 模型可聊，三引擎切换正常。

## 6. P3 `:engine:genie`

参照 chatapp_android。全程可被 `-PskipGenie=true` 跳过。

1. QAIRT/QNN SDK 2.45 本地路径（环境变量 `QAIRT_SDK_ROOT` 或 gradle.properties 配置）；未配置时模块编译跳过、UI 显示 `MissingDependency`
2. `libgenie_chat_jni.so` 薄封装 `GenieDialog_create/query/reset`；`libQnnHtp*` 等 so 随包打
3. HTP config 按 `Build.SOC_MODEL` 选择（照抄 chatapp_android 的配置表）
4. `probe()` 非骁龙/无 HTP 时返回 `UnsupportedSoc`，UI 灰显说明原因
5. 目录校验：`genie_config.json` + `tokenizer.json` + `*.bin`
6. 已知坑：Genie 偶发空回复 → reset + 有限重试（≤2 次），计入错误率（DESIGN.md §6）

**验收**：骁龙真机四引擎矩阵全部 `Available`，缺依赖场景灰显不崩——**M2 达成**。四 so 共存 smoke test 全绿。

## 7. P4 `:core:benchmark`

按 DESIGN.md §2 执行，要点：

1. 用例 L/P/D（默认勾选）+ T（可选）；内置 4 条提示词（中文问答/英文问答/代码/总结）单选
2. warmup=1 + runs=3 取中位数；**跑某引擎前强制 unload 其他所有 Session**（§2.1-5）
3. RSS 三段 delta：baseline（全 unload 后）→ 加载后 → 生成峰值，读 `/proc/self/status`
4. 温度用 `ACTION_BATTERY_CHANGED` 电池温度（注明非 SoC 温度）；>42℃ 警告不拦截
5. 退后台自动暂停
6. 结果表强制展示 engineId/modelName/modelPath/quantHint；导出 JSON 到 `getExternalFilesDir("benchmark")/`
7. 结果页文案声明：跨模型/跨量化数字只作参考（§2.5）

**验收**：四引擎各选模型一键跑 L/P/D，出对比表并导出 JSON——**M3 达成**。

## 8. P5 打磨

**状态：✅ 完成，待审阅（2026-09-26）**

### P5 交付说明（2026-09-26）

**已完成**

1. **主题统一**：Chat / Models / Settings 全部换用 `ui/theme` token（16dp 边距、12dp 圆角卡片、MetricPill / PrimaryButton / OutlinedToolButton）。Chat 助手气泡下挂 `MetricPill`（TTFT / tok/s）。`PrimaryButton`/`OutlinedToolButton` 不再强制 `fillMaxWidth`，由调用方决定宽度。
2. **Chat**：
   - 顶栏溢出菜单「新建会话」→ `engine.reset(handle)` + 清空消息
   - 可折叠「采样参数」面板（temp/top_k/top_p/threads/maxNewTokens/backend）；默认值取自 Settings；**不适用字段灰显 + 12sp 说明**（`ConfigApplicability`，对齐各适配器 warnings：LiteRT/Genie 的 threads/seed、Genie backend 仅 NPU_HTP 等）
   - 发送/停止；错误气泡内红字短行；空态引导
3. **Models 内置文件浏览器**（`ui/models/FileBrowser.kt`）：
   - `java.io.File` 浏览；按引擎过滤（`.litertlm/.task` / `.gguf` / 目录）
   - `MANAGE_EXTERNAL_STORAGE` 已入 Manifest；未授权仅 App 私有目录 +「去系统设置授权」（`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`）
   - 其他 App 的 `Android/data/` 灰显不可选；校验失败行内写缺哪个文件
   - SAF 仍为可选占位（未做）
4. **Settings**（DataStore `droid_app_settings`）：
   - 默认采样参数持久化（temp/top_k/top_p/threads/maxNewTokens/backend）
   - 外观：深色 / 浅色 / 跟随系统（`MainActivity` 收集生效）
   - 多模型驻留开关（默认关 = 切换即 unload；开启行 12sp「8GB 机型易 OOM」）；Chat 切换按开关联动
   - 数据：模型根目录 / 评测导出目录展示、清空 benchmark 库
   - 关于与许可摘要
5. **错误文案**按 UI_DESIGN §6：`Availability`/加载失败/生成失败均「原因 + 怎么办」短句
6. **文档**：`docs/ENGINE_INTEGRATION.md`、`docs/MODEL_PATHS.md`、`README.md`；DESIGN 偏差表回写 SAF/API 两项

**验收（构建）**

- `:app:assembleDebug` BUILD SUCCESSFUL
- `:core:benchmark:testDebugUnitTest`、`:core:engine-api:testDebugUnitTest` 通过

**P5 真机 DoD 检查单**

1. 设置切换深/浅/跟随系统，四页配色一致
2. 聊天：新建会话清空上下文；采样面板灰显字段与当前引擎一致；助手气泡显示 TTFT/tok/s；生成中可「停止」
3. 模型页：浏览添加 `.gguf` / `.litertlm` / MNN 目录；未授权时引导可跳系统设置；校验失败红字提示
4. 设置：改默认采样后聊天面板默认值变；开关多模型驻留后切换模型行为符合描述
5. 评测：跑 L/P/D，结果表 + 导出 JSON + 清空库

**已知未做 / 可后续**

| 项 | 说明 |
|----|------|
| SAF 选择器 | 仍为可选占位（DESIGN §1.3 已降级） |
| Material You 动态取色 | UI_DESIGN §2.2 可选增强，默认关闭且未做开关 |
| OpenAI 兼容 API | 明确不做（P5+ 可选，不在范围） |
| 真机截图入 README | 待用户手测后补 |

---

## 8b. P5+ 增量：debug/release 共存、正式版签名、模型路径安全迁移

**状态：✅ 完成，待审阅（2026-09-26）**

### 需求

1. debug 与正式版可同时安装在一台设备上
2. 正式版用用户环境变量签名（不写死密码进仓库）
3. 修改模型路径时：**不删除目标路径中的文件**；重名交用户处理；原目录有数据询问是否迁移，可不迁移

### 交付

**1. debug/release 共存 + 环境变量签名**（`app/build.gradle.kts`）

| 项 | debug | release |
|----|-------|---------|
| applicationId | `io.github.pisces312.droidllm.debug` | `io.github.pisces312.droidllm` |
| versionName | `0.1.0-P0-debug` | `0.1.0-P0` |
| app_name | `droid-llm debug` | `droid-llm` |
| 签名 | 默认 debug key | `signingConfigs.release` |

环境变量（本机已有）：

| 变量 | 用途 |
|------|------|
| `KEY_STORE` 或 `KEY_STORE_LOCATION` | keystore 路径 |
| `KEY_STORE_PASSWORD` | store 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

缺 env 时 release 回退未自定义签名（AGP 默认），不把密码写进仓库。`app_name` 改由 buildType `resValue` 提供（`strings.xml` 仅保留注释）。

**2. 模型根目录可改 + 安全迁移**（`ModelRootMigrator` + Settings）

- `AppSettings.modelRootPath`（DataStore，null = `DeviceProbe.defaultModelRoot()`）
- Settings → 数据：「修改路径」（FileBrowser 选目录）+「恢复默认」
- 迁移契约（`core/common/.../ModelRootMigrator.kt`）：
  1. **永不删除/覆盖目标已有文件**
  2. 重名（顶层同名）→ 弹窗列出，用户选「跳过重名并迁移」或「取消」；跳过项留在原目录
  3. 原目录有 N 项数据 → 询问「迁移 / 不迁移 / 取消」；**不迁移**则只改根路径，原数据不动
  4. 迁移成功后按 `remapPath` 改写 `ModelPathStore` 中落在旧根下的 `FilePath` 登记项
- 拒绝新旧路径互相包含；跨文件系统 rename 失败时 copy+删源（源副本，不动目标）
- 单测：`ModelRootMigratorTest` 8 例（冲突不覆盖、目录树移动、remap 子孙路径、嵌套拒绝等）

### 验收（构建）

- `:core:common:testDebugUnitTest` 通过（8 tests）
- `:app:assembleDebug` / `:app:assembleRelease` BUILD SUCCESSFUL
- `apksigner verify --print-certs`：release 使用用户 keystore
- 模拟器同时安装 `io.github.pisces312.droidllm` + `io.github.pisces312.droidllm.debug`

### 真机 DoD 检查单

1. `assembleRelease` 在已导出 `KEY_STORE*` 的环境可出正式包；两包并存，图标名区分
2. 设置改模型根目录：原目录有数据时出现「迁移/不迁移」；选不迁移后原文件仍在
3. 目标已存在同名文件时出现重名确认；选择跳过后目标原文件内容不变
4. 迁移后模型页里指向旧根的条目路径已更新且仍可加载

---

## 9. 执行者注意事项（坑位速查）

1. **GitHub 直连不稳**：submodule/大文件优先 `gh-proxy.com` 镜像；失败重试前先 `rm -rf` 残留目录
2. **符号冲突是头号风险**：每接入一个引擎立刻跑共存 smoke test，不要攒到 P3
3. **模型路径一律真实路径**：SAF 已降级为可选（DESIGN §1.3），不引入 `content://` 反解负担；内置文件浏览器基于 `java.io.File`，前提是 `MANAGE_EXTERNAL_STORAGE`（无运行时弹窗，只能跳系统设置页授权）；Android 11+ 该权限也读不了其他 App 的 `Android/data/`，浏览器需灰显
4. **计时口径统一**：TTFT 从请求发出到首个 token 回调，不含模型加载和模板格式化；各适配器不得自行其是
5. **Genie 只支持骁龙 HTP**：开发机/模拟器上必须优雅降级，所有 P0–P2、P4 工作不依赖 Genie 可用
6. **不要扩大范围**：功耗测量、Dynamic Feature、雷达图、质量评测、OpenAI 兼容 API 均明确不做（API 是 P5+ 可选增强，不在本计划内）
7. **目录改名**：仓库建立后工作目录可从 `LlmChatAndroid` 改为 `droid-llm`，改名时同步 `DESIGN.md` 头部说明
