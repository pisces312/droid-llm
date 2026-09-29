# 逐模型参数（MODEL_PARAMS）

> **采样 / 执行参数只有两层，没有第三层。** 本文是这两层的权威说明：值放哪、键怎么拼、
> 改哪才生效、以及「上游四个示例项目怎么做」的调研（§6）。
> 引擎接入见 `docs/ENGINE_INTEGRATION.md`，LiteRT 复读定案见 `docs/litert.md` §3.3 / §6，
> 界面语义见 `UI_DESIGN.md` §5.4。

## 1. 结论

**可以为每个模型单独设参数，而且键里带引擎** —— 同一个模型在不同引擎上可以钉不同的值。
解析链只有两段：

```
PerModelOverride        DataStore（droid_model_params），键 = "<ENGINEID>:<modelId>"，字段全可空
      ↑ 覆盖（逐字段）
EngineDefaults          适配器代码里声明（LlmEngine.defaults），必填、不可编辑
```

**没有 App 级 / 全局采样层**（2026-09-29 删除）。`AppSettings` 里的
temperature / topK / topP / threads / maxNewTokens / backend 与 `setSampling()` / `toInferenceConfig()`
已不存在，旧 DataStore 值直接废弃、不迁移。

为什么不留全局层（三条，都是踩出来的）：

1. **全局值是"没人负责"的值。** 四个引擎对同一个旋钮的合理取值差一个量级（§4），
   一个全局默认必然对其中三个是错的，且事后无法归因「这个 0.7 是谁定的」。
2. **它和「逐模型覆盖」功能重叠。** 想要的效果（这个模型用这套值）用覆盖直接表达，
   中间多一层只会制造「改了全局、以为改了这个模型」的错觉。
3. **它没有正确的默认值可填。** 既然每个引擎都要显式声明 `EngineDefaults`，全局层就只是
   `EngineDefaults` 的重复。

## 2. 键空间与兼容

```kotlin
// core/common/.../model/ModelParamsStore.kt
fun modelParamsKey(engineId: EngineId, modelId: String) = "${engineId.name}:$modelId"
```

- **键 = 引擎 + 模型，不是裸 `modelId`。** 理由有实证：Qualcomm 给 `gemma3-1b` 的 NPU 配置是
  `temp 0.8 / top-k 1`（贪心），Google 给同一模型的 LiteRT 配置是 `1.0 / top-k 64`
  （§6.1）。键里没有引擎，两个引擎的钉值会互相覆盖。
- **兼容只在读侧**：`Map.find(engineId, modelId)` 先查 `ENGINE:modelId`，未命中再查裸 `modelId`
  （v1 布局）。`set()` 写新键时顺手 `remove(modelId)`，数据自愈。
- **空覆盖不落盘**：`ModelParamsOverride.isEmpty` 为真或传 `null` → 删除该键
  （一个没钉任何字段的条目不携带信息）。
- **反序列化容错**：`Json { ignoreUnknownKeys = true }`，将来加字段不需要迁移。
- 回归：`core/common/src/test/.../model/ModelParamsKeyTest.kt`
  （同模型两引擎互不干扰 / 旧键回退 / scoped 优先）。

## 3. 用户侧怎么用

| 入口 | 位置 | 能做什么 |
|------|------|----------|
| 会话采样面板 | Chat 输入框上方参数 chip → BottomSheet | 改 temp / top_k / top_p / threads / max_tokens / backend / systemPrompt，逐字段可用「仅本模型」**钉住** |
| 设置 → 引擎默认采样 | Settings 页 | **只读**，列出四个引擎各自的出厂默认值，用来对照 |

「仅本模型」的两态语义（`ChatScreen.SamplingSheet` + `ChatViewModel.setModelOverride`）：

| 状态 | 写入 | 持久 | 作用范围 |
|------|------|------|----------|
| 已钉（`已覆盖引擎默认`） | `ModelParamsStore.set(engineId, modelId, …)` | ✅ DataStore | 该引擎 × 该模型 |
| 未钉 | 只改内存里的 `_sampling`（`updateSampling`） | ❌ **仅本次会话** | 当前会话 |

> ⚠ **未钉的改动不落盘**：模型/引擎切换、设置导入或 `refreshSampling()` 之后会回到引擎出厂默认。
> 这是当前实现留下的边界（面板副标题已按此改写），不是设计意图上的「全局层」——全局层已删。
> 若用户反馈「改了没保存」，第一件事是看这一行。

## 4. 四家引擎的出厂默认值

`LlmEngine.defaults` 的类型是 `EngineDefaults`（`core/engine-api/.../LlmEngine.kt`），
**接口上没有默认实现** —— 漏声明编译不过，不会退回某个「没人选过」的值。

