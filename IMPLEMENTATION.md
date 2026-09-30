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
| P6 | 🔄 进行中（R1 ✅、R1.5 ✅、R2 ✅、R3 ✅） | 2026-09-27 | UI/UX 重构；审查与方案见 [`docs/UI_REVIEW.md`](docs/UI_REVIEW.md)，见 §8d |
| P7 | 🔄 实施中（A1 ✅、A2 ✅、A3 ✅、A4 ✅） | 2026-09-28 | OpenAI 兼容 API 服务器；方案见 [`docs/API_SERVER.md`](docs/API_SERVER.md)，拆解见 §8e |

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
5. **HTP config 按 `Build.SOC_MODEL`**：SM8850→8-elite-gen5(v81)、SM8750→8-elite(v79)、SM8650→8-gen3(v75)、QCS8550→8-gen2(v73)；assets `htp_config/*.json` 复制到 `filesDir/htp_config/` 后注入 `dialog.engine.backend.extensions`
   - **SM8850 已从 chatapp 表里拆出**：chatapp 把 SM8850/SM8750 都指向 `8-elite.json`（soc_model 69 / v79），但 QAIRT SDK 支持表给 SM8850 的是 soc_id 87 / **V81**
   - **APK 侧裁剪**：非 release variant 只打 `libQnnHtpV81{Skel,Stub}.so`（省 ~23 MB 压缩后 / ~63 MB 未压缩），release（GitHub Release 用）保留全部 arch；`-Pdroid.qnnHtpVersions=all|79,81` 可覆盖
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

**阶段状态**：P0–P5 均已完成（P5+ / P5++ 增量亦完成）。**P6（UI/UX 重构）已完成：R1 ✅、R1.5 ✅、R2 ✅、R3 ✅、R4 ✅**，见 §8d。剩余为真机 DoD 与 README 截图。

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
| P6 | UI/UX 重构（R1–R4，见 §8d）；**R1 ✅ / R1.5 ✅ / R2 ✅ / R3 ✅** | 控件语义分层 + 顶栏单入口 + 各页密度合理 | 待估 |

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
- `FakeEngine`：固定延迟逐字吐 lorem ipsum，伪造 TTFT/tps 指标——P0 起用于 UI 联调，也是接口行为的可执行样例。
  **2026-09-27 起降为 test-only**：文件迁到 `core/engine-api/src/test`，不再注入 DI、不出现在 UI；仅作为契约单测（`FakeEngineTest`）与写新引擎时的参照。
  `EngineId.FAKE` 保留给历史存储记录与「模型根目录选择器」的任意路径校验规则

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
| OpenAI 兼容 API | P5+ 可选；**方案已定**见 [`docs/API_SERVER.md`](docs/API_SERVER.md)，实施列入 P7，不在 P0–P6 范围 |
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

## 8c. P5++ 增量：权限时机、共享模型根目录 + 模型市场、三方仓库环境变量

**状态：✅ 完成，待审阅（2026-09-26）**

### 需求

1. 「所有文件访问」不再首启弹窗；改为**用户决定修改模型根目录时**再引导（降低安装/首启打扰）。
2. 模型根目录**简化回单一共享根**（所有引擎同一根，下分子目录），便于下载落盘；增加模型市场：HF / ModelScope 切换、浏览下载、本地已有模型自动关联。
3. 构建定位第三方仓库改为**本机环境变量**，仓库内不写死绝对路径；`AGENTS.md` 只记 git 地址。

### 交付

**1. 权限时机**（`MainActivity` / `SettingsScreen` / `FileBrowserRules`）

- 删除首启 `storageGuideSeen` 主界面弹窗。
- Settings → 数据 →「修改」时：未授权先弹「需要所有文件访问」引导（去授权 / 稍后再说）；`openAllFilesAccessSettings` 带 `package:` URI + 三级 fallback（`MANAGE_APP_ALL_FILES_ACCESS` → `MANAGE_ALL_FILES_ACCESS` → `APPLICATION_DETAILS_SETTINGS`）。
- FileBrowser 内未授权仍显示「去系统设置授权」按钮（同修复）。
- **注意**：`MANAGE_EXTERNAL_STORAGE` 写在 manifest 里，部分安装器/商店仍可能在安装时提示「所有文件访问」；推迟的是 App 内引导，不一定消除系统安装警告。

**2. 共享模型根目录 + 模型市场**

- **引擎模型格式互不通用**（不能共用同一份权重，只是共享根目录）：

  | 引擎 | 格式 | 摆放位置 |
  |------|------|------|
  | LiteRT-LM | 单文件 `*.task` / `*.litertlm` | `{根}/litert/` |
  | MNN | 目录 `config.json` + `*.mnn` | `{根}/mnn/` |
  | Genie | 目录 `genie_config.json` + `*.bin` + `tokenizer.json` | `{根}/genie/` |
  | llama.cpp | 单文件 `*.gguf` | `{根}/llamacpp/` |

- `AppSettings.modelRootPath`（单根，null = `DeviceProbe.defaultModelRoot()` = `files/models/`）。引擎子目录：`llamacpp/` `mnn/` `litert/` `genie/`。
- **外部模型注册**（非市场下载）：**只登记、不搬文件**——校验后把任意绝对路径写入模型表，
  引擎直接打开原路径。不提供「复制到模型目录」：搬移既占双份空间，又让源路径失效后列表对不上。
  术语用「注册」而非「导入」，语义是登记索引。
- **仅市场下载**严格遵循模型根目录结构（`{hf|modelscope}/models--org--repo/snapshots/`）。
- 迁移契约不变（`ModelRootMigrator`：永不删目标、重名跳过、可不迁移）。
- **模型市场**（Models 页 Tab「模型市场」）：
  - 目录 `assets/model_catalog.json`（`CatalogModel`：id/name/engine/size/kind=repo|file|mnn_repo/localPath/sources/tags）。由 `scripts/gen_model_catalog.py` 从 MnnLlmChat `model_market.json` 生成，当前 **159 条 MNN** + LiteRT/GGUF 共 162 条。
  - **服务器切换**（模型市场顶部，仿 MnnLlmChat `SourceSelectionDialog`）：**HF官方**（huggingface.co）/ **HF镜像**（hf-mirror.com）/ **ModelScope**（modelscope.cn）。HF 官方与镜像共用 catalog 的 `sources.HuggingFace` 仓库 id，仅 host 不同；URL 按 `ModelSource.host` 拼：
    - HF 文件 `{host}/{repo}/resolve/main/{path}`；目录树 `{host}/api/models/{repo}/tree/main?recursive=true`
    - MS 文件 `https://modelscope.cn/api/v1/models/{repo}/repo?FilePath={path}`；文件列表 `.../repo/files?Recursive=1`
  - **下载状态过滤**：市场第二行 **全部 / 已下载 / 未下载**（`DownloadFilter`）。
  - 下载器 `ModelDownloader`（HttpURLConnection + `.part` 临时文件 + 进度 StateFlow）。MNN 走 `kind=mnn_repo`，LiteRT/GGUF 走 `kind=file`。
  - **下载即注册**：对话模型下载成功后自动 `registerDownloaded` 写入 `ModelPathStore`（已注册 Tab / 聊天模型选择器可见）；ImageGen/AudioGen 等非对话模型只落盘不入库。按路径去重，重复注册提示「已在模型列表中」。
  - **本地关联**（`findModelDir`）：按候选相对路径扫描；命中即标「已下载」并显示实际路径。
  - 聊天页持续 `observeModels()`，市场新下载无需切页/重启即可出现在模型选择器。

