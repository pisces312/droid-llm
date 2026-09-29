# docs/DIAGNOSTICS.md — 诊断日志与崩溃收集

> 起因：LiteRT 在真机上「一直回复你好」、本机模拟器复现不出来，而 `adb logcat` 在用户手边没有
> PC 时取不到。这套基建的目标是**让用户自己把现场证据交出来**。
>
> 改动这块代码前，先读 §3（设计决策）与 §6（踩过的坑）。

---

## 1. 用户侧流程（就是 DoD）

```
设置 → 诊断 → 查看运行日志
  ├─ 开启记录（默认开）
  ├─ 回聊天页复现问题
  ├─ 返回日志页 → 抓取引擎日志（读本进程 logcat，拿 native 输出）
  └─ 分享日志 → 得到 .txt（含设备头 + 环形缓冲 + 引擎日志快照）
```

崩溃时多一步：日志页顶部会出现**崩溃报告卡片**（查看 / 分享 / 删除全部）。

- 日志文件：`filesDir/logs/droidllm-log.txt`
- 崩溃报告：`filesDir/crash/crash_<yyyyMMdd_HHmmss_SSS>.txt`（最多 5 份）
- 两条路径都写进了 `app/src/main/res/xml/file_paths.xml`，经 `FileProvider`
  （authority `${applicationId}.fileprovider`）以 `ACTION_SEND` + `text/plain` 分享，
  **用户侧不需要任何存储权限**。

---

## 2. 模块地图

| 件 | 位置 | 作用 |
|----|------|------|
| `EngineLogSink` / `NoopEngineLogSink` / `EngineLogging` / `LoggingLlmEngine` | `core/engine-api/.../engineapi/LoggingLlmEngine.kt` | 引擎契约侧的日志接口、进程级 sink 持有者、装饰器 |
| `DiagLogger`（+ `DiagEntry` / `DiagLevel`） | `core/common/.../common/diag/DiagLogger.kt` | 环形缓冲 2000 条 + 落盘 + logcat 镜像 |
| `DiagEngineLogSink` | 同上目录 | 把 `EngineLogSink` 桥到 `DiagLogger` |
| `EngineLogcatCapture` | 同上目录 | 读本进程 logcat（`main,system` + `crash` 两段），补 native 盲区 |
| `CrashReporter` | 同上目录 | `Thread.UncaughtExceptionHandler`，崩溃当时写报告 |
| `LogScreen` / `LogViewModel` / `CrashReportInfo` | `app/.../ui/diag/LogScreen.kt` | 日志页与崩溃卡片 |
| `DroidLlmApp.onCreate` | `app/.../DroidLlmApp.kt` | 挂载日志目录、装 sink、装崩溃处理器 |
| `file_paths.xml` | `app/src/main/res/xml/` | 只暴露 `logs/` 与 `crash/`，**不暴露模型文件** |

路由：`DroidLlmRoot.kt` 里 `composable("logs")`，**压在底部 tab 之上**而非新增第五个 tab
（日志是排障入口，不是日常功能）。

### 数据流

```
引擎 adapter（四家各自 @Provides @IntoSet）
        │  EngineLogging.wrap(impl)
        ▼
LoggingLlmEngine  ── safe {} 包裹每一处 sink 调用
        │  EngineLogging.current()
        ▼
DiagEngineLogSink
        ▼
DiagLogger ──► 环形缓冲 ArrayDeque(2000)  ──► StateFlow<List<DiagEntry>> ──► LogScreen
           └─► logcat 镜像（TAG `DroidLLM`）──► 可被 adb 或 EngineLogcatCapture 读回
           └─► flushToFile() 仅在点「分享」/ 退页时调用
```

---

## 3. 设计决策（改这里前必读）

1. **装饰器，而不是改四个 adapter。**
   调用方（Chat / 本地 API server / Benchmark）注入的是 `Set<LlmEngine>`，四家在各自的
   `companion object` 里 `@Provides @IntoSet` → `EngineLogging.wrap(impl)`，一处覆盖全部调用点。
   同时记录的是**跨引擎同形的元数据**（请求形状、模板可见的 prompt 长度、token 数、耗时、异常），
   这些 adapter 本来就已算出。

2. **sink 是进程级的，不走 DI。**
   把 `Set<LlmEngine>` 注入到「产出它的那个 module」会形成依赖环，所以 sink 走
   `EngineLogging.install()`，在 `Application.onCreate` 装一次。装之前默认 `NoopEngineLogSink`
   —— 这正是单测 / Preview 想要的（`LoggingLlmEngineTest.noSinkByDefault` 钉住）。

