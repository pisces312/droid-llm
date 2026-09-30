# docs/genie.md — Genie / QNN（Qualcomm）引擎笔记

> 本文件是 **Genie 引擎的唯一权威**（项目约定见 `AGENTS.md`：引擎专属的坑与结论进 `docs/<engine>.md`）。
> 跨引擎的通用信息在 `docs/ENGINE_INTEGRATION.md`（模块地图与总览、Backend 契约、符号冲突、计时口径、
> 许可）—— 其中 §3 是 Genie 的**入口**，只留指向本文件的指针。
> 架构契约见 `DESIGN.md` §1.2 / §10，模型目录见 `docs/MODEL_PATHS.md`，
> 采样与执行参数见 `docs/MODEL_PARAMS.md`。

## 0. 结论速查

**Genie 启动失败是「两个独立缺口串联」，只修一个必然以另一种方式失败**（2026-09-29 真机调研结论）：

| # | 缺口 | 症状（一眼区分） | 修法 | 详见 |
|---|---|---|---|---|
| 1 | 未设 `ADSP_LIBRARY_PATH` → HTP skel 找不到 | `GenieDialog_create failed`，**约 300 ms 后优雅返回**，日志里有一串 `apps_std_fopen_fd failed` | `QnnEnv.ensure(nativeLibraryDir)`，且必须在 `GenieNative` 被触碰前调用 | §4.2–4.4 |
| 2 | APK 缺 `libQnnHtpNetRunExtensions.so` | **SIGSEGV 段错误**，栈在 `libGenie.so (GenieDialog_create+484)`，线程 `DefaultDispatch`，**早于模型加载**、无 abort message | `copyQnnJniLibs` 的 `include(...)` 加一行 | §4.5 |

两条都是**上游 `chatapp_android` 同样存在的坑**，第 2 条更是 **Qualcomm 自身的 bug**（加载失败只 warn
不返回错误，随后解引用空指针）—— 所以 app 侧日志永远看不到线索，**排查必须用官方 CLI**（§9）。

排障入口：**先跑 §9 的两个 CLI 实验，再回头看 app 日志** —— 顺序反了会浪费大量时间。

---

## 1. 依赖与编译

| 项 | 值 |
|---|---|
| 依赖 | Qualcomm QAIRT / QNN SDK **2.50.0.260828**（专有发布包，非 git；与 local-dream 一致） |
| 模块 | `:engine:genie` |
| Kotlin | `GenieEngine.kt`（`LlmEngine` 实现）、`GenieConfigResolver.kt`（htp_config 落盘 + `genie_config.json` 路径改写）、`GenieNative.kt`（JNI 绑定 + `QnnEnv`） |
| native | `src/main/cpp/genie_chat_jni.cpp` → `libgenie_chat_jni.so`；链预编译 `libGenie.so` + QNN HTP runtime |
| Backend | 仅 `NPU_HTP` / `AUTO`；其余**显式** `EngineException.UnsupportedBackend`（契约：禁止静默回退） |

**QAIRT 路径解析顺序**（`engine/genie/build.gradle.kts`）：

1. Gradle `-Pdroid.qairtSdkRoot=...`
2. env `QAIRT_PATH`
3. env `QAIRT_SDK_ROOT`
4. 默认 `D:/dev/qairt/2.50.0.260828`（仅开发者机器；仓库不写死路径）

判定「QAIRT 可用」需**同时**满足 `<root>/lib/aarch64-android/libGenie.so` 是文件、`<root>/include/Genie` 是目录。

**编译开关**：

- `-Pdroid.skipGenie=true`（或 `-PskipGenie=true`）：跳过 so 打包与 native 构建；Kotlin 仍编译，
  `probe()` → `MissingDependency`，UI 灰显
- QAIRT 路径无效时**自动跳过**并 warn（不是报错）

**jniLibs 布局**（`copyQnnJniLibs`，对齐 chatapp_android 的 `CopyQnnLibs`）：

- arm64 so 从 `lib/aarch64-android/` 拷入，`libQnnHtpV*Stub.so` 全收、排除 `*CalculatorStub.so`
- **显式点名（不在任何通配里，漏掉就是 §4.5 的段错误）**：`libGenie.so`、`libQnnHtp.so`、
  `libQnnHtpPrepare.so`、`libQnnSystem.so`、`libQnnSaver.so`、**`libQnnHtpNetRunExtensions.so`**
  —— 增删这个列表前先读 §4.5