- **MNN 存储结构（对齐 MnnLlmChat）**，相对模型根目录。**所有引擎**从 HF/魔塔市场下载都走这套布局：

  ```
  {sourceDir}/models--{org}--{repo}/snapshots/_no_sha_/   # 新下载（sourceDir = hf | modelscope）
  {sourceDir}/models--{org}--{repo}/snapshots/{sha}/      # MnnLlmChat HF 提交 sha 也识别
  mnn/{sourceDir}/models--{org}--{repo}/snapshots/...     # 备选（引擎子目录下）
  mnn/{name}/  ·  modelscope/{name}/  ·  {name}/          # 旧布局兼容
  ```

  `kind=file` 的单文件（LiteRT `.task` / GGUF）落在 snapshot 目录内；`kind=repo|mnn_repo` 整仓落 snapshot 目录。`sourceDir`：HF（含镜像）→ `hf/`，ModelScope → `modelscope/`。**把模型根目录指到手机上的 `mnn-models/` 即可识别 MnnLlmChat 已下载的模型**（截图：`mnn-models/modelscope/models--MNN--*`）。第三方导入仍用 `{根}/{引擎子目录}/`。

- **Gallery 模型来源**：Google AI Edge Gallery 的模型**在 HuggingFace**（allowlist + `https://huggingface.co/{modelId}/resolve/{commit}/{modelFile}`），**没有 ModelScope 源**；MNN 社区模型才有 HF（`taobao-mnn/*`）+ ModelScope（`MNN/*`）双源。市场条目已按此配置。

**3. 三方仓库环境变量**

- `engine/mnn`：`MNN_ROOT` / `droid.mnnRoot`，**无默认路径**，缺失直接 `error(...)`。
- `engine/genie`：`QAIRT_PATH` / `QAIRT_SDK_ROOT` / `droid.qairtSdkRoot`，缺失自动跳过 native。
- Windows 用户级 `setx`：`MNN_ROOT` `QAIRT_PATH` `GALLERY_ROOT` `AI_HUB_APPS_ROOT` `CHATTERUI_ROOT` `MNN_LLM_CHAT_ROOT`。
- `AGENTS.md` 新增「第三方仓库（只记 git 地址）」表；`gradle.properties` 示例路径改为占位。

### 验收（构建）

- `:app:compileDebugKotlin` / `:app:assembleDebug` BUILD SUCCESSFUL
- `:core:common:testDebugUnitTest` 通过
- manifest 增加 `INTERNET`（市场下载）

### 真机 DoD 检查单

1. 冷启动**无**权限引导弹窗；Settings → 修改模型根目录时出现「去授权」引导，按钮能打开系统「所有文件访问」页
2. Settings 显示单一共享根；修改/恢复默认 + 迁移/不迁移/跳过重名流程与 8b 相同
3. 模型市场：可切 **HF官方 / HF镜像 / ModelScope**；**全部/已下载/未下载** 过滤；目录约 162 条（MNN 159，与 MnnLlmChat 对齐）；模型根指到 `mnn-models/` 时 `modelscope/models--MNN--*/snapshots/*/` 显示「已下载」
4. 已 push 到根目录的模型显示「已下载」，点「添加到列表」进入已导入 Tab；列表项显示实际命中路径
5. **市场下载完成的对话模型**立即出现在「已导入」Tab 和聊天模型选择器（无需重启/切页）；ImageGen/AudioGen 不自动入库
5. 断网/半截下载失败后状态为「下载失败」，`.part` 不残留为正式文件

---

## 8d. P6 增量：UI/UX 重构（**R1 ✅ / R1.5 ✅ / R2 ✅ / R3 ✅ / R4 ✅ 全部完成**）

**状态：✅ 已完成（2026-09-27 立项并当日收尾；R1 → R1.5 → R2 → R3 → R4 全部落地）**

> **遗留**：R2 的②③⑤、R3 的手感复核、R4 的视觉复核三项需**真机 arm64**手测（模拟器跑不了真实流式与评测），
> 见各「交付说明」末尾的「未验证」段与 `docs/mnn.md` §6.1。代码侧无待办。

> **权威输入**：[`docs/UI_REVIEW.md`](docs/UI_REVIEW.md) —— 现状盘点 + 问题清单（含文件:行号）+ 布局方案 + 与 MnnLlmChat 的逐条对照。
> 本节只做落地拆解，不重复论证。**动手前先通读该文档**；契约约束见 `DESIGN.md` §1.2，界面权威见 `UI_DESIGN.md`。

### 立项依据（两句话）

1. **控件语义混乱**：同一个主色填充按钮被复用于"选中 / 主操作 / 次级"12+ 处 → 每页一排紫块，大数字与主按钮被淹没。
2. **信息密度错配**：工具型 App 却把说明文字与宽松大卡片堆在首屏（最典型：评测页「开始评测」不在首屏）。

外加一组一致性硬伤：**同一个引擎在四个界面有四种写法**（`MNN 3.6.1 #c0461933` / `LLAMACPP` / `LITERT` / 失败行 `LITERT`）。

### 关键设计结论（已定，勿反复）

| 结论 | 说明 |
|---|---|
| **引擎 + 模型合并成一个入口** | 顶部"作用域条"整行可点 → 一个 BottomSheet 管两级（引擎 chip 切作用域 + 该引擎模型列表）。对上 `DESIGN §1.2` 的从属关系 |
| **入口本体不用图标** | 实测 MnnLlmChat `ModelSwitcherView` 就是"文字 + 下拉箭头"（`view_model_switcher.xml`）；图标只出现在弹层**列表条目**里 |
| **不把引擎/模型移到输入框旁** | 会话级配置（切换 = unload + 重新 load，秒级阻塞）与每条消息级开关（采样等）语义不同，位置即语义 |
| **弹层只做"选中"，不自动 load** | 选引擎/模型仅释放旧会话回 `IDLE`，加载仍由主按钮触发。外壳换了，`DESIGN §1.2` 契约不动 |
| **模型不做专属图形，但用厂商 logo** | 每个模型一个图标不可行；改为"**厂商** logo（命中约 74%）+ 文字兜底"，见下方决策 3 |
| **底部导航保持 4 tab 常驻** | 当前宽度充裕，不降级为 2 tab + 溢出菜单，见下方决策 1 |

### 已拍板决策（2026-09-27）

| # | 决定 | 落地要点 |
|---|---|---|
| 1 | **4 tab 全部常驻**，不改底部导航 | 无改动项；`DroidLlmRoot.kt` 导航结构不动 |
| 2 | 引擎标识用**状态点 ●** | 复用 `EngineStatusCard` 已有三态色（绿=可用 / 灰=不可用 / 琥珀=忙）；顺带替掉单独占一行的"可用 · 已加载"。映射表见 `UI_REVIEW.md` §7.2 |
| 3 | **引入厂商 logo** | logo 源 = MnnLlmChat `res/drawable-nodpi/*_icon.{png,webp}`（17 个文件约 900KB，`smolm_icon.png` 单文件 417KB 需压缩）。**我们的 `assets/model_catalog.json` 已有 `vendor` 字段**（162 条 / 28 家厂商），市场模型直接读字段，用户自导入才子串兜底。命中率约 **120/162 ≈ 74%**（Qwen 一家 63 条）。厂商分布表见 `UI_REVIEW.md` §7.1 |
| 4 | 采样参数**搬进 BottomSheet** | Chat 输入卡上方只留一个 chip，点击开 sheet（与 MnnLlmChat 模式 7 一致） |

