# CODE_REVIEW.md — 非 native 代码审阅（2026-09-30）

> **修复状态（2026-09-30）**：第一批 A1–A4、第二批 B1–B4、第三批 B5–B9 已修完并通过
> `compileDebugKotlin` + `testDebugUnitTest`。P2（C1–C20）与测试补齐待做。
> 详见文末「修复记录」。

> 范围：`app/`、`core/*`、`engine/*` 的全部 Kotlin 源码（约 1.5 万行，84 个文件）+ Gradle 构建配置。
> 不含 C++/JNI 实现。所有"已确认缺陷"均附代码位置与证据；严重度分级：
> **P0** = 可达崩溃 / 数据错误；**P1** = 逻辑错误 / 结果失真；**P2** = 健壮性 / 一致性 / 可维护性。

## 0. 总评

整体质量高于一般个人项目：契约层（`core:engine-api`）设计清晰且注释规范；`LoggingLlmEngine`
装饰器把日志横切关注点收口得很好；错误处理大多遵循"适配器显式拒绝、不静默回退"的契约；
设置导入采用 additive 合并策略，DataStore 各 store 职责单一。

主要系统性问题集中在三处：

1. **配置解析链在 benchmark 路径上缺失**——聊天与 API server 都走「逐模型覆盖 → 引擎 EngineDefaults」，
   benchmark 却直接用契约占位默认值，三方漂移（见 B1）。
2. **会话生命周期跨页面失控**——benchmark 会卸载聊天页持有的 session，而聊天页对此无感知，
   存在一条可达的崩溃链（见 A1）。
3. **指标口径不统一**——三处把字符数当 token 数、Token.index 三种语义、Genie 空 warning 污染。

## A. 已确认缺陷（按严重度）

### P0 — 可达崩溃 / 确定错误

**A1. benchmark 卸载聊天 session 后，聊天页发送即崩溃**
- 链路：`BenchmarkRunner.runOneTarget` 先 `sessionRegistry.unloadAll()`，而 `ChatViewModel.openSession`
  把聊天 session 也注册进了同一个全局 `SessionRegistry`（`ChatViewModel.kt:609`）。聊天 session 被
  unload 后 `isClosed=true`，但 `ChatUiPersist.sessionState` 仍为 READY、`session` 引用未清。
- 用户回到聊天页（无 recreate，ViewModel 存活）→ `send()` 检查通过 →
  `engine.generate(handle, ...)`（`ChatViewModel.kt:665`）→ 四个适配器都在 `isClosed` 时
  **同步 throw `EngineException.InvalidState`** → `send()` 没有 try-catch → 异常穿透 Compose
  点击回调 → 进程崩溃。
- 修法（两处都做）：
  1. `send()` 对 `engine.generate(...)` 包 `runCatching`，失败时置 `sessionState=FAILED` 并提示重新加载；
  2. `restoreChat()`（`ChatViewModel.kt:359`）检查 `live != null && !live.isClosed`，已关闭则走
     `markIdle()` 而不是标 READY。

**A2. `ApiForegroundService` WakeLock 单位错误：36 秒后自动释放**
- `ApiForegroundService.kt:97`：`acquire(10 * 60 * 60L)`——参数单位是毫秒，该式 = 36_000 ms ≈ 36 秒，
  注释写 "safety cap 10h"。应为 `10 * 60 * 60 * 1000L`。
- 影响：API server 服务 36 秒后失去唤醒锁，后台推理可能被系统休眠打断。

**A3. `ApiForegroundService.onDestroy` 的清理协程被立即取消**
- `ApiForegroundService.kt:82-87`：先 `serviceScope.launch { apiServer?.stop(); bridge.release() }`
  再同步 `serviceScope.cancel()`。`launch` 只是入队，`cancel()` 先执行则清理体可能从未运行
  （server 不停、API session 持有的模型不卸载）。
- 修法：清理用独立 scope（如 `GlobalScope` + 超时）或 `runBlocking`；或 `launch` 后 `join()` 再 cancel。

