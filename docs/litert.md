# docs/litert.md — LiteRT-LM 引擎笔记

> 本引擎的坑与实测结论写这里（项目约定见 `AGENTS.md`：引擎专属发现进 `docs/<engine>.md`）。
> 通用接入信息在 `docs/ENGINE_INTEGRATION.md` §1。

## 1. 依赖与事实基线

| 项 | 值 |
|---|---|
| 依赖 | `com.google.ai.edge.litertlm:litertlm-android:0.11.0`（AAR，**闭源**） |
| AAR 缓存 | `D:\dev\.gradle\caches\modules-2\files-2.1\com.google.ai.edge.litertlm\litertlm-android\0.11.0\...`（**不在** `~/.gradle`） |
| 参考实现 | `GALLERY_ROOT` = `D:\3rd-party-projects\gallery`，同一 AAR 版本 0.11.0 |
| 反编译方式 | 解 AAR → `classes.jar` → `D:/dev/AndroidStudio/jbr/bin/javap.exe -p -c`；`.so` 提字符串用 `tr -c '[:print:]' '\n'`（Git Bash 无 `strings`） |

### 已确认的 API 语义（不要靠猜）

- `Message.toString()` == `Contents.toString()`（`joinToString("")`）。
  **`onMessage` 回调给的是 token 增量，不是累计全文** —— 判据不是这段字节码，而是 gallery 的消费端：
  `DefaultLlmSessionManager` / `LlmSingleTurnViewModel` 都是 `response = "$response$partialResult"`
  （纯 append）。若回调是累计全文，gallery 自己就会显示成一串叠字。
- `SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int = 0)`。
  **0.11.0 没有 repetition / frequency penalty 参数**。
- `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`。
  `maxNumTokens` 是 **KV cache / 上下文长度**，不是「最多生成多少 token」。Gemma3-1B 的 `.litertlm`
  模型上下文就是 4096。
- `ConversationConfig(systemInstruction, initialMessages, tools, samplerConfig, automaticToolCalling, channels, extraContext)`。
- 内置 fallback jinja 模板含 `{%- if messages[0]['role'] == 'system' -%}` 与
  `raise_exception("Conversation roles must alternate user/assistant/...")`
  → `initialMessages` 必须**严格 user/model 交替且首条为 user**；SYSTEM 走 `systemInstruction`，不进 `initialMessages`。

### 两个「官方但没用上」的诊断钩子（本轮挖出来的）

| API | 作用 |
|---|---|
| `Conversation.renderMessageIntoString(message, extraContext = emptyMap())` | 返回**模板渲染后的真实 prompt 字符串** |
| `Engine.Companion.setNativeMinLogSeverity(LogSeverity)` | 提高 native 日志等级（`VERBOSE`/`DEBUG`），把 `liblitertlm_jni.so` 内部输出灌进 logcat |

前者正是当年在 MNN 上定案的同一手法（打印模板可见的 prompt）。后者配合
`EngineLogcatCapture`（见 `docs/DIAGNOSTICS.md`）即可让真机日志自带 native 细节。
**调用时机（发送前 / 发送后）尚未实测确认**，用之前先验。

## 2. 本项目 vs gallery：LiteRT 路径完整差异表

排查「为什么 gallery 正常、我们异常」时先看这张表。同 AAR 版本、同设备类、同模型。

| 项 | 本项目 | gallery | 是否偏差 |
|---|---|---|---|
| litertlm-android | 0.11.0 | 0.11.0 | 否 |
| **后端** | **默认 CPU**（引擎推荐值，§6） | 默认 GPU | **是**，且是**刻意保留**的偏差：GPU 路径的复读未单独复测（§3.3） |
| **temperature** | **1.0**（引擎推荐值） | **1.0**（`DEFAULT_TEMPERATURE`） | 否（2026-09-29 起对齐） |
| **topK** | **64**（引擎推荐值） | **64**（`DEFAULT_TOPK`） | 否（2026-09-29 起对齐） |
| topP | 0.95 | 0.95 | 否 |
| **maxNumTokens** | **4096**（直接把 `maxNewTokens` 塞进去） | 1024（`DEFAULT_MAX_TOKEN`，模型目录可覆盖） | **是**，未验证是否与复读有关 |
| GPU sampler .so | 未随包（`libLiteRtTopKOpenClSampler.so` 缺失 → CPU 采样回退） | **同样缺失**（gallery 全仓 0 个 `.so`） | 否 |
| systemInstruction | `"You are a helpful assistant."` | `SystemPromptHelper.getEffectiveSystemPrompt(...)` | 否（都有） |
| `enable_thinking` | 每次传 Boolean | 同样每次都传 | 否 |
| `onMessage` 处理 | 直接 `append` | 直接 `append` | 否 |
| `<ctrl` 前缀过滤 | 有 | agent 路径有 | 否 |
| 会话复用 | 复用长生命周期 Conversation；历史不一致时重建 | 复用；仅用户点停后重建 | 基本一致 |
| 复读截断 | **无**（上一版自创的 `isDegenerateRepeat` 已按要求删除） | 无 | 否 |