> **决策 3 的合规项**：厂商 logo 是各公司**商标**，MNN 仓库的 Apache-2.0 **不覆盖商标**；用于标识模型出处属指示性使用（业界普遍做法）。**R4 落地时须在 `docs/LICENSING.md` 增记一条**（来源、用途、商标归各厂商所有）。
> **决策 1 不产生改动项**。


### 分步计划

| 步骤 | 内容 | 覆盖问题 | 风险 |
|---|---|---|---|
| **R1** ✅ | **引擎显示名统一**：`engine-api` 加 `val EngineId.displayName`，各引擎 `override val displayName` 改为返回它（**单一来源**）；**6 处展示面**全改走它（`labelledName` 仅留给需版本号的场合）。**已完成**，见下方「R1 交付说明」；配套补丁 **R1.5**（Models 三行 chip）见「R1.5 交付说明」 | P0-1 | 极低，纯改名零行为变更 |
| **R2** ✅ | 五个小改纯收益项：①数值输入本地 buffer（`NumericField`，**每次输入即提交**——非"失焦再解析"，见交付说明）②跟随滚动三件套 ③结果表补单位 ④`Settings` 的 `backend` 只读框 → 下拉 ⑤`ResultTable` 固定行高。**已完成**，见下方「R2 交付说明」 | P0-3~7 | 低（**已完成 2026-09-27**；②待真机验） |
| **R3** ✅ | **三层控件体系**（筛选 Chip / 一屏仅一个 Filled 主操作 / 次级 Outlined）+ Chat 顶栏重构（作用域条 + 两级 BottomSheet，引擎 chip 带**状态点 ●**〔决策 2〕）+ 采样参数收成输入框上方 chip 并**点击开 BottomSheet**〔决策 4〕。**已完成并回写 `UI_DESIGN.md`**，见下方「R3 交付说明」 | §4.2 首条 + §5.1 | 中（**已完成 2026-09-27**；UI 效果待设备确认） |
| **R4** ✅ | 评测页密度重构（置顶"选模型"卡 + 紧凑引擎行，让「开始评测」回首屏）+ **厂商 logo 资产接入**〔决策 3，含压缩与 `docs/LICENSING.md` 增记〕+ 空态统一为「插图 + 说明 + 按钮」+ 其余打磨。~~Models 引擎 chip 行~~ **已由 R1.5 提前完成**。**已完成**，见下方「R4 交付说明」；实际做法与计划的差异见该节① | §4.2 / §4.3 | 中（**已完成 2026-09-27**，13 个 logo 压到 96.5KB） |

### R1 交付说明（2026-09-27，✅ 完成）

**问题**：同一个引擎，**六个界面六种写法**（`LLAMACPP` / `LITERT` / `MNN 3.6.1 #c0461933` 混用）。

**改动**（8 个文件 + 1 个新增测试）

| 文件 | 改动 |
|---|---|
| `core/engine-api/.../LlmEngine.kt` | 新增 `val EngineId.displayName`（**唯一来源**，5 个 id 全覆盖）+ `fun engineIdFromStorage(raw)` 反解持久化字符串 |
| `engine/{mnn,litert,genie,llamacpp}/...*Engine.kt` | `override val displayName` 四处硬编码字符串 → `get() = id.displayName`（各加一个 import） |
| `ui/models/ModelsScreen.kt` | 三处：已导入列表 `${model.engineId}`、引擎按钮 `id.name`×2、**市场列表** `${row.model.engine}` |
| `ui/benchmark/BenchmarkScreen.kt` | **历史卡** `${item.engineId}`（经 `engineIdFromStorage` 反解） |
| `core/benchmark/.../BenchmarkRunner.kt` | 失败 fallback `target.engineId.name`、`not registered` 错误串 |
| `ui/benchmark/BenchmarkViewModel.kt` | 失败提示 `${p.engineId.name}` |
| `core/engine-api/src/test/.../EngineIdDisplayNameTest.kt` | **新增** 3 例：displayName 全非空、适配器与共享表一致、`fromStorage` 往返/大小写/未知值 |

**比原计划多修 2 处**：原审查只列 4 处，实施时发现**市场列表**与**评测历史卡**也各自泄漏——这两处存的是 **`EngineId.name`（机器可读 ID）而非展示名**，故新增 `engineIdFromStorage` 反解后再取 `displayName`。

**明确保留 `.name` 的位置**（持久化 / 机器可读，**不得改**）：`ModelsViewModel.engineDir`（目录名，写成 `mnn/` `litert/` …）、`DataStoreModelPathStore:82`、`BenchmarkRunner:418,420`（Room 字段）、`JsonExporter:31`（JSON `engineId`，与 `engineDisplayName` 成对）。**Chat 顶栏仍用 `labelledName`**（版本号在那里有用）。

**验收**
- `:app:assembleDebug` BUILD SUCCESSFUL（`app-debug.apk` 161.5 MB）
- `:core:engine-api:testDebugUnitTest` 4 tests、`:core:benchmark:testDebugUnitTest` 4 tests，**全绿 0 失败**
- 真机截图比对**未做**（需用户手测：四个界面同一引擎应显示同一串文字）

**踩坑**：Kotlin 对**没有显式 companion** 的枚举，`EngineId.Companion` 作类型引用会 `Unresolved reference 'Companion'` → 反解函数改为顶层函数 `engineIdFromStorage`。

### R1.5 交付说明（2026-09-27，✅ 完成）

**起因**：R1 把展示名改对之后名字变长，**Models 里那排等宽按钮断字反而更明显**——
`llama.cpp` 断成 `llama .cpp`、`LiteRT-LM` 断成 `LiteR T-LM`、`ModelScope` 断成 `ModelScop` + `e`。
（属 R4 范围，但改动很小、收益立竿见影，故提前做。）

**改动**（1 个文件：`ui/models/ModelsScreen.kt`）

| 位置 | 原实现 | 现实现 |
|---|---|---|
| 引擎选择（LiteRT-LM / MNN / Genie / llama.cpp） | 4 个 `weight(1f)` 按钮，选中 = 主色实心 | `ChoiceChipRow` |
| 下载源（HF官方 / HF镜像 / ModelScope） | 3 个 `weight(1f)` 按钮 | `ChoiceChipRow` |
| 下载过滤（全部 / 已下载 / 未下载） | 3 个 `weight(1f)` 按钮 | `ChoiceChipRow` |

新增私有 `ChoiceChipRow<T>(options, selected, label, onSelected)` = `Row` + `horizontalScroll` + `FilterChip`。
原 `EngineIdDropdown` 是私有函数且**名称与形态不符**（它从来不是 dropdown），随改动删除。

**为什么用横向滚动而不是 `FlowRow`**：`FlowRow` 放不下时折行、高度翻倍；横向滚动保持一行，
且子项拿到无限宽约束，文字永不折行。实测 4 个引擎 chip 总宽 **891px < 可用 1038px**，
**一屏就放得下、根本不需要滚动**。选中的 chip 用 Material3 默认选中态（浅色容器），
不再是主色实心按钮——顺带踩上 R3「筛选走 Chip」的方向。

**验收**：`:app:assembleDebug` BUILD SUCCESSFUL；模拟器实测三行文字**全部单行**
（`LiteRT-LM` / `llama.cpp` / `ModelScope` 均完整）；截图 `build/uicheck/10_market_chip.png`（市场页三行）、
`11_engine_chip.png`（引擎行）。



### R2 交付说明（2026-09-27，✅ 完成）

五项小改，都在 `:app` 的 UI 层，共 6 个文件。