**A4. LiteRT「backend 切换需重载」警告每轮必出（引用比较 bug）**
- `LiteRtEngine.kt:299`：`if (backend !== mapBackend(session.config.backend))`。
  `mapBackend` 每次构造新实例，而 litertlm 0.11.0 AAR 中 `Backend$CPU/GPU/NPU` 是普通 data class、
  无实例缓存（已对 AAR `classes.jar` 执行 `javap` 实证）→ 两个新对象 `!==` 恒为真 → 该 warning
  每轮 generate 都进 metrics，并拼进聊天状态栏文本（`ChatViewModel.kt:728`）。
- 修法：直接比较应用层枚举 `request.config.backend != session.config.backend`。

### P1 — 逻辑错误 / 结果失真

**B1. benchmark 绕过两层参数解析，与聊天/API 结果不可比**
- `BenchmarkRunner.kt:123` 与 `:295`：`InferenceConfig(maxNewTokens = ...)`——这是契约占位默认
  （temp 0.7 / topK 40 / backend AUTO），**既不是引擎 `EngineDefaults`，也不含逐模型覆盖**。
- 对照：`ChatViewModel.effectiveSampling()`（ChatViewModel.kt:310）与
  `DefaultApiInferenceBridge.baseConfig()`（DefaultApiInferenceBridge.kt:212）都正确实现了
  「per-model overlay → engine.defaults」。
- 实际后果：LiteRT benchmark 走 AUTO→GPU，而引擎默认 CPU 正是为了在 SM8850 上避免 greeting loop
  （`LiteRtEngine.kt:494-499` 的注释）——benchmark 可能在评测中复现复读，且数字与聊天体验无关。
- 修法：把解析逻辑下沉为共享 `ConfigResolver`（建议放 `core:common`，输入 engine+modelId，输出
  InferenceConfig），三个调用点复用；benchmark 注入 `ModelParamsStore`。

**B2. Genie 空回复重试会永久丢失对话历史**
- `GenieEngine.kt:261`：`buildPromptFor` 在重试循环外只算一次。增量场景下 prompt 是「仅本轮用户
  输入」；重试时 `nativeReset`（`:273`）清掉 dialog KV 后，重放的仍是这段增量 prompt → 历史全丢。
- 更严重的是 `:330`：成功后 `session.fedMessages = request.messages`——虚假声明全量历史已喂入，
  下一轮继续按增量发送 → 上下文从此不可逆地缺失。
- 修法：重试时改用全量 prompt 重放；或重试后把 `fedMessages` 置 `emptyList` 让下一轮走全量。

**B3. 字符数冒充 token 数，decode_tps 虚高**
- `GenieEngine.kt:312`：`generatedPieces = metricsOut[0].toInt().coerceAtLeast(text.length)`——
  取「native piece 数」与「UTF-16 字符数」的较大者，而后者几乎总是更大 → decode_tps 以字符计。
- `MnnEngine.kt:224`：`if (generatedNative > 0) generatedNative else text.length`——native 返回 0
  时同样退回字符数。
- 副作用：`ChatViewModel.kt:689` 的 hitTokenCap 判断会被字符数误触发（Genie 最容易）。
- 修法：兜底用 piece 计数或 1，不要用 `text.length`；native 无计数时在 warnings 里标注口径。

**B4. Genie warnings 混入空字符串**
- `GenieEngine.kt:321-327`：`warningsFor(...) + context.getString(...) + if (attempts > 1) ... else ""`
  ——首轮成功（attempts==1）也追加一个 `""` warning → `warnings.size` 虚增，
  `LoggingLlmEngine` 打出 `adapter warning: ` 空行，聊天状态栏出现尾随分隔符。
- 修法：`+ listOfNotNull(note, retryNote.takeIf { attempts > 1 })`。

**B5. `StoredModel.toLocal()` 遇未知引擎字符串直接抛异常**
- `DataStoreModelPathStore.kt:39`：`EngineId.valueOf(engineId)`。注册表或导入 bundle 里出现未知
  引擎名（新旧版本互导、引擎下线）→ `observeModels()`/`listModels()` 整个炸掉，聊天、模型页、
  设置导入全受牵连。`decode()` 的 runCatching 只挡 JSON 语法错误，挡不住这里的 valueOf。
- 修法：用 `engineIdFromStorage()` 兜底，未知项跳过（并记一条 warning）。