| 引擎 | `temp / topK / topP / threads / maxNew / backend` | 理由 |
|---|---|---|
| LiteRT-LM | `1.0 / 64 / 0.95 / 4 / 4096 / CPU` | Gemma 3 官方推荐采样；`0.7` 已被真机证实会复读，后端降级见 `docs/litert.md` §3.3、§6 |
| MNN | `0.7 / 40 / 0.95 / 4 / 4096 / CPU` | 尚无真机证据支持偏离 |
| llama.cpp | `0.7 / 40 / 0.95 / 4 / 4096 / CPU` | 当前只支持 CPU |
| Genie | `0.7 / 40 / 0.95 / 4 / 4096 / NPU_HTP` | 只支持 NPU，显式表态而不是靠 `AUTO` 兜 |

- `backend` 一律写**真实**后端，不写 `AUTO` —— `AUTO` 只是把决定推给下一层，而这一层是最后一层。
- `systemPrompt` 是**唯一留在 `AppSettings` 的相关项**：它是提示词不是采样旋钮，
  且留空会让 MNN 上极短首轮直接命中 EOS（`docs/mnn.md` §2）。逐模型同样可覆盖，
  回退顺序是 `覆盖 → AppSettings.systemPrompt`。

## 5. 生效路径（只改这两处之外的地方无效）

| 路径 | 位置 | 说明 |
|---|---|---|
| 聊天 | `ChatViewModel.effectiveSampling()` | 结果进 `_sampling`，**load 与 generate 都读它**，所以 `backend` 这类只在 load 生效的字段也能到达 `load()` |
| 本地 API | `DefaultApiInferenceBridge.baseConfig()` | `ensureSession` 与 `generate` 共用；**也读逐模型覆盖**，否则「模型自己的设置」只在聊天页生效；请求体里的 `temperature/topP/max_tokens` 叠在最上面 |

四条硬约束：

1. **装饰器必须转发 `defaults`。** Hilt 注入的是 `LoggingLlmEngine` 不是适配器本体，
   漏转则适配器的值静默失效（`LoggingLlmEngineTest.engineDefaultsAreDelegated` 钉住）。
2. **后端只在 `load()` 生效一次**（LiteRT 加载时建 delegate），所以默认值/覆盖值必须同时进
   `load` 的 config；只改 `generate` 的 config 会静默保留旧后端。
3. **不要把默认值下沉进适配器的 `generate()`**。进到适配器时，「用户手选的 0.7」与
   「继承来的 0.7」是同一个数，分不开了；合并必须发生在意图还已知的地方（app 层）。
4. **两条入口共用同一套解析**，新增入口（benchmark / 未来的批量任务）也要走 `effectiveSampling()`
   或 `baseConfig()`，不要各自读 DataStore。

## 6. 上游四个项目怎么做的（2026-09-29 调研）

对象：MnnLlmChat（`apps/Android/MnnLlmChat`）、Google AI Edge Gallery、
Qualcomm `chatapp_android`、`llama.cpp/examples/llama.android`。

| 项目 | 引擎数 | 出厂默认值在哪 | 用户改的值在哪 | 粒度 |
|------|--------|----------------|----------------|------|
| **MnnLlmChat** | 1（MNN） | 模型目录自带的 `config.json`（`temperature`/`topK`/… 全在里面）；代码兜底 `mixedSamplers/temperature=0.6/topK=20`（`modelsettings/ModelConfig.kt:323-326`） | `<模型配置目录>/custom_config.json`，`loadMergedConfig()` 把 override merge 进基础 config（`ModelConfig.kt:160-176`、`getExtraConfigFile():275`） | **模型** |
| **AI Edge Gallery** | 1（LiteRT-LM / AICore） | 模型 allowlist JSON 的 `DefaultConfig`（topK/topP/temperature/maxTokens，**逐模型**，`data/ModelAllowlist.kt:27-35`）→ `defaultConfig?.topK ?: DEFAULT_TOPK`（`ModelAllowlist.kt:142-146`） | `Model.configValues: Map<String, Any>`（`data/Model.kt:154`，key = 该模型 config 的 label；`preProcess()` 用上表默认值初始化，`Model.kt:249-255`） | **模型** |
| **chatapp_android**（Genie） | 1（QNN） | 模型目录的 `genie_config.json` → `dialog.sampler`（QAIRT 出厂 `configs/gemma3/gemma3-1b-htp.json` = `temp 0.8 / top-k 1 / top-p 0.95`） | **没有 UI**，要改就改 json | **模型** |
| **llama.android**（官方 example） | 1（llama.cpp） | native 常量 `constexpr float DEFAULT_SAMPLER_TEMP = 0.3f`，单例 sampler（`lib/src/main/cpp/ai_chat.cpp:34,107-111,121`） | **完全没有** —— 全仓 `.kt/.java/.cpp/.h` 零个 `temperature`/`topK`/`penalty` | 连模型级都没有 |

三条结论：