结论：**采样参数两项偏差已消除（1.0 / 64），剩下 `maxNumTokens` 一项未验证。**
「GPU 采样库缺失」不是差异 —— gallery 同样没有，这条假设已排除。

## 3. 真机复现记录：输出短语复读（2026-09-29）

**症状**：真机（HONOR BKQ-AN80 / SM8850 / Android 17 API 37）上 LiteRT + Gemma3-1B-IT int4，
输入 2 字短句，回复变成「您好」类问候语的重复流。

### 日志给出的硬事实

| # | 事实 | 出处 |
|---|---|---|
| 1 | 走 GPU（OpenCL delegate），7 个 subgraph 各 1373/1373 节点全部替换为 `LITERT_CL` | `tflite` / `delegate_kernel.cc` |
| 2 | 只发了一轮：`messages=2`，`promptChars=[system:28,user:2]`，`maxNew=4096`，`temp=0.7 topK=40` | `[engine/litert] generate start` |
| 3 | `system:28` 与 `"You are a helpful assistant."` 长度完全一致 → 系统提示词按默认值送达 | 长度比对 |
| 4 | 首 token 98ms，文本 `"您"` | `first token after 98ms` |
| 5 | **2868ms 产生 99 个 token（≈34.5 tok/s）后被用户取消**，不是自然结束 | `generate error ... tokens=99 type=Cancelled` |
| 6 | GPU sampler 库缺失 → `Falling back to CPU sampling` | `sampler_factory.cc:730` |
| 7 | `ThreadPool 'engine': Running up to 1 threads` | `threadpool.cc:41` |
| 8 | 当轮安装的 debug 诊断包**不含任何复读截断逻辑** | `dexdump`/grep 阴性 + 阳性对照通过 |

### 已排除的假设

| 假设 | 排除依据 |
|---|---|
| `onMessage` 给的是累计全文，被我们 append 成了叠字 | gallery 同样是 `response + partialResult`，若是累计它自己就会叠字 |
| GPU sampler `.so` 缺失导致采样错误 | gallery 全仓 0 个 `.so`，同版本 AAR，同一回退路径 |
| Hilt/日志装饰器把输出改了 | 装饰器只镜像事件，`getSample` 之类的字段是纯新增；且这是日志基建上线前就有的老问题 |

### 未定论（按可能性排序）

| # | 假设 | 支持证据 | 反证 / 缺什么 |
|---|---|---|---|
| A | **采样参数偏离 Gemma 3 官方推荐**：官方是 `temperature=1.0 / top_k=64 / top_p=0.95`，我们传 `0.7 / 40 / 0.95`；gallery 用的正是官方值。低温度是 Gemma 3 已知的复读诱因 | §2 的差异表：这是**唯一可测的偏差**；gallery 对照正常 | **2026-09-29 已验证有效**（§3.3） |
| B | **短 prompt + 1B int4 自身退化**：2 字输入几乎无约束，模型最省力的续写就是寒暄语，且没配 repetition penalty | 首次即退化、首 token 就是「您」 | 不能解释「为什么 gallery 同 prompt 不退化」 |
| C | **设备/驱动侧 GPU 数值问题**（Android 17 / API 37 + 荣耀 OpenCL 驱动） | int4 + GPU delegate 的数值偏差有先例 | gallery 默认也走 GPU，未经同机对照；**本轮与 A 同时改动，未分离** |
| D | 模板把 system 段渲染成了模型没见过的形态（如 `<start_of_turn>system`） | `.so` 里确实存在 `'<start_of_turn>system'` / `'<start_of_turn>developer'` 两种变体模板 | 日志显示加载的是 `Gemma3DataProcessor`，且模型自带模板优先；**未验证** |

> 教训沿用：**不要停在「模型自身退化」这种不可证伪的解释上**。要打印模板可见的 prompt
> （§1 的 `renderMessageIntoString`），像当初在 MNN 上定案一样。

### 3.3 真机验证：1.0 / 64 / CPU 不再复读（2026-09-29）

同一台 HONOR BKQ-AN80、同一个 `gemma3-1b-it-int4.litertlm`、同一句 2 字输入：