**B6. htp_config 资产只解包一次，app 升级后用过期配置**
- `GenieConfigResolver.kt:40`：`if (!target.isFile)` 才从 assets 复制 → OTA 升级携带更新的
  htp_config json 时，filesDir 里的旧副本永不更新。
- 修法：按 `BuildConfig.VERSION_CODE` 或内容 hash 加版本戳，不匹配则重拷。

**B7. llama.cpp 默认 seed=0：所有生成实为确定性**
- `LlamaCppEngine.kt:146` / `:194`：`seed = config.seed ?: 0L`。llama.cpp 的"随机"约定是
  `LLAMA_DEFAULT_SEED (0xFFFFFFFF)`；传 0 是固定种子 → 未指定 seed 时每次输出完全一致，
  与契约 `seed: Long? = null`（未指定=随机）语义相反。
- 修法：null → 传 `0xFFFFFFFFL`（或 native 侧用默认值常量）。

**B8. 多模型驻留无上限、无法手动释放**
- `ChatViewModel.kt:233`：`resident` LinkedHashMap 只增不减，无 LRU 驱逐；
  `unloadSession()`（`:623`）在 `multiResidency` 下直接 `return`——UI 上"停止模型"并不释放内存。
- GB 级模型驻留数个即 OOM，且只有 benchmark 的 `unloadAll` 能兜底（代价是把聊天 session 也卸了，见 A1）。
- 修法：驻留表加上限（如 2~3 个）+ LRU 驱逐；`stopModel()` 应真正从驻留表移除并卸载当前模型。

**B9. LlamaCppEngine `N_CTX = 2048` 与默认 `maxNewTokens = 4096` 矛盾**
- `LlamaCppEngine.kt:348`：上下文窗 2048，而 `EngineDefaults.maxNewTokens = 4096`——提示 + 生成
  极易超窗（native 侧行为取决于实现：截断或失败）。
- 更根本的是契约缺口：`InferenceConfig` 没有 context 尺寸字段，适配器只能硬编码。
- 修法：短期把 N_CTX 提到 ≥ 8192 并在 prefill 前 clamp `maxNewTokens ≤ n_ctx - promptTokens`；
  中期在 `InferenceConfig` 加 `contextLength: Int?`（引擎自报上限，UI 可覆盖）。

### P2 — 健壮性 / 一致性