| # | 项 | 改动 |
|---|---|---|
| ① | 数值输入 buffer（P0-4） | 新增 `ui/components/NumericField`，替代 `SettingsScreen` 5 处 + `ChatScreen` 采样面板 5 处原生 `OutlinedTextField`；删除 `ChatScreen` 里的私有 `NumberField` |
| ② | 跟随滚动三件套（P0-5） | `ChatScreen`：触发键由 `messages.size` 改为 `(lastIndex, 尾条内容长度)`；新增 `derivedStateOf` 判「是否停在底部」；列表视口右下角加「回到底部」按钮 |
| ③ | 结果表补单位（P0-7） | `BenchmarkScreen` 表头 → `Load ms` / `TTFT ms` / `Prefill tok/s` / `Decode tok/s` / `RSS peak MB` / `温度 ℃` |
| ④ | `backend` 假输入框（P0-3） | `SettingsScreen` 的 `readOnly` 框改为下拉；顺带把通用组件 `ModelPicker` 更名为 `LabeledDropdown`（它现在也服务非模型场景，原名字与用途不符） |
| ⑤ | 结果表行高（P0-6） | `UiComponents.TableRowHeight = 52.dp`，所有 `TableCell` 固定同高 |

**① 为什么不是"失焦再解析"（推翻了原方案）**：原计划写的是"本地 buffer + 失焦/IME 完成再解析写回"，
动手实测发现本 App 里**失焦事件基本不触发**，两条证据：

1. **触屏模式下点按钮不移动焦点**——点「深色」按钮后 `uiautomator` 里输入框仍是 `focused="true"`，
   `onFocusChanged` 不回调；用 `DisposableEffect(onDispose)` 兜底也无效（切底部 tab 时编辑直接丢：
   屏幕 `0.75` → 切页回来 `0.7`）。→ 改为**每次输入即提交**，本地 buffer 只负责把 `0.` 这类中间态留在屏幕上。
2. **`"0.".toFloatOrNull()` 返回 `0.0f`，不是 null**——所以"解析失败就不提交"挡不住半截输入：
   把 `0.7` 删成 `0.` 会**静默把 temp 写成 `0.0`**（实测到了）。补一条"末尾是小数点就不提交"的判断。

**模拟器实测**（`emulator-5554`）：`0.75` → 删 1 位显示 `0.`（不再被弹回）→ 删到 `0.` 后切页再回 = `0.7`
（半截状态不污染存储）→ 补成 `0.75` 切页往返 = `0.75`；输入非法 `abc` 后焦点移走回退为规范值。
④：`backend` 点开列出 `CPU / GPU / OPENCL / NPU_HTP / AUTO`，选中落盘回显（节点从纯 `EditText` 变成带
`android.widget.Spinner` 子节点）。截图 `build/uicheck/20_chat_sampling.png`。

**未能在模拟器验证**：②跟随滚动需要真实流式回复（要 load 模型，模拟器跑不了，见 `docs/mnn.md` §6.1）、
③⑤需要跑一次评测才有结果表。**三项均待真机 arm64 手测。**


### R3 交付说明（2026-09-27，✅ 完成）

重构 + 分层，共 6 个文件（`ChatScreen` / `ChatViewModel` / `ModelsScreen` / `SettingsScreen` /
`UiComponents` / 无新增文件）。

**① Chat 顶栏：双下拉 → 作用域条 + 两级 BottomSheet**

| 旧 | 新 |
|---|---|
| 第一行：引擎下拉 + 模型下拉各占半宽（`EngineModelPickers`） | 一整行「作用域条」`● 引擎 · 模型 ⌄`，`SurfaceHigh` 底 + 12dp 圆角，整行可点开 `ScopeSheet` |
| 第二行：状态行 + 启动/停止 + `MoreVert` 溢出菜单（只有一个条目） | 状态行 + 启动/停止（40dp）+「新建会话」图标按钮 |

- `ScopeSheet`（`ModalBottomSheet`）：`SheetTitle` → 引擎 chip 行（复用 `ChoiceChipRow`，带 `StatusDot`）
  → 选中引擎不可用时的原因行 → `HorizontalDivider` → 模型 `LazyColumn`（名称 + `引擎 · 量化 · 体积` + 选中勾）。
  选模型后自动收起；只切引擎时保持打开，方便接着选模型。
- **契约未动**：`selectEngine` / `selectModel` 仍是"释放旧会话 → `IDLE`"，**不自动 load**（`DESIGN §1.2`）。
- **引擎只有一个入口**：作用域条本身不切引擎，只在 sheet 内部切，否则又变回两个入口。

**② 采样参数：内联折叠卡 → chip + sheet**。输入框上方一颗 `AssistChip`（`temp … · top_p … · tok …`
+ Tune 图标）→ `SamplingSheet`（6 个字段原样搬入 + 底部「完成」）。字段适用性灰显与说明未改。
`BackendField` 从 `ExposedDropdownMenuBox` 改为复用 `LabeledDropdown`。

**③ 三层控件职责**（`UI_DESIGN.md` §4.4 新增）：

| 层 | 控件 | R3 实际改动 |
|---|---|---|
| L1 筛选/选中 | `ChoiceChipRow` | Settings「外观」原来是 Filled/Outlined **按钮对**表达选中 → 改 chip |
| L2 主操作 | `PrimaryButton` | Models 添加卡原有 **3 颗按钮含 2 颗 Filled** → 路径框尾部图标按钮（浏览）+ 唯一 Filled「导入并复制」+ Outlined「仅引用」；市场「刷新状态」从**每行一颗**提到工具栏一颗 |
| L3 次级 | `OutlinedToolButton` | 「启动/停止」「加载中」改用共用组件（新增 `height` 参数，状态行用 40dp） |

**「一屏一个 Filled」的判定**写成按**当前可见状态**算：聊天页未加载时 Filled =「启动」而发送钮灰显，
`READY` 后「启动」降级为 Outlined「停止」、Filled 让给「发送」——两者永不同时为 Filled。

**④ 新增共用组件**（全在 `UiComponents.kt`）：`StatusDot` + `enum StatusDotState`（三态：绿实心=在用 /
绿空心=可用未加载 / 琥珀=忙 / 灰=不可用）、`ChoiceChipRow<T>`（`dimmed` 只降透明度**不禁用**，
`leading` 放状态点）、`SheetTitle`、`formatModelSize`（十进制单位，从 `ModelsScreen` 私有函数提升，
市场列表与模型选择器共用）。`ChoiceChipRow` 由 `ModelsScreen` 私有提升为公共——**ModelsScreen 视觉不变**。

**⑤ 一处小重构**：`EngineChoice.unavailableReason()` 抽成顶层函数，状态行与 sheet 引擎 chip 共用同一份
原因文案（原来只在 `ChatViewModel.describeAvailability` 里，是 `private`）。

**踩坑**：M3 的 `skipPartiallyExpanded` 是 **`rememberModalBottomSheetState(...)` 的参数**，
不是 `ModalBottomSheet(...)` 的——按后者写直接编译不过。另外 `selectedModel?.model.id` 会解析成
`(selectedModel?.model).id`（可空接收者），需写 `selectedModel?.model?.id`。

**验证**：`:app:assembleDebug` BUILD SUCCESSFUL；`:core:engine-api` / `:core:common` 单测全绿。

