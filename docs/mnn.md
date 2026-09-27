# MNN 引擎笔记

> MNN（阿里）专属的集成细节、实测行为与排障记录。通用契约见 `DESIGN.md`，
> 依赖获取与模型导出格式见 `ENGINE_INTEGRATION.md` §2。
>
> **记录规则**：凡是指向单个引擎的坑、结论与调试手法，都写进对应引擎的笔记
> （`docs/mnn.md`、后续的 `docs/litert.md` / `docs/genie.md` / `docs/llamacpp.md`），
> 不再堆进通用文档。

## 1. 构建与溯源

### 1.1 libMNN.so 从哪来

- 环境变量 `MNN_ROOT`（或 Gradle 属性 `droid.mnnRoot`）指向 MNN 源码树；Gradle 取
  `<MNN_ROOT>/project/android/build_64/lib/libMNN.so` 拷进 `engine/mnn/src/main/jniLibs/arm64-v8a/`，
  并以 `-DMNN_ROOT=` 传给 CMake 找头文件。
- 本机当前值：`MNN_ROOT=D:\3rd-party-projects\MNN`，MNN **3.6.1**。
- 上游构建脚本：`project/android/build_64.sh` —— `CMAKE_BUILD_TYPE=Release`、`arm64-v8a`、
  `ANDROID_STL=c++_static`、`MNN_OPENCL=ON`、`MNN_LOW_MEMORY=ON`、
  `MNN_SUPPORT_TRANSFORMER_FUSE=ON`；**`MNN_BUILD_LLM` 默认 OFF，必须显式打开**
  （脚本接受 `$*` 透传，如 `./build_64.sh -DMNN_BUILD_LLM=ON`）。
- CMake **不再**回退到任何本机绝对路径：缺 `MNN_ROOT` 直接 `FATAL_ERROR`，避免"在别人机器上
  静默链到一个不存在的目录"。

### 1.2 判断是否和别的 app 用同一个 so

不要比编译参数，直接比二进制。两种口径：

1. **装机后**：设置 → 关于 → 展开原生库信息，读 `libMNN.so` 的大小与 MD5。
2. **APK 内**：
   ```bash
   unzip -p <apk> lib/arm64-v8a/libMNN.so | md5sum
   ```

实测示例（2026-09-27）：本项目 debug APK 与
`MnnLlmChat-v0.8.3.5-pisces-standard-signed.apk` 内的 `libMNN.so`，MD5 同为
`fc79a8c4ba9dd2298580e3cb24d158ae`（8,942,584 B）。

**注意**：本地 `build_64/lib/libMNN.so` 的 MD5 是 `ce5d3f4d…`，与上面不同 ——
AGP 打包时会 strip。所以只应比对 **APK 内 / 装机后** 的副本。

## 2. 空回复：system prompt 是必要条件

**现象**：干净会话首句只发 `hi`，助手回复为空。

**根因**（既不是"轮次"问题，也不是模拟器二进制翻译）：

- MNN 用模型自带的 jinja `chat_template` 渲染消息，而该模板对 system 段是**条件输出**：
  ```jinja
  {%- if messages[0]["role"] == "system" -%} … {%- endif -%}
  ```
  首条消息不是 system 时，**整段 system 不会出现在 prompt 里**。
- 于是首句 `hi` 渲染出来只有 9 token：
  `<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n`。
  LFM2-350M 在这个长度下第一步采样就落在 EOS（`<|im_end|>`），输出为空。
- MnnLlmChat 之所以不空，只因为它**无条件注入**了一条 system
  （`llm_session.cpp:189-190`，取值缺省为 `"You are a helpful assistant."`），
  prompt 因此长出十几 token，越过了该模型的 EOS 临界点。

**实测对照**（同一设备、同一模型、同一 `libMNN.so`，唯一变量是 prompt 内容）：

| 场景 | msgs | prompt_len | 结果 |
|---|---|---|---|
| 首句 `hi` | 1 | 9 | 空（第一个 token 就是 EOS） |
| 首句 `What is 2+2?` | 1 | 16 | 正常 |
| 会话内第二句 `hi` | 3 | 28 | 正常 |

**本项目处理**：设置 → 系统提示词（默认 `"You are a helpful assistant."`，可改可清空）。
`ChatViewModel.send()` 每次把非空 system 作为**首条** `system` 消息注入请求；
清空则不注入（此时极短首句仍会出现空回复，属预期）。

**要点**：system 必须放在**首位** —— 模板用 `messages[0]` 取它。

## 3. 计时字段：哪些可信

`LlmContext` 提供 `load_us / prefill_us / decode_us / sample_us / ttfa_us`。

| 字段 | 普通 ArGeneration 路径 | 说明 |
|---|---|---|
| `prefill_us` | 有效 | `llm.cpp` 中 prefill forward 前后计时，`+=` 累加 |
| `decode_us` | 有效 | `speculative_decoding/generate.cpp` 每个 decode step 累加 |
| `sample_us` | 有效 | 采样耗时 |
| `ttfa_us` | **恒为 0** | 只有 `omni.cpp` 会写这条路径 |

结论：**TTFT 取 `prefill_us`**（MNN 的 prefill 阶段正好覆盖到第一个 token 为止），
不要指望 `ttfa_us`。`generate_init()` 每轮会把 `prefill_us / decode_us` 清零，故是单轮值、
不会跨轮累加。

MnnLlmChat 的展示公式（`llm_session.cpp:384-391`）：

```
prefill_tps = prompt_len / (prefill_us / 1e6)
decode_tps  = gen_seq_len / (decode_us  / 1e6)
```

本项目用同一份引擎计时，但 decode 沿 `DESIGN.md` 契约（`(generatedTokens - 1) / decodeSeconds`），
两者差异仅 1/N 量级，可直接横向比较。

