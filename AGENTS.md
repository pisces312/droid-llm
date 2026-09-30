# AGENTS.md — droid-llm

> Android 多引擎 LLM 统一聊天 + 轻量 benchmark。包名 `io.github.pisces312.droidllm`，显示名 **DroidLLM**。

## 权威文档

| 文档 | 作用 |
|------|------|
| `DESIGN.md` | 设计决策唯一权威。与实施计划冲突时以 DESIGN.md 为准 |
| `UI_DESIGN.md` | 界面与交互权威（StreamClip + PixelPlayerOSS token） |
| `IMPLEMENTATION.md` | 可执行实施计划、阶段进度、DoD 清单。每阶段完成后更新 |
| `docs/<engine>.md` | **单引擎笔记**（现有 `docs/mnn.md`、`docs/llamacpp.md`、`docs/litert.md`、`docs/genie.md`）：该引擎专属的坑、实测结论、计时字段、调试手法 |
| `docs/mnn-pc-regression.md` | MNN PC 端回归：共享 core + `mnn_host_test`、模型落盘、Windows MNN host 构建、日常回归环 |
| `docs/llamacpp-decode-repro.md` | llama.cpp arm64 独立 decode 回归 CLI（`tools/llama_decode_repro`），native 重构后先跑 |
| `docs/DIAGNOSTICS.md` | 诊断日志与崩溃收集：用户侧流程、模块地图、设计决策、10 条实测坑、回归清单。**改诊断日志前必读** |
| `docs/MODEL_PARAMS.md` | 采样 / 执行参数的层次与键空间：两层结构（逐模型覆盖 → 引擎 `EngineDefaults`）、`"ENGINE:modelId"` 键、UI 语义、上游四项目对照调研、生效路径、验收清单。**改采样、设置存储、模型参数 UI 前必读** |
| `docs/I18N.md` | 中英双语：`values/`=英文兜底 + `values-zh/`、三态语言走 **AppCompat app-locale**（`locale_config.xml` + manifest `autoStoreLocales`；`MainActivity` 必须 `AppCompatActivity`，主题父级必须 AppCompat 后代）、**两条硬规则**（`NavHost.startDestination` 必须编译期常量、`popUpTo` 用 `findStartDestination()`）、各模块资源分布、占位符约定、**故意未抽取的白名单**。**新增/改 UI 文案前必读** |

**引擎诊断日志**（2026-09-29 加）：设置 → 诊断 → 查看运行日志，可在真机复现后直接分享 `.txt`。
链路 = `DiagLogger`（core:common）← `DiagEngineLogSink` ← `LoggingLlmEngine`（engine-api 装饰器）
← 四个引擎的 Hilt 绑定。**改 `:engine:*` 的注入绑定前先读 `docs/ENGINE_INTEGRATION.md`
「引擎诊断日志」一节**（把 `@Provides` 改回 `@Binds` 会静默丢日志，编译不报错）。

> **采样参数没有 App 级默认值**（2026-09-29 删）：只有「逐模型覆盖 → 引擎 `EngineDefaults`」两层，
> 逐模型覆盖的键是 `"<ENGINEID>:<modelId>"`。**改 `AppSettings` 采样字段、`ModelParamsStore`
> 或会话采样面板前先读 `docs/MODEL_PARAMS.md`**（含「为什么不能再加全局层」与上游四项目对照）。

**记录规则**：凡是指向单个引擎的坑与结论，写进对应的 `docs/<engine>.md`，不要堆进
`ENGINE_INTEGRATION.md`（那里只放依赖获取、编译开关、模型格式等通用信息）。

动手前先读 DESIGN.md §1.2（引擎接口契约）、§10（不可变默认决策）、§6（符号冲突对策）。

## 本机环境