**模拟器实测**（`emulator-5554`，截图 `build/uicheck/r3_0*.png`）：

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 作用域条 | `● LiteRT-LM · 未选择模型 ⌄` → 选完模型变 `● MNN · LFM2-350M-MNN ⌄`，**空心绿点 = 可用未启动**，符合 §7.1 |
| 2 | `ScopeSheet` | 引擎 chip 行 4 个（`Genie / LiteRT-LM / MNN / llama.cpp`）+ 状态点；`llama.cpp` 超出屏幕 → 横向滚动（预期） |
| 3 | 引擎 chip 联动模型列表 | 切 `LiteRT-LM`（无模型）→「该引擎还没有模型…」；切 `MNN` → 列出 `LFM2-350M-MNN` |
| 4 | **★ `DESIGN §1.2` 回归** | 切引擎 / 选模型后状态行仍为「未启动：点击『启动』加载 …」，logcat 无 `load` 调用 → **弹层只选中、不自动 load** ✓ |
| 5 | 模型条目选中勾 | 唯一模型自动选中，右侧 `Check` 图标节点存在（`[954,2118][1017,2181]`） |
| 6 | 选模型后自动收起 | 首轮实测**漏接线（弹层不关）**，已修（`onModel` 内一并 `switcherOpen = false`）并复测通过 |
| 7 | 参数 chip + `SamplingSheet` | chip 显示 `temp 0.7 · top_p 0.95 · tok 128`；点开 6 个字段 + `AUTO` 下拉 + 「完成」，MNN 下无灰显字段 |
| 8 | Settings 外观 | `深色 / 浅色 / 跟随系统` 已是 FilterChip（选中态紫色填充），不再是 Filled/Outlined 按钮对 |
| 9 | Settings `backend` | 真下拉（带 `Spinner` 子节点），非只读输入框 |
| 10 | Models 添加卡三层 | 引擎 chip 行一行放得下 4 个（无断字）；源路径框**尾部文件夹图标**；Filled 只剩「导入并复制到模型目录」 |
| 11 | Models 市场 | 工具栏一行 = 说明 + **一颗**「刷新状态」；条目 `MNN · Gemma · 3.7 GB`（`formatModelSize` 生效） |

**未验证（模拟器不可及）**：跟随滚动（②R2）与结果表（需 real 流式 / 评测）
仍待真机 arm64，见 `docs/mnn.md` §6.1。

**一处文案连带修正**：Settings 的 backend 说明原写「见聊天页『展开采样参数』里的灰显说明」，
面板搬进 sheet 后该指代已失效 → 改为「见聊天页参数 chip 打开的『采样参数』里的灰显说明」。


### R4 交付说明（2026-09-27，✅ 完成）

评测页密度重构 + 厂商 logo 资产接入 + 空态统一 + P2 打磨，共 **13 个改动文件 + 3 个新增**（含 13 个 logo 资源）。

| 文件 | 改动 |
|---|---|
| `ui/benchmark/BenchmarkScreen.kt` | ①评测页密度重构（本节①） |
| `ui/components/VendorLogo.kt`（新增） | ②厂商 logo 组件 + 映射表 |
| `res/drawable-nodpi/*_icon.webp`（新增 13 个） | ②logo 资源，96.5KB |
| `scripts/shrink_vendor_logos.py`（新增） | ②压缩脚本（1024px → 192px WebP q88, method=6） |
| `ui/components/UiComponents.kt` | 新增 `DroidCard`、`EmptyState`，`formatModelSize` 沿用 |
| `ui/models/ModelsScreen.kt` | 市场条目挂 logo；本地列表空态；路径加复制按钮；3 处裸 `Card` → `DroidCard` |
| `ui/benchmark/BenchmarkScreen.kt` | 3 处裸 `Card` → `DroidCard` |
| `ui/settings/SettingsScreen.kt` | `SectionCard` 内 `Card` → `DroidCard` |
| `ui/chat/ChatScreen.kt` | 空态改 `EmptyState`（带"去模型页"按钮）；标签 `temperature` → `temp` |
| `ui/DroidLlmRoot.kt` | 抽出 `goToModels`，Chat 与 Benchmark 共用 |
| `ui/models/FileBrowser.kt` | 文件行 `heightIn(min = 44.dp)` |
| `ui/theme/Color.kt`+`Theme.kt` | 新增 `Dark/LightOutlineVariant`，`outlineVariant` 指向它 |
| `ui/theme/Type.kt` | `bodySmall` 11sp → 12sp |
| `docs/LICENSING.md` | 新增 §2④ logo 商标条目 + 合规清单一条 |

**① 评测页「开始评测」回首屏**（对应 `UI_REVIEW` §4.2「评测页"开始评测"在首屏外」）

计划写的是"置顶『选模型』卡 + 紧凑引擎行"，**实际做法不同**：做成 **每个引擎一行**，行内自带模型下拉，
不另设置顶卡——因为选模型本来就只对**已勾选**的引擎有意义，抽成独立卡片反而把"引擎 ↔ 模型"的从属关系藏了
（与 R3 作用域条同一条理由）。一行 52dp，右侧按四态给不同内容：

| 状态 | 右侧内容 |
|---|---|
| 已勾选且引擎有模型 | `⌄` 图标（整行开启 `ExposedDropdownMenuBox`，下拉底部有「去『模型』页添加更多」） |
| 已勾选但引擎无模型 | 灰字"该引擎还没有模型" |
| 未勾选但引擎可用且无模型 | 紫字「去「模型」页」，`clickable + padding(h 8, v 8)`（**不用 `TextButton`**，见踩坑③） |
| 引擎不可用 | `availabilityLabel`（`MissingDependency` 等） |

引擎区由「每引擎一卡 + 内嵌下拉」（约 **540dp**）压到「一卡 4 行」（约 **210dp**）；再把「开始评测」从
`ParamsSection` **之后移到之前**（参数是可选高级项，不该挡主操作），页间距 `vertical 12 → 8`、
`spacedBy 12 → 8`，banner 与参数摘要各压成一行（`"建议插电、静置冷却；>42℃ 仅警告，不中断"` /
`"warmup 1 · runs 3 · tokens 128（共 4 次）"`）。**最终 banner 显示时按钮也完整在首屏**（模拟器实测）。

**② 厂商 logo 资产接入**（决策 3）

- 组件 `VendorLogo(vendor: String?, size: Dp = 40.dp)`：`vendor` 命中映射表 → `Image(painterResource)`（8dp 圆角）；
  未命中 → 首字母方块（`surfaceContainerHigh` 底 + `outlineVariant` 描边）。**只挂在模型市场条目**上。
- 映射 **13 家**（按 catalog `vendor` 字段精确小写拼写做 key）：`qwen` / `smol` / `gemma` / `deepseek` /
  `llama` / `hunyuan` / `thudm`(→ChatGLM) / `minicpm` / `internlm` / `gpt`(→OpenAI) / `01.ai`(→Yi) /
  `baichuan` / `phi`。覆盖约 **109/162 ≈ 67%**（按条数，Qwen 一家 63 条）。
- **为什么不做子串推断**：`LocalModel` 没有 `vendor` 字段（引擎只知道 `ModelLocation` 路径），已导入列表只能靠名字猜，
  那会把 `TinyLlama` 标成 Meta Llama、把 `Google` 标成 Gemma——**宁可回落文字也不误标商标**。
  市场条目有精确 `vendor`，所以只接在市场（`UI_REVIEW` §5.1 第 8 条已据此更正原「74% 命中」的说法）。
- 压缩：源 17 个文件约 **913KB**（`smolm_icon.png` 单张 417KB）→ 取 13 个转 **192px WebP q88**，
  合计 **96.5KB**（`smolm` 417KB → 12.5KB）。脚本 `scripts/shrink_vendor_logos.py` 读 `MNN_LLM_CHAT_ROOT`，
  输出直接落 `app/src/main/res/drawable-nodpi/`（**放 `scripts/` 不是 `build/`——后者在 gitignore 里**）。