## 4. `end_with` 不参与停止判定（更正早前结论）

`mContext->end_with` 只被 `generate_init()` **写入**，全仓没有任何地方拿它做停止判定。
停止判定走 `Llm::is_stop(token_id)` → `mTokenizer->is_stop()`（tokenizer 的 EOS / stop 列表）。

`end_with` 的唯一用途：命中停止时把它**原样写进输出流**
（`speculative_decoding/generate.cpp:81/145`、dflash / lookahead / mtp 同理：
`*mContext->os << mContext->end_with`）。

由此：

- 传 `""` 是正确做法 —— 停止时不会写入任何哨兵文本。
  （早前传 `nullptr`，MNN 会兜底成 `"\n"`，于是一个"纯 EOS 的轮次"会产出一个只含换行的
  空气泡；Compose 对空白文本不生成无障碍节点，正是那个"看不见但有内容"的指纹。）
- MnnLlmChat 传 `"<eop>"` **不是**它能触发停止，而是它采用 stepping 循环
  （`response(history_, &os, "<eop>", 0)` 只 prefill + 手动 `generate(1)`），
  需要靠 `resolveAndroidSteppingEop()` 在输出流里检测这个哨兵文本来判断本轮结束。

## 5. 其它已踩过的坑

| 坑 | 依据 | 处理 |
|---|---|---|
| `set_config` 必须在 `load()` **之前** | `Llm::load()` 构建 runtime 时读 `backend_type()` / `thread_num()`；之后再 set 只影响采样参数 | `nativeCreate` 内 `createLLM → set_config → load`（与 demo 的 `LlmSession::Load()` 一致） |
| 每轮生成前把 `context->status` 复位成 `RUNNING` | 上一轮结束停在 `NORMAL_FINISHED` / `MAX_TOKENS_FINISHED`，下一轮 `response()` 会直接不解码 | `nativeGenerate` 内检查，非 RUNNING 则 `const_cast` 置回（对齐 demo 的 `restoreAndroidSteppingStatusIfNeeded`） |
| `createLLM` 要传 `config.json` 的绝对路径 | 传纯目录会拼出 `<dir>tokenizer.txt`（缺分隔符），tokenizer 加载失败 | Kotlin 侧传 `File(dir, "config.json").absolutePath` |
| 模型自带 `config.json` 已写死 `backend_type=cpu` / `thread_num=4` / `precision=low` / `memory=low` | — | 我们传 `cpu` + `threads`，与 MnnLlmChat 的缺省一致；**跨 app 比速度时这一项不构成差异** |
| 模拟器上数值不可信 | `emulator-5554` 是 x86_64，arm64 产物走 `libndk_translation.so` 二进制翻译 | 性能与采样结论一律以真机 arm64 为准 |
| **模拟器连"跑通评测流程"都做不到** | 实测卡在 `llm->load()`，见 §5.1 | 评测（含结果表/历史卡/失败行）一律真机 arm64；模拟器只用于纯 UI 布局验证 |

### 5.1 模拟器为什么不能用来跑评测

**结论：不要尝试在模拟器上运行评测。** 这不是代码 bug，也不是配置问题，是环境限制，
在模拟器上排查评测流程只会浪费时间。

2026-09-27 实测（`emulator-5554`，`sdk_gphone64_x86_64`，**2GB RAM**，APK 只含
`lib/arm64-v8a/` 故走 native bridge 翻译）。点「开始评测」（MNN 引擎 + LFM2-350M-MNN）后：

```
mnn_chat_jni: createLLM .../LFM2-350M-MNN/.../config.json   ← 请求确实下发到了 native
（此后不再有任何 native 日志）
```

进程侧：**CPU 0%、状态 S(sleeping)、无 FATAL / OOM / ANR，RES 592MB 停滞不动，3 分钟无进展**。

→ **卡在 `llm->load()`**（`createLLM` 之后、load 完成日志之前）。

日志里出现的这两条是**正常**的，不要当成失败原因去追：

```
E MNNJNI: unable to load libcdsprpc.so
E MNNJNI: [MNN::Hexagon] Open libcdsprpc.so failed
```

模拟器没有骁龙 HTP，Genie 那条 QNN 路径本来就不通（`MNN_QNN: Loaded HTP backend.`
是 MNN 的探测日志）。

真正原因是两条叠加：arm64 代码走二进制翻译 + 模拟器只有 2GB RAM（`MemTotal: 2021092 kB`，
free 仅 200MB、swap 已用 500MB+）。**只能 `adb shell am force-stop` 恢复**。

因此：

- 评测的验收（结果表、历史卡、未注册引擎的失败行）**必须真机 arm64**。历史卡尤其
  依赖 Room 里有真实记录，模拟器生成不出来。
- 模拟器仍然可用于**纯 UI 布局验证** —— Compose 渲染不碰 native，界面照常出帧，
  翻页、截图、`uiautomator dump` 读文字都正常。

## 6. 调试手法

- 按 PID 看日志：`adb logcat -d --pid=$(adb shell pidof io.github.pisces312.droidllm.debug)`
- 原生埋点：`__android_log_print(ANDROID_LOG_INFO, "<tag>", ...)`；MNN 自身的 `MNN_ERROR`
  也会进 logcat。
- 界面文本断言：`adb shell uiautomator dump //sdcard/x.xml` 再
  `adb shell cat //sdcard/x.xml` —— **不要用 `adb pull`**，Git Bash 会把 `/sdcard`
  解析成 `D:/dev/git/sdcard/...`。
- 截图：`adb exec-out screencap -p > x.png`
- 比对 so：见 §1.2。