| # | 位置 | 问题 | 建议 |
|---|------|------|------|
| C1 | `GenieEngine.kt:286` vs `LiteRtEngine.kt:247` vs `LlamaCppEngine.kt:225` | `EngineEvent.Token.index` 三种语义：字符数 / 1 起始计数 / 0 起始计数 | 在契约注释中统一定义（建议 0 起始 token 序号），三适配器改齐 |
| C2 | `ModelsViewModel.kt:375` | 引擎名解析失败静默回退 `EngineId.LLAMACPP`（错误归因） | 解析失败则报错并中止注册 |
| C3 | `DeviceProbe.kt:22-37` | `ProbeContext` 含 `availableStorageMb` 却永久缓存；`libraryExists` 重复查 `/system/lib64` | 存储量每次现取；去重 |
| C4 | `BenchmarkRunner.kt:167-168` | SUSTAIN 的 `totalSamples` 公式在 `runs>1` 时与实际循环次数不符（进度口径） | 按实际循环公式 `warmup+runs+max(0,sustainRounds-runs)` |
| C5 | `ModelFormat.kt:106-108` | `validateGguf` 用 `InputStream.read(header)` 可能短读 | 改 `readNBytes(4)` 或循环读满 |
| C6 | `ThinkingSupport.kt:21` | `"r1"` 子串过宽（如名字含 `r16k` 的上下文变体会误判）；正则每次调用现编译 | 词边界匹配（`(?:^|[^a-z0-9])r1(?:[^a-z0-9]|$)`）并预编译 |
| C7 | `UnboundEngine.kt:33` | `generate` 直接 throw 而非返回发 Error 的 job，与契约形状不一致 | 返回一个立即发 `EngineEvent.Error` 的 job |
| C8 | `ChatScreen.kt:216` | `items(messages)` 无 key，与项目自身「key 用身份」规则不一致（DiagEntry 有 seq，ChatUiMessage 无 id） | 给 `ChatUiMessage` 加自增 id 并 `items(messages, key = { it.id })` |
| C9 | `ModelRootMigrator.kt:58,74` | 中文错误串硬编码在 core:common，经 `settings_migrate_failed` 直接上屏（英文 UI 显示中文），违反 I18N 规则 | 改返回错误码/原因枚举，UI 层映射资源 |
| C10 | `SettingsScreen.kt:618-620` | systemPrompt 每击键写一次 DataStore（编辑长文本 = 几十次磁盘事务 + 流回灌重组） | 本地 state 暂存，onDone/失焦后一次性提交（项目已有 NumericField 同款问题，可统一） |
| C11 | `ChatViewModel.kt:672-679` | 每 token 全量重建 messages 列表 + `sb.toString()`：长回复 O(n²) 拷贝 + 每 token 全列表重组 | 按 50~80ms 或每 N token 节流刷新气泡 |
| C12 | `DiagLogger.kt:128` | 每条日志 `entriesFlow.value = buffer.toList()`（O(n) 拷贝/条） | 仅 LogScreen 活跃时同步，或换 `MutableSharedFlow<DiagEntry>` 增量发射 |
| C13 | `ApiServer.kt:30` | `start()` 里 `runBlocking` 读 DataStore（若在主线程调用会卡帧）；`isPortAvailable` 只探 127.0.0.1，与 bind 0.0.0.0 不一致 | start 改 suspend；按 bindAddress 探测 |
| C14 | `ApiServerRoutes.kt:71-72` | CORS `anyHost() + allowCredentials = true`：浏览器拒绝此组合且不安全 | 组合时拒绝启动或警告；默认要求显式 origins |
| C15 | `GenieEngine.kt:10` + `engine/genie/build.gradle.kts:173` | 未使用的 `ChatTemplate` import；`:engine:genie` 对 `:core:chattemplate` 是死依赖 | 删除 |
| C16 | `LlamaCppEngine.kt:322-324` | `warningsFor` 里空 if 块（死代码）；且「backend=CPU」warning 每轮必现，属噪音 | 删空块；该提示降为只在 load 时出现一次 |
| C17 | `SettingsBundle.summary()` | 死代码（I18N.md 已注明），且是中文硬编码 | 删除 |
| C18 | `core:common` 内嵌 Room（`bench/ResultStore.kt`） | Room 编译器/运行时因此进入所有 engine 模块类路径；`exportSchema=false` 且无迁移策略 | 把 bench 存储移到 `core:benchmark`；补 schema 导出与迁移计划 |
| C19 | `LiteRtEngine.kt:206-223` | generate 出错（非取消）时 `liveHistory` 未置脏：失败轮已被 native conversation 消费，下一轮按 sameHistory 复用脏会话 → 用户消息重复 | 出错分支同样 `session.liveHistory = null`（对照取消分支 `:322`） |
| C20 | `ChatViewModel.kt:638` | `send()` 用 `_selectedEngine.value?.engine` 而非 `sessionEngine`；正常流程一致，但语义上应以会话为准 | 改用 `sessionEngine` 并在不一致时拒绝 |

## B. 架构与设计观察

1. **配置解析三处实现（最高优先级的结构性问题）**。聊天（`effectiveSampling`）、API
   （`baseConfig`）、benchmark（缺失）三份逻辑。`docs/MODEL_PARAMS.md` 把「两层结构」定为核心契约，
   却没有单一实现承载它——B1 就是这种漂移的第一次发作。建议下沉 `ConfigResolver`，并为其写单测。
2. **`ModelLocation.SafUri` 全引擎不可用**。四个适配器都在 load 时 throw，但注册入口并不封堵
   （`FileFormatValidator` 对 SAF 返回 `Unknown` 放行）。要么在注册/扫描侧拒绝 SAF，要么在
   `MODEL_PATHS.md` 与 UI 明确「SAF 暂不可用」，避免"能注册、加载才炸"。
3. **装饰器与 DI 链路良好**。`@IntoSet` + `EngineLogging.wrap` + 惰性 sink 解析的设计正确且注释
   到位；`LoggingLlmEngine.safe{}` 保证日志永不影响推理，是教科书式写法。