- 合规：`docs/LICENSING.md` §2④ 记来源/用途/权利归属（各厂商商标，Apache-2.0 不覆盖商标）+ 合规清单一条。

**③ 空态统一为「图标 + 一句说明 + 按钮」**（模式 6）

新增 `EmptyState(icon, text, actionLabel?, onAction?)`（44dp `outlineVariant` 图标 + 居中 `bodyMedium` + 可选 `PrimaryButton`），
三处接入：

| 位置 | 内容 | 动作 |
|---|---|---|
| Chat 空态 | "先在「模型」页添加模型，再回到这里启动它" | Filled「去「模型」页」 |
| Models 本地列表空 | "还没有模型。可从模型市场下载，或用上面的卡片导入本地文件。" | Filled「浏览模型市场」（切到市场 Tab） |
| Benchmark | **不收进空态**，改为引擎行的「去「模型」页」文字入口 | 顺带修掉原来的中英混排 `去 Models 页` |

Chat 与 Benchmark 的跳转共用 `DroidLlmRoot` 抽出的 `goToModels`，避免两处各写一遍导航。

**④ P2 打磨**（4 项，逐条对应 `UI_REVIEW` §4.3）

| 项 | 落地 |
|---|---|
| `bodySmall` 偏小 | 11sp → **12sp**；`labelSmall` 保持 11sp 作脚注 |
| 浅色卡片浮不起来 | **改做法**：实测 `#EEEAF8` 卡片 vs `#F6F5FB` 背景只有 **1.09:1**（相对亮度 0.841 / 0.920），改底色无法可辨 → 新增 `outlineVariant` token（深色 `#3A3A42` / 浅色 `#D5CFE8`），靠 **1dp 描边**拉开层次 |
| 长路径无法复制 | 页内 `CopyPathButton`（`ClipboardManager` + Toast「已复制」），根目录行与模型条目路径各挂一颗 |
| 点击目标不足 44dp | `FileBrowser` 文件行 `heightIn(min = 44.dp)` + 垂直居中 |
| 术语漂移 | Chat 字段标签 `temperature` → `temp`（与参数 chip、`UI_DESIGN` §5.4 统一） |

`DroidCard` 顺带统一了三个页面的裸 `Card`（`ModelsScreen` ×3 / `BenchmarkScreen` ×3 / `SettingsScreen` ×1），
把"卡片底色 + 圆角 + 描边"收成一处，避免下个页面又各写一套。

**踩坑（三条，已回写 `UI_DESIGN.md` §4.5）**

1. **Kotlin 块注释可嵌套**：KDoc 里写 `res/drawable-nodpi/*_icon.*` 时那个 `/*` 会开启嵌套注释 →
   `Unclosed comment`，且**报错行号指向文件末尾**（不是出错行），照行号找只会白费时间。
2. **`TextButton` 会把行高撑起来**：引擎行内第一次用 `TextButton` 放那个跳转入口，
   行高由 52dp 涨到 63dp，又把这颗按钮挤出首屏 → 改用 `clickable + padding(h 8, v 8)` 的 `Text`。
3. **浅色细描边的可辨性只能靠实测**：不要凭"加个边框应该行"下结论，算一下 WCAG 相对亮度再定方案。

**验证**：`:app:assembleDebug` **BUILD SUCCESSFUL**；`:core:engine-api` / `:core:common` 单测全绿。

**模拟器实测**（`emulator-5554`，1080×2400，截图 `build/uicheck/r4_0*.png`）：

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 评测页引擎区高度 | 4 卡 → 1 卡 4 行，约 540dp → **210dp** |
| 2 | **★「开始评测」在首屏** | 默认与 banner 显示时**均完整可见**（两次迭代：先压间距只露出顶边，再把按钮移到参数之前才彻底） |
| 3 | 引擎行四态 | 勾选+有模型 → `⌄`；未勾选+无模型 → 紫字「去「模型」页」；不可用 → `availabilityLabel`；行高 52dp |
| 4 | 行内下拉 | 底部含「去「模型」页添加更多」，与工具栏入口行为一致 |
| 5 | 市场条目 logo | `qwen_icon` 等命中项显示圆角图；未命中厂商显示首字母方块（**无崩溃、无空白**） |
| 6 | 本地列表空态 | `EmptyState`（文件夹图标 + 说明 + Filled「浏览模型市场」），点击切到市场 Tab |
| 7 | Chat 空态 | 同款 `EmptyState`，Filled「去「模型」页」跳到模型页 |
| 8 | 路径复制 | 根目录行与模型条目路径各一颗图标按钮，`IconButton` 32dp + 16dp 图标 |
| 9 | 卡片描边 | 深色主题下卡片与背景可辨（1dp `outlineVariant`） |
| 10 | 术语统一 | Chat 参数 chip 与采样 sheet 字段标签均为 `temp` |

**未验证（模拟器不可及，待真机 arm64）**：logo 在 3x 高密度屏的清晰度（192px 源 → 显示 40dp，
在 3x 屏需 120px，余量够但要看实际观感）、浅色主题描边的观感、市场长列表滚动时 logo 的解码开销。
**R2 的跟随滚动与结果表、R3 的手势手感**同样仍待真机（`docs/mnn.md` §6.1）。

**未做（明确记录，不是遗漏）**：聊天页 `titleLarge` 标题（会挤掉消息区，`UI_REVIEW` §4.3 建议不做）、
消息长按复制（属**新功能**，不在"打磨"范围）、评测结果"行展开看样本"（`UI_REVIEW` §4.1 遗留，仍未实现）。



### 参考实现（本机源码，只借交互不借实现）

`MNN_LLM_CHAT_ROOT=D:\3rd-party-projects\MNN\apps\Android\MnnLlmChat`（Java + XML View；我们是 Compose）

| 借鉴点 | 源文件 |
|---|---|
| 顶栏中央单入口切换器 | `res/layout/view_model_switcher.xml` + `widgets/ModelSwitcherView.kt` |
| 模型选择 BottomSheet + 条目 | `res/layout/fragment_choose_model.xml`、`list_item_model_selection.xml` |
| 复合输入卡（预览 + EditText + 一行按钮） | `res/layout/activity_chat.xml:86-305` |
| 自动滚动 + 悬浮回底部 | `res/layout/activity_chat.xml:306-329`、`chat/chatlist/ChatListComponent.kt:216,228-229` |
| 参数 BottomSheet + 显式保存 | `res/layout/fragment_settings_sheet.xml` |
| 数值输入中间态处理 | `modelsettings/NumericInputParser.kt` |
| 统一空态 | `res/layout/chat_layout_empty_view.xml`、`fragment_modellist.xml` |
| 评测页置顶"选模型"卡 | `res/layout/fragment_benchmark.xml` |
| 引擎 chip（筛选） | `res/layout/chip_filter_item.xml` |
| **厂商 logo 资产**（决策 3） | `res/drawable-nodpi/*_icon.{png,webp}`（17 个，约 900KB） |
| 厂商匹配逻辑（子串兜底） | `model/ModelUtils.kt:81+`（`getDrawableId`）、`model/ModelVendors.kt` |

### 验收（每步独立）

- R1 / R2：`gradlew :app:assembleDebug` BUILD SUCCESSFUL；四个界面同一引擎显示一致（**截图比对**）；`Settings` 改 `0.7 → 0.75` 不再吞掉中间态；生成长回复时列表持续跟到底部。
  - R1 的"同一引擎显示一致"**已截图验证**；R2 的数值框**已在模拟器验证**（含半截状态不污染存储）；
  - R2 剩下的**跟随滚动**（需流式回复）与**结果表单位/行高**（需跑一次评测）**必须真机 arm64**——模拟器跑不了评测，见 `docs/mnn.md` §6.1。