- hexagon skel（`lib/hexagon-v*/unsigned/libQnnHtpV*Skel.so`）**平铺**进 `jniLibs/arm64-v8a`
  —— QNN 期望扁平布局，不认 `hexagon-v81/` 子目录
- `cleanQnnJniLibsLayout` 删历史遗留的嵌套目录

**打包方式的前提（改它等于静默弄坏 Genie）**：`useLegacyPackaging = true`
（⇒ manifest `extractNativeLibs=true`），so 会被解压成 `nativeLibraryDir` 下的**真实文件**。
改成 `false` 后 APK 内 so 只以未压缩条目存在，host 侧 adsprpc 的 `fopen` 找不到 skel，
`GenieDialog_create` 会在 ~300 ms 后失败且日志里只有这一句。详见 §4.4。

## 2. 模型目录（目录型）

```
qwen1.5b-genie/
├── genie_config.json
├── tokenizer.json
├── *.bin            # HTP context binary（一个或多个）
└── metadata.json    # 可选：genie.chat_template 角色前后缀
```

导出工具：Qualcomm AI Hub / `qnn-*` 转换脚本（参考 `$AI_HUB_APPS_ROOT` 下的 `chatapp_android`）。
校验规则与浏览器过滤见 `docs/MODEL_PATHS.md`。

**实测模型实例**（调研用，SM8750 导出 → 在 SM8850 上可跑）：

```
/storage/emulated/0/models-llm/genie/qwen3_4b_instruct/
├── genie_config.json              # dialog: context/tokenizer/engine(backend+model)
├── tokenizer.json
├── metadata.json
├── htp_backend_ext_config.json    # ⚠ 写的是 soc_model:69 / dsp_arch:"v79"（见 §8）
├── text-generator.json            # AI Hub 产物
├── tool-versions.yaml             # ⚠ 写的是 qairt: 2.45.0（app 跑 2.50，见 §8）
├── genie-app-script.txt
└── qwen3_4b_instruct_2507_w4a16_part_{1..4}_of_4.bin   # 4 份 ctx-bin
```

## 3. SoC 支持与 dsp_arch

- **SoC 限制**：仅骁龙 HTP。非骁龙 `probe()` → `UnsupportedSoc`，UI 灰显且不崩。
- **SoC → dsp_arch**（以 **QAIRT SDK 支持表**为准，不是 chatapp 的表 —— 两者对 SM8850 不一致）：

  | SoC | soc_id | HTP arch | htp_config asset |
  |-----|--------|----------|------------------|
  | SM8850 (8 Elite Gen 5) | 87 | V81 | `qualcomm-snapdragon-8-elite-gen5.json` |
  | SM8750 (8 Elite) | 69 | V79 | `qualcomm-snapdragon-8-elite.json` |
  | SM8650 (8 Gen 3) | 57 | V75 | `qualcomm-snapdragon-8-gen3.json` |
  | SM8550 (8 Gen 2) | 43 | V73 | `qualcomm-snapdragon-8-gen2.json` |

  **2026-09-29 真机实证（BKQ-AN80 / SM8850 / Android 17）**：用 QAIRT 自带的
  `bin/aarch64-android/qnn-platform-validator --backend dsp --coreVersion` 在设备上跑，输出
  `Core Version of the backend DSP: Hexagon Architecture V81` → **SM8850 = V81 确认**。
  同一台机器 `--testBackend` **Passed**（`QNN is supported for backend DSP on the device`），
  说明 fastrpc / unsigned PD / skel 加载 / DSP 侧执行这条链在本机完全正常。
  上游 `chatapp_android` 把 SM8850 映射到 `qualcomm-snapdragon-8-elite.json`（soc 69 / v79）
  （`MainActivity.java:187`、`:338`）是**错的**（至少对本机不适用），**不要照抄那张表**。

  表在 `GenieConfigResolver.SOC_TO_HTP`，键是 `Build.SOC_MODEL`。htp_config 首次使用时从
  `assets/htp_config/` 复制到 `filesDir/htp_config/`，再作为 `backend.extensions` 写进 dialog config。

- **APK 只打一个 arch**：非 release variant 默认只保留 `libQnnHtpV81{Skel,Stub}.so`（dev 机 SM8850）；
  release variant 保留 SDK 全部 arch 供 GitHub Release。覆盖：`-Pdroid.qnnHtpVersions=all|79,81`

### 3.1 不要靠推断，用 validator 取权威值

`soc_id` / `dsp_arch` 这两个数字**不能靠"查表 + 猜"**：上游 Qualcomm 自己的仓库就写错了
（把 SM8850 当 v79）。判定方法固定在 §9.1 —— 一条命令拿到设备实际 DSP 架构，1 分钟出结论。
**新机型接入前先跑它**，否则很容易把 arch 写错、再去查完全不相干的崩溃原因。