4. **SessionRegistry 的双重角色需要文档化**。它同时服务 benchmark 的"独占驻留"与 API server 的
   会话管理，而副作用（卸载聊天 session，A1）目前只在代码里隐式存在。建议在 DESIGN.md 里写明
   "benchmark 运行 = 聊天会话被释放"的契约，并在 UI 上提示。
5. **JSON 技术栈混用**：`core:benchmark` 用 `org.json`，其余模块用 kotlinx.serialization。
   建议统一（org.json 在 Android 上有平台差异史）。
6. **`ChatUiPersist` 的定位正确但缺边界检查**。进程级单例扛 recreate 是合理取舍，但它让 session
   生命周期脱离了 ViewModel——所有"外部卸载"（benchmark、未来任何 registry 消费者）都需要在
   读取侧做 `isClosed` 防御（见 A1 修法 2）。
7. **ModelDownloader 增强项**（非缺陷）：无下载取消、无断点续传（`.part` 失败即弃）、无大小/hash
   校验、repo 模式全量拉取（不过滤无关文件）。按优先级建议：取消 > 大小校验 > 续传。

## C. 测试现状与缺口

现有单测覆盖了契约层（FakeEngine/LoggingLlmEngine/EngineId）、common（DataStore 之外的纯逻辑：
ModelParamsKey/ModelRootMigrator/SettingsBundle/CrashReporter/DiagLogger/AppLanguage）、apiserver
协议与队列、benchmark 模型、ThinkingDisplay。androidTest 有 `EngineCoexistenceTest` 与
`NavigationRecreateTest` 两条关键护栏。底子不错。

建议补的高价值单测（都是纯逻辑、无需设备）：

1. `ConfigResolver`（随 B1 下沉后）——两层合并的优先级与全字段覆盖。
2. `GenieConfigResolver`——JSON 补丁逻辑（tokenizer/engine/sampler 三路 patch、缺 key 兜底）。
3. `MetricsCollector`——native/wall 双口径的优先级、0 token / 单 token 边界。
4. `ModelCatalog.matchImported` / `findModelDir`——多布局、歧义命中、大小写。
5. `ThinkingSupport.byName`——C6 的误伤用例固化。
6. `StoredModel.toLocal`——未知引擎字符串（B5 的回归测试）。

## D. 修复优先级建议

| 批次 | 内容 | 理由 |
|------|------|------|
| 第一批 | A1（崩溃链）、A2（wakelock）、A3（清理取消）、A4（!==） | 一行到几行的确定性修复，直接消除崩溃与每轮噪音 |
| 第二批 | B1（ConfigResolver 下沉）、B2（Genie 重试历史）、B3（token 口径）、B4（空 warning） | 恢复评测可信度与 Genie 多轮正确性 |
| 第三批 | B5、B6、B7、B8、B9 | 健壮性与资源管理 |
| 持续 | P2 表格项 + 测试补齐 | 随相关模块改动顺手做 |

## E. 修复记录（2026-09-30）

### 第一批 — 已修

| 项 | 修法 | 落点 |
|----|------|------|
| A1 | `restoreChat` 检查 `!live.isClosed`；`send()` 入口拒已关闭 handle + `runCatching(generate)`，失败置 `FAILED` 并提示重载 | `ChatViewModel.kt`；新串 `chat_status_session_lost` |
| A2 | `acquire(10 * 60 * 60 * 1000L)` | `ApiForegroundService.kt` |
| A3 | 清理改独立 scope，不再与 `serviceScope.cancel()` 竞态 | `ApiForegroundService.kt` |
| A4 | 比较应用层枚举 `request.config.backend != session.config.backend`；`mapBackend` 仅作 OPENCL 校验 | `LiteRtEngine.kt` |

### 第二批 — 已修