- R3：顶栏单入口可开两级弹层（引擎 chip 状态点三态正确）；**选引擎/模型不触发自动 load**（`DESIGN §1.2` 回归）；
  采样 chip 点击开 sheet；每屏最多一个 Filled 主操作；`UI_DESIGN.md` 已补 §4.4 控件三层职责。
  - **模拟器已验证 1–11 项**（见「R3 交付说明」实测表，含 `DESIGN §1.2` 回归）；真机只需复核视觉与手势
    手感（sheet 拖动、chip 横滚）；跟随滚动与结果表仍待真机。
- R4：评测页「开始评测」在首屏；模型条目厂商 logo 命中/兜底均正常；`LICENSING.md` 已记 logo 商标条目。
  - **模拟器已验证 10 项**（见「R4 交付说明」实测表，含 banner 显示时按钮仍在首屏、logo 命中和兜底均不崩）；
    真机只需复核 logo 清晰度与浅色描边观感。

> **§8d 收尾结论**：代码侧 R1–R4 全部完成，构建 + 单测全绿。**唯一未闭环的是三项真机 arm64 复核**
> （跟随滚动 / 结果表；sheet 与 chip 手感；logo 与描边观感）——模拟器跑不了真实流式与评测，属环境限制。

### 决策状态

✅ **4 条已全部拍板（2026-09-27）**，见上方「已拍板决策」表，明细依据在 `UI_REVIEW.md` §7。
✅ **R1 已完成**（构建 + 单测全绿，提交 `1e45553`）；**R1.5** `bbe77f2`；**R2** `d48daee`；**R3** `37c368d`。
✅ **R1.5 已完成**（Models 三行 chip；模拟器实测三行文字全部单行）。
✅ **R2 已完成**（5 项 UI 小改；模拟器验证 ①数值框 ④backend 下拉，②③⑤待真机）。
✅ **R3 已完成**（三层控件 + 顶栏作用域条 + 两级 sheet；模拟器实测 11 项，含 `DESIGN §1.2` 回归）。
✅ **R4 已完成**（评测页密度重构 + 13 个厂商 logo〔96.5KB〕+ 空态统一 + P2 打磨；模拟器实测 10 项）。
**当前进度：R1 ✅ → R1.5 ✅ → R2 ✅ → R3 ✅ → R4 ✅ —— §8d 全部完成（2026-09-27）**。
**剩余只差真机 arm64 复核**（代码侧无待办）：R2 的②跟随滚动 / ③⑤结果表，R3 的 sheet 与 chip 手感，
R4 的 logo 在高密度屏的清晰度与浅色描边观感 —— 模拟器跑不了真实流式与评测（`docs/mnn.md` §6.1）。
**R3 遗留已闭环**（2026-09-27 收尾）：`UI_DESIGN.md` §4.4 把"每个列表行都挂一个 Filled"列为反例，而模型市场
原本每条目仍挂一颗 Filled「下载」/「已下载 · 添加到列表」（R3 只收敛了「刷新状态」那一颗）→ 已降为
`OutlinedToolButton`，市场页 Filled 数为 0；同步修正 §4.4 与 §5.2 措辞（§5.2 原写"下载是唯一 Filled"，自相矛盾）。

### R5 交付说明（Thinking 开关 + 回答截断 + 注册模型，2026-09-27）

**① Thinking 开关**（对话框附近，`UI_REVIEW` §3.2 模式 3）

| 项 | 落地 |
|---|---|
| UI | `Composer` 参数 chip 同行 `FilterChip`（`Thinking 开/关`）；仅模型支持时显示 |
| 契约 | `InferenceConfig.enableThinking`（默认 true）+ `ConfigField.THINKING` |
| 检测 | `ThinkingSupport`（名字：qwen3/thinking/r1 等）+ MNN `templateHasEnableThinking`（读 chat_template） |
| MNN | `set_config` 写 `jinja.context.enable_thinking`（见 `docs/mnn.md` §5） |
| 其它引擎 | Qwen3 系用户消息尾附 `/think` `/no_think`（MNN 不用后缀，避免双重控制） |

**② 回答显示不全**

- **不是 UI 限制**：`MessageBubble` 的 `Text(msg.content)` 无 `maxLines`，长回复可完整换行滚动。
- **是生成长度不够**：`maxNewTokens` 默认 **128** 太小；Thinking 模型还会把额度吃在 `<think>` 上。
  - 默认值 **128 → 4096**（对齐并超过 MnnLlmChat 的默认 2048 / 上限 4096；`InferenceConfig` / `AppSettings`）。
  - 达上限时气泡尾注「已达 maxNewTokens=N 上限…」+ 状态行「达 maxNewTokens 上限」。

**③ 导入 → 注册（外部模型保留原路径）**

| 项 | 落地 |
|---|---|
| 术语 | 「导入」→「注册」（Tab「已注册」、卡片「注册外部模型」） |
| 行为 | 外部模型**只登记、不搬文件**；删除「导入并复制到模型目录」 |
| 主操作 | 「注册模型（保留原路径）」= 唯一 Filled |
| 例外 | 仅市场下载严格走模型根目录 `{hf\|modelscope}/models--org--repo/snapshots/` |

**验证**：`:core:engine-api` / `:core:common` 单测 + `:app:assembleDebug` 全绿。

---

## 8e. P7：OpenAI 兼容 API 服务器（A1–A4 已实现）

**状态**：🔄 A1–A4 ✅（2026-09-28），A5（Anthropic）未做。方案见 [`docs/API_SERVER.md`](docs/API_SERVER.md)。

| 项 | 摘要 |
|---|---|
| 范围 | 手机内嵌 HTTP Server，对外暴露 OpenAI 兼容 `/v1/chat/completions` + `/v1/models` |
| 选型 | Ktor 3.1.3 + **CIO**（非 Netty）+ 单飞队列 + 前台 Service |
| 模块 | `:core:apiserver`；桥接 `DefaultApiInferenceBridge`（`:app`） |
| 入口 | Settings →「API 服务器」；`ApiForegroundService` 前台服务 |
| 鉴权 | Bearer / x-api-key，默认开，Key 随机 16 位（DataStore `droid_api_server`） |
| 流式 | SSE `chat.completion.chunk` + `[DONE]`；客户端断开 → `GenerateJob.cancel` |
| 红线 | 不改 `LlmEngine` 契约；首期纯文本；API 与 UI 聊天互斥使用会话 |

**A1–A4 交付**

1. `:core:apiserver`：`ApiServer`（Ktor CIO）、`ApiServerRoutes`、`RequestQueueManager`（单飞 FIFO）、`OpenAiDtos`/`OpenAiFormatter`、`ApiInferenceBridge`
2. `:app`：`DefaultApiInferenceBridge`（LlmEngine 会话）、`ApiServerPreferences`/`ApiServerConfigStore`、`ApiForegroundService`、Settings UI 分区
3. 单测：`ApiProtocolTest`（请求解析/拒 image/格式化）、`RequestQueueManagerTest`（FIFO/单飞/失败不堵队）
4. 验证：`:core:apiserver:testDebugUnitTest` + `:app:assembleDebug` 全绿

**真机 DoD（待手测）**

1. Settings 开启 API 后 `curl http://127.0.0.1:8080/v1/models`（或 adb forward）返回模型列表
2. 非流式 / 流式 `/v1/chat/completions` 调通；错误 Key 401；image content 400
3. 断开流式后可继续下一请求；通知栏可停止服务

---

## 9. P8：中英双语（i18n）与首个正式版（2026-09-30，✅ 构建/单测通过；真机切换待测）