| 项 | 位置 / 说明 |
|----|-------------|
| 工作目录 | `D:\my-projects\droid-llm`（会话 cwd 可能过期，Bash 必须显式传 `workdir`） |
| JDK | `D:\dev\AndroidStudio\jbr`（构建时设 `JAVA_HOME`） |
| Android SDK | `D:\dev\android_sdk`（`local.properties` 已写 `sdk.dir`） |
| NDK / CMake | 27.0.12077973 / 3.22.1（SDK 内） |
| Gradle | wrapper 8.13（Tencent 镜像）+ AGP 8.13.2；Kotlin 2.2.21 + KSP 2.3.6（litertlm 0.11.0 需要 ≥2.2 metadata） |
| 构建命令 | `cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :app:assembleDebug"` |
| GitHub | 直连易 reset，用 `https://gh-proxy.com/https://github.com/...` |
| 第三方仓库定位 | **只用环境变量 / gradle 属性，仓库内不写死本机绝对路径**。编译只需 `MNN_ROOT`（必需）+ `QAIRT_PATH`（可选）；其余参考用变量见「第三方仓库」A/B 两节 |
| llama.cpp 源码 | 已 vendored 到 `third_party/llama.cpp/`（勿用 submodule） |

**目录约定**（用户全局规则）：开发工具 `D:\dev`，便携软件 `D:\software`，本项目 `D:\my-projects`，第三方 clone 自定位置。修改 SDK 路径 / JDK / Gradle 配置前必须先问用户。**本机 clone 路径写在环境变量里（见下表），不要写进仓库。**

## 第三方仓库

本地位置用环境变量定位（本机已 `setx`），文档与构建脚本都不写死绝对路径。GitHub 直连不稳时用镜像前缀 `https://gh-proxy.com/`。

### A. 编译必需 / 可选（Gradle 会读）

| 环境变量（或 gradle 属性） | 指向 | 用途 | 缺失时 |
|------|------|------|------|
| `MNN_ROOT`（`droid.mnnRoot`） | MNN 源码树（含 `project/android/build_64/lib/libMNN.so`，需 `MNN_BUILD_LLM=ON`） | `:engine:mnn` 链预编译 `libMNN.so`、CMake 找头文件。git: `https://github.com/alibaba/MNN` | **直接报错**，无法构建 mnn 引擎 |
| `QAIRT_PATH` / `QAIRT_SDK_ROOT`（`droid.qairtSdkRoot`） | Qualcomm QAIRT/QNN SDK 发布包（非 git） | `:engine:genie` 链 `libGenie.so` + QNN HTP runtime | Genie native **自动跳过**（Kotlin 仍编译，UI 报 MissingDependency） |
| （无需环境变量） | `third_party/llama.cpp/` | llama.cpp 已 vendored，`add_subdirectory` 静态编入。上游: `https://github.com/ggml-org/llama.cpp` | — |

### B. 仅参考源码（Gradle **不读**，编译不需要）

这些变量只是本机参考 clone 的快捷定位，方便改代码时对照实现；**删掉也不影响构建**。

| 环境变量 | 参考项目（git） | 参考什么 |
|------|------|------|
| `GALLERY_ROOT` | `https://github.com/google-ai-edge/gallery` | LiteRT-LM 接入、Gallery 模型 allowlist / HF 下载 URL、`LlmChatModelHelper` |
| `MNN_LLM_CHAT_ROOT` | `https://github.com/alibaba/MNN` → `apps/Android/MnnLlmChat` | 模型市场（`model_market.json`）、HF/ModelScope 双源切换、下载器、目录扫描识别已下载 |
| `AI_HUB_APPS_ROOT` | `https://github.com/qualcomm/ai-hub-apps` | Genie `chatapp_android`：`genie_config.json` 解析、prompt tags、QAIRT 打包布局 |
| `CHATTERUI_ROOT` | `https://github.com/Vali-98/ChatterUI` | llama.cpp Android 集成与 GGUF 模型管理 |

## 构建与验证