3. **日志绝不改变引擎行为。**
   `LoggingLlmEngine.safe {}` 包住全部 15 处 sink 调用。`generate` 会在**事件回调路径**上写日志，
   sink 一旦抛异常就会从 `onEvent` 漏出去，用户看到的是**一次假的推理失败**（或在 coroutine 里
   变成无关崩溃）。回归：`LoggingLlmEngineTest.survivesAThrowingSink`。
   → 同理 `DiagLogger` / `CrashReporter` 的公开入口全部 `runCatching`，「从不抛异常」是可验证契约。

4. **热路径不落盘。**
   `DiagLogger.log` 只 append 内存 + 打 logcat；`flushToFile()` 只在分享时调用
   （对齐 AGENTS.md「性能热路径禁止做文件 IO」）。缓冲有界（2000），所以常开只占内存、不无限增长
   —— 这也是开关能默认开的前提。

5. **只记元数据，不记用户正文。**
   prompt 记 `role:长度` 形状（`promptChars=[system:28,user:2]`），首 token 记**截断 + 转义**预览
   （`quote()`，60 字符上限、`\n`→`\\n`）。原始对话内容不进日志。

6. **开关持久化在 DataStore，但刻意不进设置导出包。**
   与 `modelRootPath` 同类，属设备侧偏好，跨设备导入无意义。`BundledSettings` 是显式字段白名单，
   不会自动带上新字段 —— 这是刻意的，不是遗漏。

7. **`LazyColumn` 的 key 必须用 `DiagEntry.seq`（自增身份），不能用内容派生。**
   详见 §6.3，这条**曾经让日志页必崩**。

8. **抓取结果绝不写回环形缓冲。**
   我们自己的日志已镜像到 logcat TAG `DroidLLM`，把抓取文本喂回缓冲，下次 dump 会把它重新捕获
   → 自我放大、无限增长。抓取是**游离快照**（`StateFlow<String?>`），只在
   `reportText(header, engineLog)` 拼接时出现。

9. **crash 段排在最后。**
   `EngineLogcatCapture.keepTail` 超限时**丢头部保尾部**，所以把最想要的崩段写在最后才留得住。

---

## 4. 为什么引擎内部日志只能靠读 logcat

四家里只有**一个**引擎提供真正的日志回调：

| 引擎 | 能否挂日志回调 | 事实依据 |
|------|----------------|----------|
| llama.cpp | ✅ `llama_log_set(callback, user_data)` | `include/llama.h:1515-1516` |
| MNN | ❌ | `MNN_PRINT` / `MNN_ERROR` 是编译期宏，写死 `__android_log_print(TAG="MNNJNI")`；全仓无 `SetLogHandler` |
| LiteRT-LM | ❌ | 闭源 AAR，无任何日志 hook（gallery 里只有 `MetricsLogger`，那是指标不是日志） |
| Genie | ❌ | `chatapp_android/src/main/cpp/GenieWrapper.cpp` 只有 `__android_log_print` |

要为 MNN/Genie 加回调就得改它们的 native 源码（还能改），LiteRT-LM 则完全做不到。
**读 logcat 是唯一不改任何人、且覆盖全部四家的做法。**

关键机制：**自 Android 4.1 起 App 可读自己进程的日志，无需 `READ_LOGS`**；logd 会把非特权读取者
收窄到自己的 uid，所以读不到别的 App。主机侧 `logcat | grep` 是反模式（每行都传上来再丢掉 99%）。

命令是**两次 dump**，而不是一次 `-b main,system,crash`：

```
logcat -d -b main,system -v time --pid=<ownPid> -t 4000
logcat -d -b crash       -v time                  -t 4000   ← 刻意不带 --pid
```

- `-d` dump 后即退出，契合「复现后再收」的节奏；`-t` 兜住行数，`EngineLogcatCapture` 再兜 512 KB
- **crash 那一趟绝不能加 `--pid`**，理由见 §6.4
- 实测抓到过 `MNNJNI`（`libMNN.so` 内部）、`llamacpp_jni`、`ndk_translation`
- **取全量而非 TAG 白名单**：白名单要穷举 MNN / litertlm 内部几十个 TAG，漏一个就丢证据。
  全量的额外好处是引擎段与 App 段**共用时间轴**，能把「App 发请求」和「native 内部发生了什么」对齐。
  代价是掺入 `OpenGLRenderer` / `Choreographer` 等 framework 噪声。

---

## 5. 崩溃覆盖：两条互不重叠的通路

| 崩溃类型 | 谁负责 | 时机 | 产物 |
|---|---|---|---|
| Java / Kotlin 未捕获异常 | `CrashReporter`（`Thread.UncaughtExceptionHandler`） | 崩溃**当时** | `filesDir/crash/crash_<stamp>.txt` |
| native SIGSEGV / SIGABRT | `EngineLogcatCapture` 的 crash 段 | **下次启动** | 日志里的 `----- crash -----` 段 |