## 4. 调研：Genie 启动失败的两个缺口

### 4.1 调研起点与时间线

| 时点 | 事件 | 现象 |
|---|---|---|
| 起点 | 用户提供 app 诊断日志（`GenieDialog_create failed`） | **优雅失败**：`load FAILED after 303ms` |
| 阶段一 | 定位为 skel 找不到 → 加 `QnnEnv`（§4.4） | 编译通过，`dexdump` 确认符号入包；**此时未真机验证** |
| 阶段二 | 用户在 BKQ-AN80 真机测试，**启动即闪退** | 换成了 **native 段错误**（§4.5） |
| 阶段二续 | 复现 + 抓实时 QNN 日志 | 发现 skel **已成功加载** → 阶段一的修复是有效的，问题换了层 |
| 阶段二终 | validator 排除设备问题 → `genie-t2t-run` 复现 → A/B 锁定缺库 | **`EXIT=0`，模型正常生成** |
| 验收 | app 侧装新包实测 | `ttft=77ms` / `decode=97.25 tok/s` / 回复正确 / 零崩溃（§4.6） |

**关键教训**：阶段一改完就宣称"修好了"是错的 —— 它只解决第一层，第二层被完全遮住。
凡是 native 加载链上的修复，**必须真机跑到「模型能出字」才算闭环**。

### 4.2 症状一：`GenieDialog_create failed`（skel 找不到）

引擎层只有一句无信息量的报错：

```
E/engine/genie: load FAILED model=qwen3_4b_instruct after 303ms | RuntimeException: GenieDialog_create failed
```

真因在 native 段（app 进程自己的 logcat，tag 是包名）：

```
get_handle_priority_from_uri: libQnnHtpV81Skel.so lib opened handle without priority token
apps_std_fopen_fd failed for ./libQnnHtpV81Skel.so            (No such file or directory)
apps_std_fopen_fd failed for /system/lib/rfsa/adsp2/cdsp/…    (No such file or directory)
apps_std_fopen_fd failed for /vendor/dsp/cdsp/…               (Permission denied)
      … 共十几个搜索路径全失败 …
dlopen_ex failed for libQnnHtpV81Skel.so (flags 258)                       ← CDSP 侧
open_mod_table_open_dynamic failed for file:///libQnnHtpV81Skel.so?…&_dom=cdsp
remote_handle_open_domain: dynamic loading failed … on domain 3
```

**判读要点**：那一串搜索路径**全是设备系统目录 + `./`，没有一个 app 私有目录** ——
这就是「`ADSP_LIBRARY_PATH` 从未被设置」的直接证据。

### 4.3 机制：adsprpc 如何定位 skel

`libQnnHtpV<arch>Skel.so` **不是**由 app 的 linker 加载的：是 host 侧 **adsprpc**（跑在 app 进程内）
先在文件系统里把它找出来，再喂给 CDSP。它的搜索列表 = 一批系统路径
（`/vendor/dsp/cdsp`、`/system/lib/rfsa/adsp`、`/odm/firmware`、`./` …）**加上 `$ADSP_LIBRARY_PATH`**。
retail 机的系统路径里没有 QNN skel，且 untrusted app 读不了 `/vendor/dsp` → DSP 侧 `dlopen_ex` 失败
→ `GenieDialog_create` 约 300 ms 后返回失败。

所以有两个等价的可修点：**设 `$ADSP_LIBRARY_PATH`**，或**让进程 cwd 就是库目录**（官方教程走后者，
见 §10）。APK 场景下 cwd 不可控，只能设环境变量。

### 4.4 修法（缺口一）

```kotlin
// engine/genie/.../GenieNative.kt
internal object QnnEnv {
    private val applied = AtomicBoolean(false)
    fun ensure(nativeLibDir: String) {           // = ApplicationInfo.nativeLibraryDir
        if (!applied.compareAndSet(false, true)) return
        Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, true)
        Os.setenv("LD_LIBRARY_PATH", nativeLibDir, true)
    }
}
```

在 `GenieEngine.ensureNative()` 里、**首次触碰 `GenieNative` 之前**调用（`probe()` 与 `load()` 都经过它）。

三条硬约束：

1. **必须先设再加载 so**。`GenieNative` 是 Kotlin `object`，一碰它就 `System.loadLibrary("genie_chat_jni")`；
   若把 `ensure()` 写成它的成员，object 初始化顺序（先跑 `init` 再执行方法体）会让设置失效
   → 所以 `QnnEnv` 与 `GenieNative` **分成两个 object**。