| 配置 | 结果 |
|---|---|
| `temp=0.7 / topK=40 / AUTO(→GPU)` | 复读问候语，2.9s 产出 99 token 才被手动停 |
| **`temp=1.0 / topK=64 / CPU`** | **正常回复，不再复读** |

**但这不是一次干净的 A/B**：三项（温度、topK、后端）是同时改的，所以只能得出
「**这组配置可用**」，**不能**把功劳判给其中任何一项。规范做法是每次只动一项。

按 §2 的差异表，仍未被解释的部分：

- **GPU 是否就是诱因** —— 若只用 1.0/64 但后端留 GPU 也不复读，那后端根本不用降级，
  CPU 只是白丢速度。**这是最值得补的一次实验**（一次发送即可判定）。
- **短 prompt 是否只是触发条件**（假设 B）—— 在 CPU + 1.0/64 下用 2 字与 ≥10 字各发一次。
- `maxNumTokens=4096` vs gallery 的 1024 是否有影响。

> 因此出厂默认值（§6）照抄了这组**已验证可用**的组合，而不是照抄 gallery ——
> 在 GPU 被单独证明无害之前，拿确定性换掉一点解码速度是划算的。

### 定论实验（剩余，按性价比排序）

1. **单变量复测 GPU**：把该模型的后端钉回 GPU（会话面板「仅本模型」→ GPU），温度/topK 保持 1.0/64。
   仍不复读 → 假设 C 排除，可把出厂推荐值里的 CPU 去掉。
2. **输入长度 A/B**：同一模型换成 ≥10 字的完整问句。若正常 → 短 prompt 是触发条件（假设 B 部分成立）。
3. **同机对照 gallery**：用 Google AI Edge Gallery 跑同一个 `gemma3-1b-it-int4.litertlm`、同一句，
   同样开 GPU。若 gallery 也复读 → 是模型/设备，不是我们的接入。
4. 上述都排除后，再考虑 **NPU**（设备是 SM8850，Genie 侧可见 `v81`）或换模型量化版本。

## 4. 诊断空白（下次复现前值得补）

本轮日志**无法**证明复读内容与真实 prompt，因为：

1. `LoggingLlmEngine` 只记**首 token 文本**，不记生成结果 —— 被取消/报错时正文完全没进日志。
   → 应改为在 `Done` / `Error` 时记录**输出尾部**（如末 120 字，转义 + 截断）。
2. 从不记录**模板渲染后的 prompt**。`promptChars=[system:28,user:2]` 是原始字符数，
   不是真正喂给模型的 token 序列 —— MNN 当初就是靠打印模板才定案的。
3. native 日志等级固定在默认档，`liblitertlm_jni.so` 内部细节没进 logcat。

三条都有现成 API 可用（§1），不需要改 native。

## 5. 与 UI 的交互约束

- `LiteRtJob.cancel()` 走 `conversation.cancelProcess()`；取消后 `liveHistory = null`，
  下一轮必须从 `GenerateRequest.messages` 重建 Conversation —— **不要**在 `null` 时假设
  Conversation 仍然干净，否则历史会重复注入（`Conversation roles must alternate` 会直接抛）。
- `sendMessageAsync` 的回调线程不是主线程，`onEvent` 下游（Chat UI）必须自己保证线程安全。

## 6. 引擎出厂默认值（2026-09-29 起，两层结构）

> 通用部分（两层结构、`"<ENGINEID>:<modelId>"` 键、生效路径、上游四项目对照）
> **一律见 `docs/MODEL_PARAMS.md`**，本节只留 LiteRT 自己的值。

LiteRT 自己那一套：

```kotlin
// engine/litert/.../LiteRtEngine.kt
override val defaults = EngineDefaults(
    temperature = 1.0f,      // Gemma 3 官方推荐，gallery 同值；0.7 已被真机证实会复读（§3.3）
    topK = 64,               // 同上
    topP = 0.95f,
    threads = 4,             // LiteRT 自管线程池，这个值其实不生效，只在 metrics 里记 warning
    maxNewTokens = 4096,
    backend = AppBackend.CPU,   // 见 §3.3：GPU 未单独复测
)
```

`maxNewTokens = 4096` 只是沿用，**没有对比实验支撑** —— Gallery 的 `DEFAULT_MAX_TOKEN` 是 1024
（`docs/MODEL_PARAMS.md` §8 已记为待验证）。

验收方式（真机，无需 PC）：选中一个 `.litertlm` 模型 → 会话采样面板应显示
**温度 1.0 / topK 64 / 后端 CPU**；或抓取引擎日志看 `load`/`generate` 行里的
`temp=1.0 topK=64 backend=CPU`。