- native 崩溃时进程当场死，**Java 层没有任何机会运行** → 此时 `files/crash/` 必然为空
  （实测确认）。这不是 bug，别再往 `CrashReporter` 里找原因。
- `CrashReporter` 的三条约束：**从不抛异常**（否则第二个异常会盖掉真崩溃）、
  **从不吞掉崩溃**（一定转交上一个 handler，让系统照常弹崩溃框；无上一个 handler 时才
  `killProcess` + `exitProcess(10)`）、最多 5 份（`prune()` 旧的先删）。
- `reporting` 是 `AtomicBoolean` **闩**而不是锁：处理器内部再失败会递归。
- 报告内容：设备头 + 异常堆栈 + （跟随诊断开关的）logcat 段。堆栈段**不受开关影响、永远写入**
  —— 它不含用户正文；logcat 段可能回显 prompt 正文，所以跟随开关。
- 文件名的毫秒戳会重复，`nextFile()` 撞了就加 `_1` / `_2` 后缀，**不覆盖**。

### 上游的 `crash_util.cpp`：官方代码，但上游自己弃用了

核实结论：它是 **MNN 官方代码**（作者 `ruoyi.sjd`，在 `upstream/master` 上），但
`MnnLlmChat/CrashUtil.kt` 里 `//seems not work fine for native crash` 下面就是**被注释掉的**
`initNative(...)` —— 那个 `sigaction` + `_Unwind_Backtrace` 处理器在上游是死代码。
上游实际生效的是 Kotlin 那一半：`UncaughtExceptionHandler` + dump `main` 与 `crash` 两个缓冲。

本项目采用同样的分工，**不移植那半 C++**（还要新建 native 库 + CMake + Gradle 接线，
而为一条上游已放弃的路径付这个成本不划算）。

> 判定「某个文件是不是上游的」的方法：`git show --format='%an %ae' <sha>` + `git remote -v` +
> `git cat-file -e upstream/master:<path>` + `git merge-base --is-ancestor <sha> upstream/master`。
> **别看 commit message 风格** —— `[LLM:Feature]` 那种也可能是自己写的。

---

## 6. 踩过的坑（全部为实测，不是推测）

### 6.1 四家引擎的 Hilt 绑定改法：`@Binds` → `@Provides`

四个 `:engine:*` 模块原先各自 `@Binds @IntoSet`。要包装饰器就改成 `companion object` 里的
`@Provides @IntoSet` → `EngineLogging.wrap(impl)`。

> **改回 `@Binds` 会静默丢掉全部引擎日志，而且照常编译通过**，没有任何编译期保护。
> 验证手段：`grep newSetBuilder app/build/generated/hilt/**/DaggerDroidLlmApp_HiltComponents_SingletonC.java`
> → 应为 `newSetBuilder(4)`。

### 6.2 `object` 里不能再嵌 `companion object`

写 `@Provides` 时顺手把常量塞进 `companion object` 直接编译失败。常量放 object 顶层即可。

### 6.3 LazyColumn 的 key 不能用内容派生 —— 曾让日志页必崩

`LogList` 最初用 `timestampMs + message.hashCode()` 作 key。问题在于**四个引擎会在同一毫秒输出
完全相同的 probe 结论**（`probe -> Available` / `UnsupportedSoc(ranchu)`），key 直接碰撞：

```
IllegalArgumentException: Key "1759xxxxxxx-1234567" was already used
```

→ **进日志页必崩**。现用 `DiagEntry.seq`（`AtomicLong` 自增）。

> 一般教训：**Compose 的 key 用身份（自增 id），不要用内容。**
> 回归：`DiagLoggerTest.seq stays unique for identical text written in the same millisecond`。

### 6.4 `--pid` 会精确过滤掉 tombstone

这一条最反直觉：**要给 crash 缓冲加 `--pid` 是本能的错误做法**。
tombstone 由 **`crash_dump` 这个独立进程**写入，行上的 pid 是 crash_dump 的，不是 App 的
（实测：App pid=5003，日志里是 `F/DEBUG ( 5298)`）。

API 34 实测：`-b crash --pid=<appPid>` → **0 行**；不带 `--pid` → **73 行**。

→ 按 pid 收窄会**精确删掉这里唯一想拿的东西**。这就是 `capture()` 拆成两次 dump 的原因。

### 6.5 sink 抛异常会击穿 `onEvent`，变成「假推理失败」

由 `LoggingLlmEngineTest.survivesAThrowingSink` 逼出：sink 抛出的异常从 `generate` 的事件回调里
漏出去，外观上就是一次推理失败。修法：15 处 sink 调用全部包 `safe {}`。

