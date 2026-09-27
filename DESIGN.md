# droid-llm 多引擎统一 App + Benchmark 方案

> 目标：构建一个 Android App（项目名 **droid-llm**，仓库 `droid-llm`，工作目录 `D:\my-projects\droid-llm`），**同时接入四种端侧 LLM 引擎**，在同一个聊天界面里切换使用；附带一个**轻量 benchmark**，用于对比各引擎在本机的速度表现。
>
> 应用显示名 **DroidLLM**，包名 `io.github.pisces312.droidllm`。
>
> 定位差异：最接近的同类项目是 [PolyEngineInfer](https://github.com/FilipFan/PolyEngineInfer)（llama.cpp/ONNX/ExecuTorch/LiteRT 四引擎实验性 App），但它无 Genie(NPU)/MNN、无独立 benchmark、对话无状态。droid-llm 的差异即：NPU 引擎聚合 + 可控评测 + 多轮对话。
>
> 已确认：四个参考项目（gallery / MnnLlmChat / chatapp_android / ChatterUI）在你的真机上均可正常运行。

---

## 0. 四引擎现状（调研结论）

| 维度 | Google LiteRT-LM | 阿里 MNN-LLM | 高通 Genie (QNN) | llama.cpp |
|------|------------------|--------------|------------------|-----------|
| 参考项目 | `gallery` | `MNN/apps/Android/MnnLlmChat` | `ai-hub-apps/chatapp_android` | `ChatterUI` |
| 技术栈 | Kotlin + AAR | C++/JNI + libMNN.so | C++/JNI + libGenie.so | C++/JNI + libllama.so |
| 依赖 | `com.google.ai.edge.litertlm:litertlm-android:0.11.0` | `MNN_SOURCE_ROOT` 预编译 so | QAIRT/QNN SDK **2.50.0.260828**（`QAIRT_PATH`，对齐 local-dream；原稿 2.45 已被本机 SDK 取代） | 源码 CMake / 绑定 |
| 模型格式 | `.litertlm` / `.task` | 目录：`config.json` + `llm.mnn`(+分片) | `*.bin`(ctx) + `genie_config.json` + `tokenizer.json` | `.gguf` |
| 加速后端 | CPU / GPU / NPU | CPU / OpenCL (+QnnModule) | **仅 HTP/NPU**（骁龙） | CPU / OpenCL / HTP |
| 指标 | TTFT / prefill&decode tps | prefill&decode tps | TTFT / decode tps（`MetricsCollector` 统一口径；GenieProfile 仅参考） | prompt/predicted tps |

**关键结论**

1. **模型格式不互通** → 不做「一份模型跑四家」的硬绑定；**每个引擎独立配置自己的模型文件/目录**。
2. **API 形态差异大** → 抽统一 `LlmEngine` 接口，四个适配器分别对接。
3. **Native so 可能符号冲突** → 分 Gradle module 隔离；单 APK 全打即可。
4. **Genie 仅骁龙 HTP** → 做成可选引擎，不可用时 UI 灰显并说明原因，不崩溃。
5. **评测目标务实化**：不是学术极限分，而是「同一台手机上，哪个引擎/模型组合日常更顺」。

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        :app (UI Shell)                      │
│      Chat  │  Models(路径配置)  │  Benchmark  │  Settings    │
└────────────┬────────────────────────────────────────────────┘
             │ 仅依赖 engine-api + common
┌────────────▼────────────────────────────────────────────────┐
│              :core:engine-api  （纯 Kotlin 接口）             │
│   LlmEngine / InferenceConfig / GenerateRequest / Metrics   │
└────────────┬──────────┬──────────┬──────────┬───────────────┘
             │          │          │          │
   ┌─────────▼──┐ ┌─────▼────┐ ┌──▼───────┐ ┌▼────────────┐
   │:engine:    │ │:engine:  │ │:engine:  │ │:engine:     │
   │litert      │ │mnn       │ │genie     │ │llamacpp     │
   │LiteRtEngine│ │MnnEngine │ │GenieEng. │ │LlamaCppEng. │
   └─────┬──────┘ └────┬─────┘ └────┬─────┘ └──────┬──────┘
         │             │            │               │
   litertlm AAR   libMNN.so    libGenie.so     libllama.so
                  + JNI        + JNI           + JNI
                               + libQnnHtp*    + (可选 HTP/OpenCL)
┌─────────────────────────────────────────────────────────────┐
│  :core:common — ModelPathStore / MetricsCollector /         │
│  DeviceProbe / ResultStore (Room)                           │
└─────────────────────────────────────────────────────────────┘
```

### 1.1 模块划分

| 模块 | 职责 | 依赖 |
|------|------|------|
| `:app` | Compose UI、导航、DI | api/common + 各 engine |
| `:core:engine-api` | 统一引擎接口、数据类、错误类型 | 轻依赖 |
| `:core:common` | 模型路径配置、指标、设备探测、结果库 | engine-api |
| `:core:benchmark` | 轻量评测调度、结果聚合 | engine-api, common |
| `:engine:litert` | LiteRT-LM 适配器 | litertlm-android |
| `:engine:mnn` | MNN 适配器 + JNI | CMake→libMNN |
| `:engine:genie` | Genie 适配器 + JNI | CMake→libGenie + QAIRT |
| `:engine:llamacpp` | llama.cpp 适配器 + JNI | CMake→libllama |

**包体策略：单 APK 全打（arm64-v8a only）**，实测 **≈143 MB**（不含模型；含 QNN HTP 全 arch 运行时，可按目标 SoC 裁剪）。模型一律外置存储，不进 APK。

> **关于 Dynamic Feature（动态功能模块）**：Play Feature Delivery 的一种发布方式，把 App 拆成「基础模块 + 可选插件模块」，用户安装时只下基础包，进到某功能时再按需下载插件（例如 `:engine:genie` 单独一个包）。好处是首装包小；代价是构建复杂、调试麻烦、离线侧载（adb install）不友好，而且对 benchmark 场景不友好（引擎应常驻可比）。
> **本项目直接不做 DFM**，四个引擎全部编进单 APK；若以后包体真成问题再考虑。

### 1.2 统一引擎接口（核心）

```kotlin
enum class EngineId { LITERT, MNN, GENIE, LLAMACPP }

enum class Backend { CPU, GPU, OPENCL, NPU_HTP, AUTO }

data class InferenceConfig(
    val maxNewTokens: Int = 128,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val backend: Backend = Backend.AUTO,
    val seed: Long? = null,
    val systemPrompt: String? = null,
)

data class GenerateRequest(
    val messages: List<ChatMessage>,
    val config: InferenceConfig,
)

sealed class EngineEvent {
    data class Token(val text: String, val index: Int) : EngineEvent()
    data class Done(val result: GenerateResult) : EngineEvent()
    data class Error(val cause: EngineException) : EngineEvent()
}

interface LlmEngine {
    val id: EngineId
    val displayName: String

    suspend fun probe(probeContext: ProbeContext): Availability
    suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle
    fun generate(handle: SessionHandle, request: GenerateRequest, onEvent: (EngineEvent) -> Unit): GenerateJob
    suspend fun reset(handle: SessionHandle)
    suspend fun unload(handle: SessionHandle)
    fun lastMetrics(handle: SessionHandle): EngineMetrics?
}
```

| 统一概念 | LiteRT-LM | MNN | Genie | llama.cpp |
|----------|-----------|-----|-------|-----------|
| `load` | `Engine.initialize` | `Llm::createLLM` + `load` | `GenieDialog_create` | `llama_load_model` + new context |
| `generate` | `sendMessageAsync` | `Response()` 流式 | `GenieDialog_query` | decode 循环 |
| `reset` | 新 Conversation | 新 Prompt 会话 | `GenieDialog_reset` | `llama_kv_cache_clear` |
| 指标 | MetricsCollector（统一） | MetricsCollector（统一） | MetricsCollector（统一） | MetricsCollector（统一） |

**`Backend` 枚举的引擎映射**（`GPU` 与 `OPENCL` 有交集，按下表对齐；不支持的取值一律拒绝并明确提示，不做静默回退）

| Backend | LiteRT | MNN | Genie | llama.cpp |
|---------|--------|-----|-------|-----------|
| CPU | ✅ | ✅ | ❌（仅 HTP） | ✅ |
| GPU | ✅（GPU delegate） | ✅（映射 OpenCL） | ❌ | ✅（映射 OpenCL） |
| OPENCL | ❌ | ✅ | ❌ | ✅ |
| NPU_HTP | ✅（若机型支持） | 可选（QnnModule） | ✅（唯一） | 可选 |
| AUTO | 引擎自选 | 引擎自选 | = NPU_HTP | 引擎自选 |

**`InferenceConfig` 字段适用性**：并非所有字段对所有引擎生效（如 `threads` 对 Genie 无意义、各家 `topK` 默认值不同）。约定：不适用的字段**忽略并记 warning**，适配器在日志/指标中如实标注实际生效值，避免 benchmark 结果的参数列误导。

**Prompt / chat template 策略（按引擎落地）**

| 引擎 | 模板来源 | 说明 |
|------|----------|------|
| LiteRT-LM | AAR 内置 | 适配器不调 `ChatTemplate` |
| llama.cpp | `llama_chat_apply_template` | JNI 内原生模板 |
| MNN | `Llm::response(ChatMessages)` | 原生模板 |
| Genie | 模型目录 `metadata.json` → `genie.chat_template` 角色前后缀；缺省 `user:`/`assistant:` | 与 chatapp_android 一致；**minja JNI 推迟**（`:core:chattemplate` 仅 `ChatTemplate.format` fallback） |

**Session 线程安全契约**（写在 engine-api，而非各适配器自行处理）：同一 `SessionHandle` 上 `generate` 与 `unload`/`reset` 互斥；同一时刻至多一个 `generate` 在跑；`generate` 进行中调用 `unload` 必须阻塞等待或明确失败，不得崩溃。

### 1.3 模型路径：每引擎独立配置（重点调整）

**不要求**「同一基座 × 四份导出」。用户在手机存储里自己组织模型，App 只保存「哪个引擎 → 哪个路径」。

```
/sdcard/Android/data/<pkg>/files/models/     （或用户任意目录，经内置文件浏览器/直接路径）
├── litert/
│   └── qwen1.5b.litertlm
├── mnn/
│   └── qwen1.5b/            # config.json + llm.mnn(+分片)
├── genie/
│   └── qwen1.5b_genie/      # *.bin + genie_config.json + tokenizer.json
└── llamacpp/
    └── qwen1.5b-q4_k_m.gguf
```

**配置模型（Models 页）**

- 每个引擎一张卡片：当前已配置路径、格式校验结果、更换 / 浏览（内置文件浏览器）/ 清除
- 支持「收藏模型列表」：可给同一引擎存多个模型条目，聊天/Benchmark 时下拉选
- 路径来源：
  1. App 私有目录 `getExternalFilesDir("models")`（默认，免权限）
  2. 直接路径 `/sdcard/...` + **内置文件浏览器**（`java.io.File` 语义，需 `MANAGE_EXTERNAL_STORAGE`，引导跳系统设置页授权；未授权时仅可浏览 App 私有目录。注意 Android 11+ 即使有所有文件权限也读不了**其他 App** 的 `Android/data/`，浏览器中灰显）
  3. SAF `content://` URI：**降级为可选**（仅外部分享场景，P5 不强制实现）。native 引擎需真实路径，SAF 必须反解（参考 StreamClip `FileUtils.getPathResultFromUri()` 的四级 fallback）；目录型模型（MNN/Genie）fd 方案不可用，GB 级模型复制到私有目录不可接受
- **格式校验**（load 前快速探测）：
  - LiteRT：扩展名 `.litertlm` / `.task` 或文件头
  - MNN：目录内存在 `config.json` + `llm.mnn`
  - Genie：目录内存在 `genie_config.json` + `tokenizer.json` + `*.bin`
  - llama.cpp：`.gguf` 魔数

`LocalModel` 数据结构：

```kotlin
data class LocalModel(
    val id: String,              // uuid
    val engineId: EngineId,
    val displayName: String,     // 用户可改，如 "Qwen1.5B-Q4"
    val location: ModelLocation, // FilePath | SafUri | AppPrivate
    val formatHint: String?,     // "gguf" / "mnn_dir" / "genie_dir" / "litertlm"
    val fileSizeBytes: Long?,
    val quantHint: String?,      // 可选，用户标注
)
```

**Benchmark 时**：用户为每个待测引擎分别选择一个 `LocalModel`（可以是完全不同的基座/量化），App 只保证「测的是用户指定的那份文件」，并在结果里完整记录路径、文件名、大小、用户标注的 quant。

---

## 2. Benchmark（轻量版）

### 2.1 设计原则（简化后）

1. **用户自选模型**：每个引擎选一个已配置的模型，不要求同基座。
2. **只测速度与内存**，**不做功耗/电流采样**。
3. **热身 + 计时**：默认 1 次 warmup + 3 次计时，取中位数。
4. **一次一个引擎**，跑前提示插电、静置冷却（>42℃ 警告，不强制拦截）。
5. **跑某引擎前强制 unload 其他所有引擎的 Session**，保证 RSS 归因干净（见 §2.3）。

> 温度来源说明：使用 `ACTION_BATTERY_CHANGED` 的电池温度，**不是 SoC 温度**，仅作粗粒度热状态参考，不做精确热分析；若机型可读 thermal zone 则在 `extra` 字段附带 SoC 温度。
5. 原始数据落 Room，可导出 JSON。

### 2.2 用例套件（从 B0–B8 砍到 4 个）

| ID | 用例 | 输入 | 输出 | 指标 |
|----|------|------|------|------|
| L | Load | — | — | `load_ms`，加载后 `rss_mb` |
| P | Prefill | 固定 ~256 token 提示 | 16 token | `ttft_ms`，`prefill_tps` |
| D | Decode | 固定短提示 | 128 token | `decode_tps`，`per_token_ms` |
| T | TPS 持续 | 短提示 | 128 token × 3 轮 | 各轮 `decode_tps`（看是否掉速） |

> 用例开关默认勾选 L/P/D；T 为可选。
> 提示词内置 4 条可选（中文问答 / 英文问答 / 代码 / 总结），Benchmark 时单选。

### 2.3 采集指标（去掉功耗后）

| 类别 | 字段 |
|------|------|
| 性能 | `load_ms`，`ttft_ms`，`prefill_tps`，`decode_tps`，`per_token_ms_p50` |
| 资源 | `rss_mb_load`，`rss_mb_peak`（`/proc/self/status` 为进程级指标，须配合 §2.1-5 的独占策略；记录 baseline（加载前）→ 加载后 → 生成峰值三段 delta） |
| 环境 | `temp_c`（有则记），`timestamp` |
| 标识 | `engineId`，`modelName`，`modelPath`，`quantHint`，`deviceId/soc`，`sdk` |

### 2.4 交互流程

```
Benchmark 页
  1) 勾选引擎（默认全选，不可用的自动禁用）
  2) 每个勾选的引擎 → 下拉选择其已配置模型（或「去 Models 页添加」）
  3) 选择用例 L / P / D /（T）
  4) 设置轮数（默认 warmup=1, runs=3）与 maxNewTokens
  5) 开始 → 逐项进度条 + 实时 tps
  6) 结束 → 结果表（引擎 × 指标）+ 导出 JSON
```

### 2.5 结果表示例

| Engine | Model | Quant | Load(ms) | TTFT(ms) | Prefill tps | Decode tps | RSS peak |
|--------|-------|-------|----------|----------|-------------|------------|----------|
| MNN | qwen1.5b/mnn | w4a16 | 1200 | 180 | 320 | 38 | 2100 |
| llama.cpp | qwen1.5b-q4_k_m.gguf | Q4_K_M | 900 | 220 | 280 | 42 | 1800 |
| LiteRT | qwen1.5b.litertlm | f16 | 2100 | 260 | 250 | 30 | 3200 |
| Genie | qwen1.5b_genie/ | w4a16 | 1500 | 150 | 350 | 45 | 1900 |

> 表中 quant/模型列必须原样展示；跨模型/跨量化的数字**只作参考**，不写「A 引擎比 B 快」的绝对结论。

### 2.6 明确不做

- ❌ 功耗 / mAh / 电池电流采样
- ❌ 强制冷却锁、强制飞行模式
- ❌ 长上下文衰减曲线、多轮 KV 专项
- ❌ 质量评测（正确率/人工评分）—— 后续需要再加
- ❌ 复杂雷达图/对比图表 —— 先用表格 + 简单柱状图即可

---

## 3. 工程落地细节

### 3.1 Gradle / 构建

**工具链（与 Android 官方推荐一致，增量可跳过）**

| 层 | 工具 | 说明 |
|----|------|------|
| 工程/依赖 | **Gradle 8.13** + **AGP 8.13.2** | 配置缓存 `org.gradle.configuration-cache=true`；任务级 up-to-date / build cache |
| Kotlin | **2.2.21** + **KSP 2.3.6** | litertlm 0.11.0 要求 Kotlin metadata 2.3.0，2.1 读不了（见 P2） |
| Native | **AGP externalNativeBuild + CMake 3.22.1 + Ninja** | Android SDK 自带 `cmake/3.22.1/bin/ninja.exe`；生成 `build.ninja` / `.ninja_log`，按 `.cpp` 时间戳与依赖图增量编译 |
| NDK | **27.x**（`ANDROID_HOME`） | 仅 `arm64-v8a` |
| JNI 打包 | `jniLibs.useLegacyPackaging = true` | Genie/llama.cpp/MNN 需 so 落盘 dlopen |

**增量行为（已验证）**

- 未改动的模块/翻译单元：Gradle 标 `UP-TO-DATE`，Ninja 不重编；只重链受影响的 so
- 改 `*.cpp` / `CMakeLists.txt`：Ninja 只重编该 TU 并重链 `lib*_chat_jni.so`
- 改 Kotlin：只重编对应 module，不触碰 native
- 预编译 so 拷贝（`copyMnnJniLibs` / `copyQnnJniLibs`）：Gradle `Copy` 任务按输入哈希跳过；`onlyIf { !skipGenie }`
- 重配置触发：AGP `hash_key.txt` 变化（CMake 参数 / NDK / ABI）才重新 `cmake` configure

**工程约束**

- minSdk **31**，target 35
- `noCompress += ["bin", "json", "mnn", "gguf", "litertlm", "task"]`（仅预留：模型一律外置不进 APK，此配置只在将来做预置演示模型时才生效）
- Native 产物：
  ```
  engine/mnn/src/main/cpp/       → libmnn_chat_jni.so + libMNN.so
  engine/genie/src/main/cpp/     → libgenie_chat_jni.so + libGenie/libQnnHtp*
  engine/llamacpp/src/main/cpp/  → libllamacpp_chat_jni.so（静态编入 llama/ggml）
  engine/litert/                 → 纯 Kotlin + litertlm AAR
  ```
- Genie 跳过：`-PskipGenie=true` 或 `-Pdroid.skipGenie=true`；QAIRT 路径无效时自动跳过并 warn。跳过时 `excludes **/*.so`，Kotlin 仍编译（`probe()`→`MissingDependency`）
- QAIRT 路径解析顺序：`droid.qairtSdkRoot` → env `QAIRT_PATH` → env `QAIRT_SDK_ROOT` → 默认 `D:/dev/qairt/2.50.0.260828`
- 实测 APK ≈ **143 MB**（四引擎 + QNN HTP V68–V81 Skel/Stub；可按目标 SoC 裁剪）

**实现与原稿的偏差（以代码为准）**

| 原稿 | 实际 |
|------|------|
| Kotlin 2.x（P0 时 2.1.20） | **2.2.21** + KSP **2.3.6**（litertlm 约束） |
| QAIRT 2.45 | **2.50.0.260828**（本机 / local-dream 同款） |
| Genie 指标用 GenieProfile | **MetricsCollector 统一口径**；GenieProfile 未接 |
| minja chattemplate JNI | **推迟**：Genie 走模型 `metadata.json` 角色标签（chatapp 同款）+ `ChatTemplate.format` fallback |
| 包体 70–120MB | **≈143MB**（QNN 全 arch） |
| SAF `content://` 主路径 | **降级为可选**（§1.3）；P5 主路径 = 绝对路径 + 内置文件浏览器 + `MANAGE_EXTERNAL_STORAGE` |
| Settings 含 OpenAI 兼容 API（P5+ 可选） | **明确不做**，不在当前里程碑 |

### 3.2 能力探测（DeviceProbe）

启动时收集并缓存：

- `Build.SOC_MODEL`（Genie 选 HTP config 用）
- `Build.VERSION.SDK_INT`
- 是否存在 `libOpenCL.so` / `libcdsprpc.so`
- RAM / 可用存储
- QAIRT/Genie so 是否打进包（编译开关决定）

`LlmEngine.probe()` 返回 `Available / MissingDependency / UnsupportedSoc / ModelNotConfigured`，UI 显示具体原因。

### 3.3 线程与生命周期

- 每 Session 独立单线程推理队列（Genie/MNN 非线程安全）
- `unload` 与 `generate` 互斥（`@Volatile` + `synchronized`，参考 MnnLlmChat）
- 退后台暂停 benchmark；`onTrimMemory` 时卸载非活跃 Session
- **默认单模型驻留**：聊天切换引擎/模型即 unload 上一个 Session，同一时刻只有一个模型驻留内存（8GB 机型上两个 4bit 1.5B + 四家 so 已偏紧）；想多驻留的用户在 Settings 里显式放开

---

## 4. UI 信息架构

```
Home
├── Chat
│   ├── 引擎选择器（显示可用性）
│   ├── 模型选择器（该引擎已配置模型下拉）
│   ├── 流式对话（显示 TTFT / 本次 tps）
│   └── 采样参数面板（temp/top_k/top_p/threads/backend/max_tokens）
├── Models
│   ├── 四张引擎卡片：各自模型路径 / 添加(文件浏览器/路径) / 校验 / 删除
│   └── 同引擎多模型收藏
├── Benchmark
│   ├── 选引擎 × 每引擎选模型 × 选用例 × 轮数
│   ├── 进行中：进度、实时 tps
│   └── 结果表 + 导出 JSON
└── Settings
    ├── 默认采样参数
    ├── 后端偏好 / 线程数
    └── 数据目录与导出
```

技术栈：Jetpack Compose + Hilt + Coroutines/Flow + Room + DataStore。

> **视觉与交互的唯一权威**见独立文档 [`UI_DESIGN.md`](UI_DESIGN.md)（风格锚点 StreamClip + PixelPlayerOSS）；本文只约束信息架构。

---

## 5. 实施阶段（调整后更短）

| 阶段 | 内容 | 产出 | 估时 |
|------|------|------|------|
| **P0 脚手架** | 工程骨架、engine-api、FakeEngine、UI 导航、Models 路径配置 | 可安装空 App | 1–2 天 |
| **P1 双开源引擎** | `:engine:llamacpp` + `:engine:mnn` 完整接入；聊天流式通 | 两引擎可聊 | 3–5 天 |
| **P2 LiteRT** | `:engine:litert` | 三引擎可聊 | 1–2 天 |
| **P3 Genie** | `:engine:genie` + SOC 适配 + 门控 | 四引擎可聊 | 2–4 天 |
| **P4 轻量 Benchmark** | L/P/D/T 用例、结果表、JSON 导出 | 可出对比表 | 1–2 天 |
| **P5 打磨** | 校验、错误提示、文档 | 可交付 | 1 天 |

**里程碑**
- M1：两个引擎在同一聊天页可切换，模型路径可配置
- M2：四引擎矩阵页显示真实可用性，缺依赖不崩
- M3：一键跑 L/P/D，导出对比 JSON

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| QAIRT 获取/授权 | Genie 构建门槛 | `-PskipGenie`；真机已验证可跑，按参考工程脚本集成 |
| 四家 so 符号冲突 | 运行时崩溃 | 分 module 只是编译期隔离，运行时同进程仍可能撞 vendored 符号（protobuf/abseil/ggml 等）；对策：各 JNI 封装层编译加 `-fvisibility=hidden` + version script / `-Wl,--exclude-libs,ALL`，只导出 `Java_*`；**P0 阶段即做四 so 同进程顺序加载 smoke test**，尽早暴露而非 P3 才发现 |
| 模型路径指向 content:// 大文件 | 加载失败/泄漏 | SAF 已降级为可选（§1.3）；主路径为真实路径 + 内置文件浏览器（`MANAGE_EXTERNAL_STORAGE`），native 直读。若未来接 SAF：先反解真实路径（参考 StreamClip `FileUtils.getPathResultFromUri()`），失败才复制到 App 私有目录 |
| 各引擎 quant 不一致被误比 | 结论误导 | 结果表强制展示模型名+quant+路径；文档声明只作参考 |
| Genie 偶发空回复 | bench 中断 | reset + 有限重试，计入错误率 |
| APK 体积 | 安装负担 | arm64 only；模型外置；实测 ≈143MB（QNN 全 arch），必要时按 SoC 裁剪 Skel/Stub |

---

## 7. 从参考项目「偷师」清单

| 来源 | 直接复用 |
|------|----------|
| gallery | `LlmModelHelper` 抽象、`MetricsTracker`（TTFT/tps）、Compose/Hilt 骨架 |
| MnnLlmChat | `llm_session.cpp` 流式缓冲/UTF8 断包、power profile、生命周期竞态处理 |
| chatapp_android | Genie JNI 薄封装、HTP config 按 `SOC_MODEL` 选择、`useLegacyPackaging`、SAF 选模型 |
| ChatterUI | GGUF 头校验、`getContentFd`、backend 设备枚举、`CompletionTimings` 字段命名 |
| PolyEngineInfer | 本地副本 `D:\my-projects\references\PolyEngineInfer`（浅克隆，无 submodule）。可参考：<br>① **按模型路径自动路由引擎**（`ChatModelHelper.createEngineFromPath`：扩展名 gguf/task/litertlm/pte、目录特征 genai_config.json），可作为 Models 页校验通过后的默认行为；<br>② **独立 `chattemplate` 模块**（minja + nlohmann/json 走 JNI 做 Jinja chat template 格式化）——解决四家引擎 prompt 模板不一致的问题，droid-llm 可照抄这个模块划分；<br>③ **每引擎 module 带 androidTest + tiny 测试模型**（tiny-random-gpt2 / stories15M / tiny-mistral Q2_K），正是 §6 要求的 P0 四 so 同进程 smoke test 的落地形态；<br>④ `InferenceStats` 的 TTFT/prefill/decode 计时口径（注意其 TTFT 含模板格式化开销，我们应在 engine-api 层统一计时点）；<br>⑤ 其接口无 Session 概念、对话无状态——droid-llm 的 SessionHandle 多轮设计是差异点，不要退化 |

---

## 8. 目录规划

```
droid-llm/
├── DESIGN.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── app/
├── core/
│   ├── engine-api/
│   ├── common/
│   └── benchmark/
├── engine/
│   ├── litert/
│   ├── mnn/src/main/cpp/
│   ├── genie/src/main/cpp/
│   └── llamacpp/src/main/cpp/
├── scripts/
│   ├── build_native.ps1
│   └── export_benchmark.ps1
└── docs/
    ├── ENGINE_INTEGRATION.md
    └── MODEL_PATHS.md
```

---

## 9. 相对上一版的变更摘要

| 项 | 上一版 | 本版 |
|----|--------|------|
| 主目标 | 引擎 + 严格评测 | **一 App 多引擎聊天为主**，评测为辅 |
| 功耗测量 | 可选 mAh | **明确不做** |
| 评测用例 | B0–B8 | **L / P / D / T 四个** |
| 模型组织 | 强制同基座四格式矩阵 | **每引擎独立路径，用户自选模型** |
| 包体 | 可能上 Dynamic Feature | **单 APK 全打，不做 DFM** |
| 冷却/飞行模式 | 强制 | 仅提示 |
| 图表 | 雷达图等 | 表格 + 简单柱状 |
| 内存策略 | 未约定 | **默认单模型驻留**（切换即 unload） |
| Backend/参数适用性 | 未约定 | Backend 映射表 + 不适用字段忽略并 warning |
| RSS 归因 | 未约定 | benchmark 独占测试 + 三段 delta |
| 符号冲突对策 | 分 module | JNI 层符号隐藏 + P0 四 so 同进程 smoke test |

---

## 10. 默认决策（若无异议按此执行）

1. **llama.cpp**：自建 CMake + 薄 JNI（与 MNN/Genie 同构），不引入 React Native。
2. **包体**：单 APK，arm64 only，四引擎全打。
3. **模型**：默认根目录 `getExternalFilesDir("models")/<engine>/`；自定义路径走 `MANAGE_EXTERNAL_STORAGE` + 内置文件浏览器（真实路径直读）；SAF 降级为可选（仅外部分享场景）。
4. **Benchmark**：L/P/D 默认勾选，T 可选；warmup=1，runs=3；功耗不测。
5. **Genie**：集成但带门控；你的真机已验证可跑，按 chatapp_android 的 so/HTP 配置搬迁。
6. **单 App 合一**（而非保持四个独立 App 或扩展成熟项目）：合并的核心价值是**控制变量对比**（同机、同测量路径、同 UI/计时开销）与一处管理模型/对话/参数。成熟项目不满足前提：ChatterUI 是 React Native 且只集成 llama.cpp，gallery 绑死 LiteRT 系——给它们补三个引擎的成本不低于自建 Compose 壳，还会引入不可控的计时噪声。
7. **对外暴露推理服务 API（OpenAI 兼容）**作为 P5+ 可选增强，不写进当前里程碑：架构上只需在 `LlmEngine` 之上包一层本地 HTTP server（如 Ktor/NanoHTTPD），不影响现有接口设计。