```powershell
# Debug（applicationId 后缀 .debug，可与正式版并存）
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :app:assembleDebug"

# 正式版（读环境变量签名，勿写口令进仓库）
# KEY_STORE / KEY_STORE_LOCATION / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :app:assembleRelease"

# 单元测试
cmd /c "set JAVA_HOME=D:\dev\AndroidStudio\jbr&& set ANDROID_HOME=D:\dev\android_sdk&& gradlew.bat :core:engine-api:testDebugUnitTest :core:common:testDebugUnitTest"

# 跳过 Genie native（QAIRT 未配置时）
# gradlew.bat :app:assembleDebug -PskipGenie=true   (or -Pdroid.skipGenie=true)
```

- **QNN HTP arch 默认裁剪**：非 release variant **只打 v81**（dev 机 SM8850，见
  `docs/genie.md` §3 的 SoC→dsp_arch 表），release variant 保留 SDK 全部
  arch（GitHub Release 用）。覆盖：`-Pdroid.qnnHtpVersions=all` 或 `-Pdroid.qnnHtpVersions=79,81`。
- 目标 ABI 仅 `arm64-v8a`，单 APK 全打，不做 Dynamic Feature。
- debug：`io.github.pisces312.droidllm.debug` / 名称 `droid-llm debug`；release：`io.github.pisces312.droidllm` / 名称 `droid-llm`，可同机安装。
- 真机 adb：`D:\dev\android_sdk\platform-tools\adb.exe`（不在 PATH）。
- Gradle 若因目录改名出现 "outside root directory"，先 `gradlew clean` 再构建。

## 模块地图

```
:app                     Compose UI（Chat / Models / Benchmark / Settings）
:core:engine-api         LlmEngine 统一接口、InferenceConfig、EngineEvent（FakeEngine 在 src/test，仅测契约）
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
4. **单模型驻留**：同一时刻只加载一个模型，切换 = 卸载旧的（Settings 可开多模型驻留）。
5. **Session 线程安全**：generate / unload 互斥。
6. **符号隔离**：每个 native 库 `CXX_VISIBILITY_PRESET hidden` + `-Wl,--exclude-libs,ALL`；跨引擎 so 共存必须过 `EngineCoexistenceTest`。
7. **模型根目录迁移**（`ModelRootMigrator`）：**永不删除/覆盖目标已有文件**；重名交用户跳过或取消；原目录有数据询问是否迁移，可不迁移。
8. **导航不变量**：`NavHost` 的 `startDestination` **必须是编译期常量**（它是内部 `remember` 的 key，传可变值会让 NavGraph 每帧重建 → 死循环，见 `docs/I18N.md` §2 硬规则一）；`popUpTo` 一律 `graph.findStartDestination().id`；**不要再加「跨 recreate 记住当前 tab」的全局单例** —— `rememberNavController()` 自带 `saveState`/`restoreState`。
9. **不做**：功耗测量、DFM、雷达图、质量评测、OpenAI 兼容 API（P5+ 才可选）。

## MNN PC 回归（改 native 后必跑）

改 `engine/mnn/src/main/cpp/`（含抽出的 `mnn_chat_core`）后，先在 PC 上回归，再打 APK：

1. 模型：`D:\models\LFM2-350M-MNN`（从模拟器 pull，见 `docs/mnn-pc-regression.md` §2）。
2. 重编 host harness（秒级）：`scripts/mnn_host_regress.ps1`。
3. 全部 PASS 后再 `:app:assembleDebug` / 真机验收。

细节与边界见 `docs/mnn-pc-regression.md`。PC 不测性能数字、不测 Kotlin/UI；真机 arm64 仍是最终验收。

## 阶段流程

1. 只做当前阶段（P0→P5），完成后更新 `IMPLEMENTATION.md` 进度表与交付说明。
2. **停下来等用户审阅**，不要自动进入下一阶段。
3. 真机 DoD 由用户手测；构建/单测 DoD 由执行者跑通。

## 脚本与输出

- 脚本（`.ps1` / `.bat`）默认写英文。
- 新建工程必须有 `.gitignore` 与 `AGENTS.md`。
- 密钥/口令不入库（keystore 口令等只存在于用户本地环境变量 `KEY_*`，勿写入仓库）。
- 应用图标：adaptive icon（`mipmap-anydpi-v26` + 各密度 `ic_launcher_foreground/monochrome`）；debug 用 `src/debug` 覆盖背景色区分。

## 常见坑

- PowerShell 正则/管道易被解析干扰 → 复杂扫描写临时 `.ps1` 或走 `cmd /c`。
- Kotlin `scope.isActive` 在 `launch` 内要 `ensureActive()`（`import kotlinx.coroutines.ensureActive`）。
- `when` 表达式里禁止自引用同一 `val`（用 `config.backend` 等）。
- MNN `Llm::createLLM(dir)` 需要目录内有 `config.json`；流式用 `response(ChatMessages, ostream)`。
- llama.cpp 用纯 `llama.h`（不链 `common`），batch 工具函数本地内联。
- **llama 在 x86 模拟器上可能 SIGABRT**（DefaultDispatch + ndk_translation 栈）：
  真机 arm64 正常、`tools/llama_decode_repro` 也正常 → 按环境问题处理，
  **不要回滚 native**。llama 功能 DoD 以真机为准（详见 `docs/llamacpp.md` §8）。
- **模拟器截图是缩放的，`input tap` 要用物理坐标**：`adb exec-out screencap` 出图 480×1078，
  但 `adb shell wm size` 是 **1080×2400**（density 420）。按截图像素比例点会全部落空
  （表现为"点了没反应"）。**先 `wm size` 取真实分辨率再换算坐标**；本模拟器底部 4 个 tab
  中心约为 x = 135 / 405 / 675 / 945、y ≈ 2093。
- UI 断言用 `uiautomator dump` + `adb shell cat //sdcard/x.xml`（**不要 `adb pull`**，
  Git Bash 会把路径解析成 `D:/dev/git/sdcard/...`）。