### 6.6 Compose 里「渲染了但点不到」—— bounds 是 `[0,0][0,0]`

日志页两张卡（控制卡约 87% 屏高 + 崩溃卡）叠加后 `Column` 溢出，第二张卡的按钮
**渲染存在但 bounds 为 `[0,0][0,0]`**：uiautomator 里看得见、点不到。

修法：卡片放进各自的滚动区 `weight(7f, fill = false)`，日志列表 `weight(1f)` 保留约 1/8
（与只有控制卡时一致）；**崩溃卡排在控制卡之前**（崩溃后它才是首要操作，而且它矮，
放在高控制卡下面会被挤出可视区）。

> 判据：`uiautomator dump` 里 grep 按钮的 `bounds`，**不要靠截图** —— 截图看不出可点击性。

### 6.7 IO 线程上构造 `Toast` 会抛

`LogViewModel` 里所有 Toast 调用点都在 `Dispatchers.IO` 块内，而 `Toast` 的构造函数在非主线程
会抛 `Can't toast on a thread that has not called Looper.prepare()`。
最讽刺的是：**能走到这条路径说明刚好出错了**，Toast 再崩一次就把用户真正需要看的信息盖掉了。
修法：`private val mainHandler = Handler(Looper.getMainLooper())`，`toast()` 里 `post {}`。

### 6.8 两个「差点静默错」的写法

- `Process.myPid()` 曾放在 `runCatching` **外面** → 破坏「从不抛异常」契约。已移入。
- 崩溃报告文件名的毫秒戳会重复，直接覆盖会**静默丢掉上一份**。已改为撞名加后缀。

### 6.9 测试里的异步陷阱

`FakeEngine.generate` 是异步的，断言不能立刻读日志 —— 需要 `awaitIdle(job)` 轮询 `job.isActive`。

### 6.10 验证「APK 里有没有新类」不能用文件大小

本项目 debug APK 两次构建**巧合地都是 142,503,574 B**。必须用
`dexdump -f classes.dex | grep <类名>`（或 `unzip -l` 看资源）来确认，**不要用文件大小判断**。

---

## 7. 验证与回归

单测（32/32 全绿）：

```powershell
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :core:engine-api:testDebugUnitTest :core:common:testDebugUnitTest"
```

| 测试 | 钉住的契约 |
|------|------------|
| `LoggingLlmEngineTest.passesEventsThroughAndRecordsGenerate` | 装饰器透传事件且确实记了 generate |
| `LoggingLlmEngineTest.identityIsDelegated` | `id` / `displayName` / `version` 原样代理 |
| `LoggingLlmEngineTest.survivesAThrowingSink` | sink 抛异常不影响推理 |
| `LoggingLlmEngineTest.noSinkByDefault` | 未 install 时是 no-op |
| `DiagLoggerTest.seq stays unique …` | LazyColumn key 不会碰撞（§6.3） |
| `DiagLoggerTest.seq keeps increasing across clears` | clear 后 seq 不回退，防 key 复用 |
| `DiagLoggerTest.clear empties the buffer` | — |
| `CrashReporterTest.report captures the thread, the exception and a logcat section` | 报告结构 |
| `CrashReporterTest.only the newest reports are kept` | 最多 5 份 |
| `CrashReporterTest.uncaughtException still hands the crash to the previous handler` | 从不吞崩溃 |
| `CrashReporterTest.listReports is newest first` | 顺序 |
| `CrashReporterTest.install resolves the report directory` | 测试缝可用 |

模拟器 API 34 实测（真机仍是最终验收）：

- Java 异常 → `files/crash/` 写出 2751 B 报告 ✅
- 人为 `SIGSEGV` → `files/crash/` **无**新文件（符合 §5 的设计）✅
- crash 缓冲在下次启动被读到 73 行 tombstone，其中 `F/libc: Fatal signal 11 (SIGSEGV)`
  带 **54 个带符号栈帧** ✅ —— 这是 native 崩溃唯一的证据来源
- 抓取前后 chars 176 → 21230

---

## 8. 边界与未做的事

- **不测性能**：日志只做现场取证，不做 benchmark（性能仍走 `MetricsCollector`，见
  `ENGINE_INTEGRATION.md`）。
- **不写用户正文**：见 §3.5。若将来必须记 prompt 正文，需要先在设置里做显式二次确认。
- **`diagnosticLogging` 关闭时**：`DiagLogger.log` 直接 return（连 logcat 镜像也没有）；
  `CrashReporter` 仍写堆栈，仅不附 logcat 段。
- **C++ `sigaction` 半未移植**：理由见 §5 末。
- **真机 DoD 待用户执行**：装 APK → 复现 LiteRT「一直回复你好」→ 抓取引擎日志 → 分享 `.txt`。