2. **前提是 `useLegacyPackaging = true`**（⇒ manifest `extractNativeLibs=true`），so 会被解压成
   `nativeLibraryDir` 下的**真实文件**，app 自己可读。
   **若哪天改成 `false`，这条修复会静默失效**（skel 只存在于 APK 内，host 侧 fopen 找不到）。
3. **`/vendor/lib/rfsa/adsp` 的 push 方案不做**：需要 `adb root` + 写 vendor 分区，retail 机不可用。

对照上游：`chatapp_android` `src/main/java/com/quicinc/chatapp/Conversation.java:60-62`
同样是 `Os.setenv("ADSP_LIBRARY_PATH", nativeLibraryDir, true)` + `LD_LIBRARY_PATH`，
且同样用 `jniLibs.useLegacyPackaging = true`（`build.gradle:142`）。

**只有 Genie 需要这个变量**：llama.cpp 的 `GGML_HEXAGON` 默认 OFF 且本仓库未开，其 HTP 路径不涉及。

### 4.5 症状二：`libQnnHtpNetRunExtensions.so` 未打包 → `GenieDialog_create` 段错误

缺口一修好后 `GenieDialog_create` 仍然失败，但换成了**纯 native 段错误**：

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 (read)
Cause: null pointer dereference
    tid 6445, name: DefaultDispatch              ← QNN HTP 自己的线程，不是 app 的
    #14 libGenie.so (GenieDialog_create+484)     ← PC 偏移 0x7cf9cc，三次崩溃完全一致
    #15 libgenie_chat_jni.so (GenieNative_nativeCreate+264)