- **改 `packaging.jniLibs.useLegacyPackaging` 会静默弄坏 Genie**：它必须是 `true`（⇒ manifest
  `extractNativeLibs=true`），HTP 的 skel 才会落成 `nativeLibraryDir` 下的真实文件；
  改成 `false` 后 `GenieDialog_create` 会在 ~300 ms 后失败，**日志里只有一句 `GenieDialog_create failed`**。
  详见 `docs/genie.md` §4.4（硬约束 2）。
- **`libQnnHtpNetRunExtensions.so` 必须打进 APK**（QAIRT `lib/aarch64-android/`）：缺它时 Genie 只 warn
  （`Failure in initializing backend extensions`）然后**段错误** —— `GenieDialog_create` 内 null deref，
  线程是 QNN 自己的 `DefaultDispatch`，而且**早于模型加载**、app 日志里没有任何线索。
  从 `engine/genie/build.gradle.kts` 的 `copyQnnJniLibs` include 列表里删掉它会立刻复发。
  排查这类问题用 `$QAIRT_PATH/bin/aarch64-android/{qnn-platform-validator,genie-t2t-run}`
  （**完整配方见 `docs/genie.md` §9**；先跑它再看 app 日志，顺序反了查不出来）。
- **16 KB page size 对齐**：Android 15+ 的 16 KB 页设备会在 **debuggable** 应用启动时弹「Android
  应用兼容性」对话框逐库报告（release 不弹，但 Google Play 自 2025-11-01 起强制）。三个 native 模块
  已按 NDK r27 官方配方加 `-Wl,-z,max-page-size=16384` + `-D__BIONIC_NO_PAGE_SIZE_MACRO`。
  **改完 so 后必须 `adb reboot` 才看得到系统判定更新** —— 只重装 APK 时它仍按**旧** APK 报告，
  别据此认为修复无效；判定一律用 `llvm-readelf -l` 读**设备上** `lib/arm64/` 的实际文件。
  `libQnnHtpV*Skel.so` 修不了（QAIRT 2.50 全部变体都是 0x1000），但系统对它判「未知错误」不触发警告。
  详见 `docs/ENGINE_INTEGRATION.md`「16 KB page size 对齐（跨引擎）」。
