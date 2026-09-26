# AGENTS.md — droid-llm

> Android 多引擎 LLM 统一聊天 + 轻量 benchmark。包名 `io.github.pisces312.droidllm`，显示名 **DroidLLM**。

## 权威文档

| 文档 | 作用 |
|------|------|
| `DESIGN.md` | 设计决策唯一权威。与实施计划冲突时以 DESIGN.md 为准 |
| `IMPLEMENTATION.md` | 可执行实施计划、阶段进度、DoD 清单。每阶段完成后更新 |

动手前先读 DESIGN.md §1.2（引擎接口契约）、§10（不可变默认决策）、§6（符号冲突对策）。

## 本机环境

| 项 | 位置 / 说明 |
|----|-------------|
| 工作目录 | `D:\my-projects\droid-llm`（会话 cwd 可能过期，Bash 必须显式传 `workdir`） |
| JDK | `D:\dev\AndroidStudio\jbr`（构建时设 `JAVA_HOME`） |
| Android SDK | `D:\dev\android_sdk`（`local.properties` 已写 `sdk.dir`） |
| NDK / CMake | 27.0.12077973 / 3.22.1（SDK 内） |
| Gradle | wrapper 8.13（Tencent 镜像）+ AGP 8.13.2 |
| 构建命令 | `cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :app:assembleDebug"` |
| GitHub | 直连易 reset，用 `https://gh-proxy.com/https://github.com/...` |
| MNN 预编译 | `droid.mnnRoot` 属性或 `MNN_ROOT` 环境变量，默认 `D:/3rd-party-projects/MNN`（`project/android/build_64/lib/libMNN.so`） |
| llama.cpp 源码 | 已 vendored 到 `third_party/llama.cpp/`（勿用 submodule） |

**目录约定**（用户全局规则）：开发工具 `D:\dev`，便携软件 `D:\software`，本项目 `D:\my-projects`，第三方 clone `D:\3rd-party-projects`。修改 SDK 路径 / JDK / Gradle 配置前必须先问用户。

## 构建与验证

```powershell
# 完整 APK
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :app:assembleDebug"

# 单元测试
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :core:engine-api:testDebugUnitTest"

# 跳过 Genie native（QAIRT 未配置时）
# gradlew.bat :app:assembleDebug -PskipGenie=true
```

- 目标 ABI 仅 `arm64-v8a`，单 APK 全打，不做 Dynamic Feature。
- 真机 adb：`D:\dev\android_sdk\platform-tools\adb.exe`（不在 PATH）。
- Gradle 若因目录改名出现 "outside root directory"，先 `gradlew clean` 再构建。

## 模块地图

```
:app                     Compose UI（Chat / Models / Benchmark / Settings）
:core:engine-api         LlmEngine 统一接口、InferenceConfig、EngineEvent、FakeEngine
:core:common             ModelPathStore / MetricsCollector / DeviceProbe / ResultStore
:core:benchmark          评测调度（P4）
:core:chattemplate       ChatTemplate.format（fallback；minja JNI 在 P3）
:engine:litert           LiteRT-LM 适配器（纯 Kotlin + litertlm-android AAR）
:engine:mnn              MNN 适配器 + JNI（libMNN.so 预编译）
:engine:genie            Genie/QNN 适配器 + JNI（`-PskipGenie` 门控）
:engine:llamacpp         llama.cpp 适配器 + JNI（third_party 静态链入）
third_party/llama.cpp    vendored 源码树
```

## 关键契约（勿破坏）

1. **模型格式不互通**：每个引擎独立配置自己的模型文件/目录，不做「一份模型跑四家」。
2. **Backend 映射**见 DESIGN.md §1.2。不支持的 Backend **显式拒绝**，禁止静默回退。
3. **TTFT 口径**：从 `generate()` 请求发出到首个 token 回调；含 prefill，不含 load 与模板格式化。各引擎统一走 `MetricsCollector`，不得自行其是。
4. **单模型驻留**：同一时刻只加载一个模型，切换 = 卸载旧的。
5. **Session 线程安全**：generate / unload 互斥。
6. **符号隔离**：每个 native 库 `CXX_VISIBILITY_PRESET hidden` + `-Wl,--exclude-libs,ALL`；跨引擎 so 共存必须过 `EngineCoexistenceTest`。
7. **不做**：功耗测量、DFM、雷达图、质量评测、OpenAI 兼容 API（P5+ 才可选）。

## 阶段流程

1. 只做当前阶段（P0→P5），完成后更新 `IMPLEMENTATION.md` 进度表与交付说明。
2. **停下来等用户审阅**，不要自动进入下一阶段。
3. 真机 DoD 由用户手测；构建/单测 DoD 由执行者跑通。

## 脚本与输出

- 脚本（`.ps1` / `.bat`）默认写英文。
- 新建工程必须有 `.gitignore` 与 `AGENTS.md`。
- 密钥/口令不入库（keystore 口令等只存在于用户本地配置，勿写入仓库）。

## 常见坑

- PowerShell 正则/管道易被解析干扰 → 复杂扫描写临时 `.ps1` 或走 `cmd /c`。
- Kotlin `scope.isActive` 在 `launch` 内要 `ensureActive()`（`import kotlinx.coroutines.ensureActive`）。
- `when` 表达式里禁止自引用同一 `val`（用 `config.backend` 等）。
- MNN `Llm::createLLM(dir)` 需要目录内有 `config.json`；流式用 `response(ChatMessages, ostream)`。
- llama.cpp 用纯 `llama.h`（不链 `common`），batch 工具函数本地内联。