1. **做过设置系统的三家（MNN / Gallery / Genie）全部「跟模型走」。** 没有一家把采样参数做成
   "跟引擎走"的全局设置页 —— 它们的默认值一律来自**模型自带的配置文件**（MNN `config.json`、
   Genie `genie_config.json`）或**模型 allowlist 的逐模型 defaultConfig**（Gallery）。
   我们之所以要有 `EngineDefaults` 这一层，是因为我们有四个引擎、且用户自备模型文件
   （没有随模型发布的 config.json 可读），必须给「引擎未知 + 模型没说」留一个明确的兜底。
2. **四家都没有"引擎"这个键维度，不是觉得不需要，而是每家只有一个引擎。**
   我们保留 `"ENGINE:modelId"` 是为了同一模型在跨引擎时钉值不串味（§2 的 gemma3-1b 实证）。
3. **参数可用性本身就跟引擎/加速器走。** Gallery 对「只支持 NPU」的模型直接不下发
   topK/topP/temperature 三个控件（`data/Config.kt:426-437`，注释
   *"For now NPU models don't support setting topK, topP, and temperature."*），
   AICore 分支还把 temperature 夹到 `[0,1]`。我们对应的是
   `core/engine-api/.../ConfigApplicability.kt`（把不适用的字段灰显 + 给 note，
   例：LiteRT/Genie 的 `threads`、除 llama.cpp 外全家的 `seed`），四个引擎不支持的
   `InferenceConfig` 字段进 `EngineMetrics.warnings`。

### 6.1 `gemma3-1b` 的跨引擎实证（这一条就是双键的理由）

| 出处 | 值 |
|---|---|
| QAIRT 出厂配置 `D:\dev\qairt\<ver>\examples\Genie\configs\gemma3\gemma3-1b-htp.json` → `dialog.sampler` | `temp 0.8 / top-k 1 / top-p 0.95`（**贪心**） |
| Google 给 LiteRT 的 Gemma 3 官方推荐（`docs/litert.md` §2，gallery 同值） | `temp 1.0 / top-k 64 / top-p 0.95` |

同一个基座、同一个参数量，正确参数差在这个程度上 —— 所以键里必须带引擎。

## 7. 验收清单（真机 arm64，不需要 PC）

1. 设置页出现 **「引擎默认采样」只读卡片**，四行 = 四家引擎（无全局采样输入框、无保存按钮）。
2. 选中一个 LiteRT 模型 → 会话采样面板显示 `temp 1.0 / top_k 64 / backend CPU`（其余三家是 `0.7/40`，Genie 的后端为 `NPU_HTP`）。
3. 点某一项的「仅本模型」→ 标签变 `已覆盖引擎默认` → 返回再进，值仍在（持久化成功）。
4. 同一个模型换个引擎 → 面板回到**新引擎**的出厂默认，看不到上一个引擎的钉值（双键生效）。
5. 引擎日志里 `load`/`generate` 行应显示生效值 `temp=… topK=… backend=…`（后端只在 load 生效）。
6. 单测：`:core:common:testDebugUnitTest` 里的 `ModelParamsKeyTest`、
   `:core:engine-api:testDebugUnitTest` 里的 `LoggingLlmEngineTest`。

## 8. 未验证 / 待决

- **`maxNewTokens` 4096 没有证据支撑**：Gallery 的 `DEFAULT_MAX_TOKEN` 是 1024，
  MnnLlmChat 的 `MAX_NEW_TOKENS_LIMIT`（钳制上限）是 4096。我们取 4096 是沿用，未做对比实验。
- **LiteRT 的 GPU 路径没有单独复测**：复读定案（`docs/litert.md` §3.3）只在 CPU 上做过，
  所以出厂后端取 `CPU`；GPU 是否同样复读、性能差多少，仍待测。
- **「未钉即不落盘」是否要改成落盘**：现状见 §3 的警告。若要改，需要先决定它落在哪一层 ——
  而**不能再新建全局层**（§1 的三条理由仍然成立）。
- **`threads` 在 LiteRT 不生效**（引擎自管线程池，适配器记 warning）；
  `seed` 在 LiteRT / MNN 都不生效。这类"字段存在但引擎忽略"的差异只在
  `EngineMetrics.warnings` 里体现，UI 未逐项标注。

## 9. 已否决的方案（别再提第二遍）

| 方案 | 为什么否 |
|---|---|
| App 级全局采样默认值 | §1（无正确值可填 + 与逐模型覆盖重叠 + 无法归因） |
| 键用裸 `modelId` | §2 / §6.1（跨引擎串味） |
| 把默认值写进各适配器的 `generate()` | §5 第 3 条（进去就分不清「选的」和「继承的」） |
| 引擎默认值给接口默认实现（`defaults = EngineDefaults(...)`） | 漏声明会静默退回；现在漏声明直接编译不过 |
| 保存配置时 bump `BundledSettings` 版本号 | 没必要：`ignoreUnknownKeys` 能读旧包，多删几个字段不需要版本门 |