| 项 | 修法 | 落点 |
|----|------|------|
| B1 | 新增 `ConfigResolver`（overlay → `EngineDefaults`，支持 `maxNewTokensOverride`）；chat / API / benchmark 三处复用；benchmark 注入 `ModelParamsStore` | `core:common/.../ConfigResolver.kt` + `ConfigResolverTest`；`ChatViewModel`、`DefaultApiInferenceBridge`、`BenchmarkRunner` |
| B2 | 空回复重试时 `nativeReset` 后清 `fedMessages` 并改 `formatFullPrompt` 全量回放 | `GenieEngine.kt` |
| B3 | 兜底用 piece 计数，不用 `text.length`；native 计 0 时记 warning | `GenieEngine.kt`、`MnnEngine.kt`；新串 `genie_piece_fallback` |
| B4 | `listOfNotNull(...takeIf{...})`，不再追加空串 warning | `GenieEngine.kt` |

### 第三批 — 已修

| 项 | 修法 | 落点 |
|----|------|------|
| B5 | `StoredModel.toLocal()` 返回 `null`（`engineIdFromStorage`），调用方 `mapNotNull` + `Log.w` | `DataStoreModelPathStore.kt` + `StoredModelTest` |
| B6 | htp_config 按 `packageInfo.versionCode` 加版本戳，不匹配则重拷 | `GenieConfigResolver.kt` |
| B7 | `seed ?: 0xFFFFFFFFL`（`LLAMA_DEFAULT_SEED`），不再用固定种子 0 | `LlamaCppEngine.kt` |
| B8 | 驻留表 access-order LRU，上限 3；`stopModel` 强制卸载并移出驻留表 | `ChatUiPersist.kt`、`ChatViewModel.kt` |
| B9 | `N_CTX` 2048→8192；prefill 后按 `n_ctx - prompt` clamp 解码上限并记 warning | `LlamaCppEngine.kt` |

验证：`:app` / 全 engine / `core:*` 的 `compileDebugKotlin` 与 `testDebugUnitTest` 全绿。
P2（C1–C20）与 §C 测试缺口未动。

## F. 修复复核（2026-09-30，独立会话逐项对照源码）

**结论：A1–A4、B1–B9 共 13 项全部修复到位，与 §E 记录一致，无谎报、无半落地。**

复核方式：逐项读修复落点源码 + 强制重跑 `:core:common` / `:core:benchmark` 单测 +
`:app:compileDebugKotlin`（BUILD SUCCESSFUL；ConfigResolverTest 6/6、StoredModelTest 3/3）。

逐项核验要点：

- A1 双保险齐全：`send()` 前置 `handle.isClosed` 拦截（置 FAILED + `chat_status_session_lost`，双语资源齐全），
  `generate` 包 `runCatching` 且 `getOrElse` 同样置 FAILED；`restoreChat` 侧 `!live.isClosed` 检查 + 清理闭环。
- A3 用独立 scope 清理且带注释指回本条；A4 改枚举比较，`mapBackend` 仅保留 OPENCL 拒绝用途。
- B1 三处调用点（chat:337 / API:215 / benchmark:275）全部收口到 `ConfigResolver`，benchmark 已注入
  `ModelParamsStore` 且 LiteRT 不再以 AUTO 评测。
- B2 重试路径 `nativeReset` 后清 `fedMessages` 并 `formatFullPrompt` 全量回放；成功路径
  `fedMessages = request.messages` 在增量/全量两种实际喂入下均为真。
- B3 genie/mnn 均为 piece 计数兜底 + fallback warning（`genie_piece_fallback` 双语齐全）；
  B7 `RANDOM_SEED = 0xFFFFFFFFL`；B9 `N_CTX = 8192` + clamp + warning。
- B8 驻留表 access-order + `MAX_RESIDENT = 3` + LRU 驱逐，`stopModel` 走 `unloadSession(force = true)` 真正释放。

复核中发现的两个新的小问题（均非阻塞）——**已修（2026-09-30）**：

1. `ChatViewModel.evictResidentOverflow()`：改为在选 eldest 时就跳过 live session
   （`firstOrNull { it.value.second !== session }`），不再「remove 后 continue 不插回」。
2. C20 收口：`send()` 以 `sessionEngine` 为准；与 picker 引擎不一致时拒绝发送并提示
   （新串 `chat_status_engine_mismatch`，双语）。至此 A/B 全部 + F 两项落地，仅剩 P2 表格与 §C 测试缺口。