### 需求
1. 界面支持中英文双语，设置里加语言选项
2. 出正式版 APK，用环境变量签名，不泄露口令

### 交付
- 语言三态（跟随系统 / 简体中文 / English）落 DataStore，设置页新增「语言」分组
- 全量抽取：413 处硬编码中文 → 376 条唯一文案，分布见 [`docs/I18N.md`](docs/I18N.md)
  （`app` 359 / `core:benchmark` 9 / `core:engine-api` 8 / `engine:genie` 16）
- `values/` = 英文兜底，`values-zh/` = 简体中文；库模块各带一份 `res/`（看不到 app 的 `R`）
- 顺带修掉一处脆弱判定：`message.startsWith("校验失败")` → ViewModel 显式下发 `messageIsError`
- 正式版签名：`dist/droidllm-0.1.0-P0-release.apk`（versionCode 1 / `0.1.0-P0`），
  `apksigner verify` 通过，证书 `CN=pisces312`

### 验收
- `:app:assembleDebug` ✅ / `:app:assembleRelease` ✅（2m）
- `:core:engine-api:testDebugUnitTest` + `:core:common:testDebugUnitTest` + `:core:benchmark:testDebugUnitTest` ✅
- `aapt2 dump configurations` 含 `zh` ✅
- **真机切换待测**：本次 `adb devices` 为空（无线 adb 未连），未做界面验证

### 审阅后修复（2026-09-30）
- `AppLocale`：`SYSTEM` 时恢复设备 `LocaleList`（原先 `LocaleList.setDefault` 会卡在 EN/ZH）
- 新增 `ChatUiPersist`：`recreate()` 换语言不再丢聊天记录/会话句柄
- `docs/I18N.md` §2 机制描述改回与代码一致（Application **不** wrap；切换走 `recreate()` 而非重指 `LocalContext`）
- 中文占位符 `%s`→`%d` 与英文对齐（25 条）；英文 UI「Chinese」→「Chinese (Simplified)」
- 补 `AppLanguageTest`

### 机制推翻 + 导航死循环修复（2026-09-30，本条推翻了上一节）
上一节给 `AppLocale` 打的补丁整体作废 —— 语言改回官方 AppCompat app-locale 机制。

**故障**：UI 为英文时点「模型」标签，画面持续闪烁，来回切几次应用退出。
**根因**：为「跨 `recreate()` 记住当前 tab」引入的 `object UiRoute`，被当作
`NavHost(startDestination = UiRoute.current)` 传入。该参数是 NavHost 内部
`remember(route, startDestination, builder)` 的 key，而实现体里直接 `navController.graph = graph`
→ 值一变就重建 NavGraph、拆掉回退栈、重启过渡动画，重启又触发下一轮重组：
**每帧循环、永不收敛**。实测空闲 4s 内 +26 帧（健康态 0）、每 4s 5~7 次 ~25MB GC、
PSS 239MB→287MB 涨到 OOM 被杀。次生伤害：起点被改后 `popUpTo("chat")` **静默失效**。

**改动**：
- 导航层：删 `UiRoute`；`startDestination` 恢复常量；`popUpTo` 改
  `graph.findStartDestination().id`；路由集中到 `ui/DroidLlmRoot.kt` 的 `AppRoutes`，
  切 tab 统一走 `NavHostController.switchTab`。
- i18n：`locale_config.xml` + manifest `android:localeConfig` +
  `AppLocalesMetadataHolderService{autoStoreLocales=true}`（API ≤32）+ `app/AppLanguageController`；
  `MainActivity` → `AppCompatActivity`，主题父级 → `Theme.AppCompat.DayNight.NoActionBar`；
  删除 `AppLocale` / `AppLanguageStore` / 两处 `attachBaseContext` / app 侧 `recreate()`。
  `AppSettings.language` → `legacyLanguage`（只读，仅供 `migrateLegacy` 一次性迁移）。
- 护栏：新增 `app/src/androidTest/.../NavigationRecreateTest.kt` —— recreate 后静置 3s 统计窗口
  实际重绘次数（`OnDrawListener`，非 vsync）≤ 10，另有一条阳性对照证明计数器不是瞎的。
- 文档：`docs/I18N.md` §1/§2 重写（含两条硬规则）、`AGENTS.md` 文档表与「关键契约」补导航不变量。

**验收（模拟器 pixel6 / API 34 / 应用英文）**：
- 复现序列 ×3（Chat→模型→设置后静置 4s）：**frames +0、GC 0**，全部通过（故障期为 +26 帧 / 5~7 次 GC）
- `uiautomator dump` 不再出现两个页面的文案叠在同一屏
- 三态语言：`简体中文 → [zh]`、`English → [en]`、`跟随系统 → []`，每次**都停在设置页**，
  且 `cmd locale get-app-locales` 证实走的是系统 `LocaleManager`（Android 13+ 系统设置入口成立）
- 杀进程冷启：仍为所选语言；0 崩溃
- 构建/测试：`:app:assembleDebug` ✅、`:app:assembleDebugAndroidTest` ✅、
  `:app:connectedDebugAndroidTest`（NavigationRecreateTest 2/2 ✅）、
  `:core:engine-api` / `:core:common` 单测 ✅
- **未做**：真机验收（`adb devices` 无真机）；`ChatUiPersist` 的存在理由存疑但未实测（见 `docs/I18N.md` 已知残留）

---

## 10. 执行者注意事项（坑位速查）

1. **GitHub 直连不稳**：submodule/大文件优先 `gh-proxy.com` 镜像；失败重试前先 `rm -rf` 残留目录
2. **符号冲突是头号风险**：每接入一个引擎立刻跑共存 smoke test，不要攒到 P3
3. **模型路径一律真实路径**：SAF 已降级为可选（DESIGN §1.3），不引入 `content://` 反解负担；内置文件浏览器基于 `java.io.File`，前提是 `MANAGE_EXTERNAL_STORAGE`（无运行时弹窗，只能跳系统设置页授权）；Android 11+ 该权限也读不了其他 App 的 `Android/data/`，浏览器需灰显
4. **计时口径统一**：TTFT 从请求发出到首个 token 回调，不含模型加载和模板格式化；各适配器不得自行其是
5. **Genie 只支持骁龙 HTP**：开发机/模拟器上必须优雅降级，所有 P0–P2、P4 工作不依赖 Genie 可用
6. **不要扩大范围**：功耗测量、Dynamic Feature、雷达图、质量评测均明确不做。OpenAI 兼容 API 为 P5+ 可选增强，方案见 [`docs/API_SERVER.md`](docs/API_SERVER.md)，仅在显式启动 P7 时实施，勿夹带进 P0–P6
7. **目录改名**：仓库建立后工作目录可从 `LlmChatAndroid` 改为 `droid-llm`，改名时同步 `DESIGN.md` 头部说明
8. **第三方路径**：一律走环境变量（`MNN_ROOT` / `QAIRT_PATH` 等，见 AGENTS.md「第三方仓库」表），禁止把 `D:\...` 写进仓库
9. **评测只在真机 arm64 上跑，别在模拟器上试**：模拟器（x86_64 + 2GB RAM + native bridge 翻译）点「开始评测」会卡死在 `llm->load()`——`createLLM` 之后无任何 native 日志、进程 CPU 0%、无崩溃/OOM，只能 `adb shell am force-stop` 恢复。**这是环境限制不是代码 bug**，在模拟器上排查评测流程纯属浪费时间。模拟器仍可用于**纯 UI 布局**验证（Compose 不碰 native）。详见 `docs/mnn.md` §6.1