```

判据（用来和别的问题区分）：

- **早于模型加载**：tombstone 内存映射里**没有任何** `.bin` / tokenizer，日志里也没打开过模型文件；
  崩溃前只有 13.5 MB（skel 上传）+ 1 MB 的 DMA 分配。
- 时序：skel 在 CDSP 上 `remote_handle64_open` 成功 → `dspqueue_create: created Queue 0, …, DSP 0x00000000`
  → **同一毫秒** SIGSEGV。
- QNN 自己没有任何错误输出，**无 abort message** → 静默 null deref。
- 三次崩溃（22:00 / 22:01 / 22:08）**PC 偏移完全一致** → 确定性崩溃，不是偶发。

**真因：APK 里缺 `libQnnHtpNetRunExtensions.so`。** 官方 Genie Android HTP 教程要求推它
（`docs/QAIRT-Docs/Genie/general/tutorials/dialog/*/htp/android/`），但 `chatapp_android` 的
`requiredLibs` 与本项目原来的 `copyQnnJniLibs` **都没有包含它** —— 这是一条上游也踩的坑。

**A/B 证据**（同一配置，唯一变量是该库在不在，用 §9.2 的 CLI）：

| 条件 | 输出 |
|---|---|
| 无 `libQnnHtpNetRunExtensions.so` | `[ERROR] Unable to load backend extensions lib: [libQnnHtpNetRunExtensions.so]` → `[WARN] Failure in initializing backend extensions.` → `[INFO] Using create From Binary` → `Segmentation fault`，**`EXIT=139`** |
| 有它 | **`EXIT=0`**，模型正常生成 token |

**修法**：`engine/genie/build.gradle.kts` 的 `copyQnnJniLibs` 的 `include(...)` 加一行
`"libQnnHtpNetRunExtensions.so"`。app 内它由 Genie 用 `dlopen` 按名字加载，
放在 `nativeLibraryDir`（`useLegacyPackaging = true`）即可被 app 命名空间找到；
arch 裁剪只匹配 `libQnnHtpV<数字>{Skel,Stub}.so`，**不会误删它**。

**这是 Qualcomm 的 bug**：扩展库加载失败时 Genie 只 warn、不返回错误，随后解引用空指针。
所以 app 侧日志里看不到任何线索 —— 排查时必须用 §9.2 的 CLI 才有输出。

### 4.6 排障过程中已排除的假设（全部实测，非推断）

| 假设 | 排除依据 |
|---|---|
| skel 没打进 APK | 当轮安装的 APK 内确有 `lib/arm64-v8a/libQnnHtpV81Skel.so`（13,546,372 B） |
| arch 裁错（打成 V79 等） | APK 内 Skel **只有 V81**，与 §3 表一致；且 validator 报设备就是 V81 |
| 权限 / PD 问题 | 日志里 `remote_session_control Unsigned PD enable 1 request` **成功**（retail 机走 unsigned PD 正常）；之前那条 `remote_handle_control_domain … Permission denied` 是签名 PD 的常规首次失败 |
| 模型 / htp_config 有问题 | 失败发生在 skel 加载阶段，早于 context binary 与 tokenizer 加载 |
| 崩溃用的是旧包 | `dumpsys package` → `lastUpdateTime=22:00:11`，崩溃在 `22:00:26` → **确认是含 `QnnEnv` 的新包** |
| native 库混版 | APK 内各库与 QAIRT SDK 2.50.0.260828 **尺寸逐项一致**：`libGenie.so` 11,273,688 / `libQnnHtp.so` 3,978,976 / `libQnnHtpV81Stub.so` 816,184 / `libQnnSystem.so` 4,068,024 |
| 打包的库集与上游不一致 | 与 chatapp `CopyQnnLibs` 的 `requiredLibs` **逐项一致**（唯一差别就是缺 NetRunExtensions） |
| 打包 / 权限问题（skel） | adsprpc 已从 `nativeLibraryDir` 成功打开并上传 CDSP（§4.5 时序） |
| 模型或 htp_config **结构**有问题 | 崩溃早于模型读取；htp 资产与 chatapp 的 `…8-elite.json` **结构逐字段相同** |
| 模型自带 ctx-bin 是 v79 导致 | 本机 v81 **能加载并跑通**该 v79 ctx-bin（§4.5 A/B 的 `EXIT=0` 那侧） |
| 设备平台本身有问题 | `qnn-platform-validator --testBackend` 在本机 **Passed**（§9.1） |

### 4.7 真机最终验收（2026-09-29 / BKQ-AN80 / SM8850 / Android 17）

两个修复都在包里 → 点「启动」：

| 项 | 实测值 |
|---|---|
| 加载 | `load ok model=qwen3_4b_instruct in 6710ms handle=GenieSession` |
| 进程 | 存活 120 s+，crash 缓冲 **0** 条 |
| UI 状态 | 按钮「启动」→「停止」；输入框提示 →「输入消息…」 |
| 发送 `Hi` 的回复 | `Hi! 😊 I'm here to help you with whatever you need. Could you please clarify your question or let me know how I can assist you? 😊` |
| 指标 | `ttft=77ms`、`decode=97.25 tok/s`、`generatedTokens=132`、`total=1467ms`、`rss=325MB` |
| warnings | 4 条，均属 §7 既有已知偏差（`threads ignored` / `seed not set` / 流式回调为文本片段 / 空行） |

## 5. 已知偏差

- `threads` / `seed` **不适用**（HTP 自行调度；sampler 走 `genie_config.json`），记入 `EngineMetrics.warnings`
- `backend` 仅 `NPU_HTP` / `AUTO`
- 空回复：`GenieDialog_reset` + 重试（≤2 次），仍空则 `GenerateFailed`
- 多轮：`GenerateRequest.messages` 为准；历史与 dialog 已持有的一致时走增量 query，否则 reset + 全量重放
- `promptTokens` 上报为 **0**：Genie 不回报 prompt token 数（实测 `promptTokens=0`、`generatedTokens` 正常）

## 6. 模板（Jinja / minja）

**全部是纯字符串拼接，没有 jinja 引擎，也没做 JNI**（与 chatapp 一致）。角色前后缀从模型目录的
`metadata.json` 取（AI Hub 的 chatapp 布局），但解析是**朴素字符串扫描**，不是 JSON 解析：
找 `"<key>": "…"` 的**首次出现**，取冒号后第一对引号之间的内容。

| 键 | 默认值 |
|---|---|
| `system_prefix` / `system_suffix` | `""` / `"\n"` |
| `user_prefix` / `user_suffix` | `"user: "` / `"\n"` |
| `assistant_prefix` | `"assistant: "` |

> 易踩点：因为是**全文首次出现**匹配，键名若在 `metadata.json` 别处先出现（例如模板字符串内部）
> 会被取到 —— 且失败时**静默回退**到默认值，不报错。文件不存在或读取失败同样走默认值。

渲染规则（`GenieEngine.formatFullPrompt` / `formatUserTurn`）：

- system 段**只在 `messages` 里真有 `SYSTEM` 时**才产出 `systemPrefix + 内容 + systemSuffix`；
  系统提示词由 App 侧注入（设置 → 系统提示词），引擎不做隐式补充
  （实测：`promptChars=[system:28,user:12]`，system 段确实进了 prompt）
- 完整重放时：SYSTEM → USER（`userPrefix…userSuffix` + `assistantPrefix`）→ ASSISTANT（写正文前
  先摘掉挂起的 `assistantPrefix`），末尾无条件补 `assistantPrefix`
- 增量轮次（dialog 已持有历史）**只发新的 user turn**，不含 system 段

## 7. 诊断日志与符号共存

- 引擎调用统一经 `EngineLogging.wrap(impl)` 装饰器（`:engine:genie` 的 Hilt 模块用
  `@Provides @IntoSet`）。**改回 `@Binds` 会静默丢日志且照常编译通过** —— 见 `docs/DIAGNOSTICS.md`。
- Genie **无 native 日志回调**：上游 `chatapp_android/.../GenieWrapper.cpp` 只有 `__android_log_print`，
  QAIRT 也未提供 hook → native 段（QNN 的 `apps_std_fopen` / `dlopen_ex` 系列）只能靠
  「读本进程 logcat」拿到，也就是 `docs/DIAGNOSTICS.md` 里的 `EngineLogcatCapture`。
  **§4.2 那份证据就是这样抓到的。**
  **推论**：Genie 自己的 `[ERROR]/[WARN]` 只走它内部的 log callback（`genie-t2t-run` 打到 stdout），
  app 这边没有接 → **凡是 Genie 主动报的错，app 日志里一定看不到**。所以 §4.5 那条只能靠官方 CLI 才抓到。
  排查顺序：**先跑 §9 的 CLI，再看 app logcat**。
- 共存：`libGenie.so` / QNN 与另三家 so 同进程，接入或升级后要跑 `EngineCoexistenceTest`
  （契约见 `docs/ENGINE_INTEGRATION.md`「符号冲突与共存」）。

## 8. 模型侧的两处不一致（与启动无关，但会挡后面的路）

调研中在设备模型目录里发现两组与 app 环境不匹配的事实，**都尚未验证是否有实际影响**：

| 项 | 模型侧 | app / 设备侧 | 现状 |
|---|---|---|---|
| QAIRT 版本 | `tool-versions.yaml` = `qairt: 2.45.0.260326154327` | app 跑 **2.50.0.260828** | QNN context binary 跨版本兼容性未测 |
| `htp_backend_ext_config.json` | `soc_model: 69` / `dsp_arch: "v79"`（SM8750），4 个 ctx-bin 也是 v79 | 设备是 SM8850 / **v81** | **能加载并跑通**（§4.7），但跨 arch 是否始终安全未证实 |
| 上游 arch 映射 | chatapp 把 SM8850 → `…8-elite.json`（v79） | validator 实测 = V81 | **上游写错**，本项目映射正确 |

**app 侧的兜底行为**：`GenieConfigResolver.patchBackend` 会把 `genie_config.json` 的
`backend.extensions` 改写成 app 自己的 `filesDir/htp_config/<asset>.json`（v81），
所以模型自带的 v79 扩展配置**不会生效**；但 **ctx-bin 本身仍是 v79 的** —— 这一层绕不开。

## 9. 排障工具箱（可复用配方）

设备上留了一套（`/data/local/tmp/qnnval/`，约 130 MB，**有意保留**以便下次回归；
不需要时 `adb shell rm -rf /data/local/tmp/qnnval`）。以下命令均以该目录为例。

**准备（推库）**：

```bash
Q="$QAIRT_PATH"; DEV="<adb -s 目标>"; D=//data/local/tmp/qnnval
adb -s "$DEV" shell mkdir -p $D
adb -s "$DEV" push "$Q/bin/aarch64-android/qnn-platform-validator" \
                  "$Q/bin/aarch64-android/genie-t2t-run" \
                  "$Q/lib/aarch64-android/libGenie.so" \
                  "$Q/lib/aarch64-android/libQnnHtp.so" \
                  "$Q/lib/aarch64-android/libQnnSystem.so" \
                  "$Q/lib/aarch64-android/libQnnHtpV81Stub.so" \
                  "$Q/lib/aarch64-android/libQnnHtpV81CalculatorStub.so" \
                  "$Q/lib/aarch64-android/libQnnHtpNetRunExtensions.so" \
                  "$Q/lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so" \
                  "$Q/lib/hexagon-v81/unsigned/libCalculator_skel.so" \
                  $D/
adb -s "$DEV" shell "chmod 755 $D/*"
```

> `adb shell` 里 `/data/...` 必须写 `//data/...`，否则 Git Bash 把它转成 `D:/dev/git/data/...`。
> 同理取文件用 `adb exec-out cat`，**不要用 `adb pull`**。

### 9.1 `qnn-platform-validator` —— 取权威 arch + 判设备是否正常

```bash
adb -s "$DEV" shell "cd /data/local/tmp/qnnval \
  && LD_LIBRARY_PATH=/data/local/tmp/qnnval ADSP_LIBRARY_PATH=/data/local/tmp/qnnval \
     ./qnn-platform-validator --backend dsp --coreVersion --testBackend"
```

预期输出（本机实测）：

```
Core Version of the backend DSP: Hexagon Architecture V81
Unit Test on the backend DSP: Passed.
QNN is supported for backend DSP on the device.
```

**这条命令把「我们的问题」和「设备/QNN 的问题」一刀切开**，是最该先跑的一步。

踩坑（两个都实际踩过）：

- 漏 `libQnnHtpV81CalculatorStub.so` → 单元测试阶段失败
- 漏 `lib/hexagon-v81/unsigned/libCalculator_skel.so` → `Error while executing the sum function`（error -6），
  此时日志里搜索列表**已出现 `/data/local/tmp/qnnval/`** —— 反过来证明 `ADSP_LIBRARY_PATH` 机制生效

### 9.2 `genie-t2t-run` —— 绕开 app 复现（唯一能看到 Genie 报错的方式）

**配置必须从设备上真实的 `genie_config.json` 拉下来改，不要手搓**（保真：字段多且版本敏感）：

```bash
# 1) 拉真实配置
adb -s "$DEV" exec-out cat //storage/emulated/0/models-llm/genie/qwen3_4b_instruct/genie_config.json \
  > genie_config.device.json
# 2) 改三处为绝对路径：backend.extensions / tokenizer.path / model.binary.ctx-bins[]
# 3) 推回去后运行
adb -s "$DEV" shell "cd /data/local/tmp/qnnval \
  && LD_LIBRARY_PATH=/data/local/tmp/qnnval ADSP_LIBRARY_PATH=/data/local/tmp/qnnval \
     ./genie-t2t-run -c genie_app_htp.json -p 'Hi'; echo EXIT=\$?"
```

- 缺 `libQnnHtpNetRunExtensions.so` 时的输出：`[ERROR] Unable to load backend extensions lib` →
  `[WARN] Failure in initializing backend extensions.` → `[INFO] Using create From Binary` →
  `Segmentation fault`，`EXIT=139`
- 补齐后：**`EXIT=0`**，正常生成（首次跑要读 3.1 GB ctx-bin，**2 分钟以上**，别以为是卡死）
- A/B 手法：`mv libQnnHtpNetRunExtensions.so ._hidden.so` → 跑 → 移回，唯一变量、可复现

> ⚠️ CLI 与 app 的生成质量**不等价**：CLI 那次输出复读且跑到 `Context Size was exceeded` 才停，
> app 侧同一模型正常停（§10 末）。**CLI 只用来验启动链路，不用来验生成质量。**

### 9.3 取证手法与工具坑

| 目标 | 手法 | 坑 |
|---|---|---|
| tombstone | `adb exec-out cat //data/tombstones/tombstone_XX` | Git Bash 路径转换；`//data` 双斜杠 |
| 找最新 tombstone | `ls -lt //data/tombstones/` | 按时间排序，别按编号猜 |
| 完整 native 日志 | `logcat -v threadtime -b main,system,crash` | **主 buffer 只有 256 KiB**，崩溃窗口很快被冲掉 → 复现时**先起 logcat 再操作** |
| 设备侧 buffer | `logcat -g` 查大小 | 本机 Honor **不支持 `-b stdout`**，会直接报错 |
| app 私有目录 | `run-as <pkg> ls -l files/` | debug 包名带 `.debug` 后缀 |
| app 自己的诊断日志 | `run-as <pkg> cat files/logs/droidllm-log.txt` | 它只记 app 层事件，**native 崩溃不进这个文件** |
| 判断装的是不是新包 | `dumpsys package <pkg> \| grep -E "lastUpdateTime\|versionName"` | 与崩溃时间戳对比，避免"拿旧包分析新问题" |
| 提取 so 里的配置键名 | Python `re.findall(rb'[ -~]{4,}', open(so,'rb').read())` | **Git Bash 下 `strings` 不可用** |
| 拉取崩溃时段的 QNN 序列 | 按 pid 过滤：`grep -E " <pid> "` | 设备噪声极多（Honor），必须按 pid + 关键字双过滤 |

## 10. 上游与官方对照

三份参考实现的做法差异（**这正是能找到缺库的线索来源**）：

| 来源 | 位置 | skel 定位方式 | htp_backend_ext 配置 | NetRunExtensions |
|---|---|---|---|---|
| **本项目** | `:engine:genie` | `Os.setenv(ADSP_LIBRARY_PATH)` ✅ | 显式 `soc_model`/`dsp_arch`（v81 资产） | ✅ **已补**（§1 列表） |
| 上游 chatapp | `$AI_HUB_APPS_ROOT/chatapp_android`，`Conversation.java:60-62` | 同左 ✅ | 显式（**v79，对 SM8850 写错**） | ❌ 缺 —— 与我们一起踩坑 |
| 官方 Genie Android HTP 教程 | `$QAIRT_PATH/docs/QAIRT-Docs/Genie/general/tutorials/dialog/*/htp/android/` | **不设环境变量**：推库到同一目录后 `cd` 过去，靠 cwd 的 `./` 命中 | 推 `htp_backend_ext_config.json` | ✅ **教程明确要求推它** ← 这就是真因线索 |
| 官方配置样例 | `$QAIRT_PATH/examples/Genie/configs/` | — | **极简**，只有 `{"devices":[{"cores":[{"perf_profile":"burst","rpc_control_latency":100}]}]}`，**不写 `soc_model`/`dsp_arch`**（交给后端探测） | — |

**从对照里得出的两条可用结论**：

1. **官方教程要求推 `libQnnHtpNetRunExtensions.so`，而两家 Android app 都没打它** ——
   说明这是「app 场景特有的坑」，纯 CLI 教程不会暴露、只有真的打进 APK 才踩到。
2. **官方 `htp_backend_ext_config.json` 极简**（不写 soc/arch）与 chatapp 的显式写法相反。
   本项目的 htp 资产是显式版（v81）。**两种都还没做 A/B** —— 见 §11 待验证。
   若后续遇到 HTP 初始化类问题，这是第一个该做的对照实验。

## 11. 未做 / 待验证

**生成质量 / EOS（与启动问题无关，单独查）**

- CLI 试跑会复读并跑到 `Context Size was exceeded`，app 侧同一模型正常停（§9.2 末）。
  差异应来自 app 侧覆盖了 sampler/context 参数 → **EOS 停止条件与生成质量仍需系统验收**。

**配置层次（未做逐项对照实验）**

- `genie_config.json` 的 `dialog.engine` / `dialog.sampler` 与 App 侧覆盖的**最终优先级**，
  只按 chatapp 的写法对齐，没做过逐项实验
- `htp_backend_ext_config.json` 显式写 `soc_model`/`dsp_arch` **vs** 官方极简版，**未做 A/B**

**跨环境兼容（都只有单点实测）**

- 模型是 **QAIRT 2.45** 导出、app 跑 **2.50** —— ctx-bin 跨版本兼容性未测
- 模型自带 htp 配置写 **soc 69 / v79**、ctx-bin 也是 v79，在 **v81** 机器上**能跑通**
  （§4.7），但**跨 arch 是否始终安全未证实**
- 其它 arch（V79/V75/V73）只在 release variant 打包，**未在对应真机上验证过**

**其它**

- `libQnnHtpNetRunExtensions.so` 是否**只**在 Genie 路径需要（本项目其它引擎不走 QNN，
  但没验证过删掉它对别家有无影响）
- `promptTokens` 恒为 0（§5），Genie 是否真有接口能拿到，未查

## 12. 调研证据归档

`.workbuddy/evidence/2026-09-29-genie-crash/`（`.workbuddy/` 已 gitignore）：

| 文件 | 内容 |
|---|---|
| `tombstone_05-crash1.txt` / `_06-crash2.txt` / `_08-crash3-repro.txt` | 三次段错误的完整 tombstone（PC 偏移一致） |
| `qnn-init-window.log` | 崩溃前 100 行 native 序列（skel 打开 → dspqueue → SIGSEGV） |
| `logcat-crash-buffer.txt` | crash 缓冲区原始输出 |
| `model-metadata.json` | 设备上模型的 `metadata.json` |
| `fix-verified-end2end.log` | **修复后**真机端到端验证日志（`load ok` + 生成指标） |
| `genie-cli/` | CLI 复现用的四份配置：`genie_config.device.json`（设备原件）、`htp_v81_app.json` / `htp_minimal.json`（两种 htp 配置）、`genie_app_htp.json` / `genie_min_htp.json`（对应 dialog 配置） |

设备侧另有 `/data/local/tmp/qnnval/`（§9 工具箱，有意保留）。

---

*最后更新：2026-09-29（两次真机调研 + 修复验证）*
