# MNN PC 端回归测试

> 目标：改 native（`mnn_chat_jni.cpp` 及抽出的 core）后，在 PC 上秒级重编、秒级回归，
> 不必 `assembleDebug` + 真机/模拟器。Android 路径仍是最终验收；PC harness 只覆盖
> **本项目胶水层契约**（见 `docs/mnn.md`）。
>
> 方案定稿 2026-09-28。模型与构建产物路径按 AGENTS.md「目录约定」。

## 1. 架构

```
mnn_chat_core.{hpp,cpp}     平台无关会话核心（唯一真源）
        │
        ├── mnn_chat_jni.cpp        Android JNI 薄层（jstring / 回调桥接）
        └── mnn_host_test.cpp       Windows CLI 回归 harness
                │
                └── D:\models\LFM2-350M-MNN   （模拟器拉下来的 350M 模型）
```

**共享核心包含的契约**（`docs/mnn.md` 已踩过的坑，必须可回归）：

| 契约 | 出处 | 回归用例 |
|------|------|----------|
| `createLLM(config.json) → set_config → load` 顺序 | mnn.md §6 | load 成功 + config 生效 |
| 生成前 `context->status` 复位为 `RUNNING` | mnn.md §6 | 同 session 连续两轮均有输出 |
| `end_with = ""`（不吐哨兵） | mnn.md §4 | 停止时无多余 `\n` / `<eop>` |
| UTF-8 分片流回调 | JNI 流处理 | 多字节字符不被截断 |
| metrics 五元组 | `prompt_len/gen_seq_len/prefill_us/decode_us/ttfa_us` | 数值非负、prompt_len>0 |
| system 必须首位（模板条件输出） | mnn.md §2 | 纯 `hi` 无 system → 空；有 system → 非空 |

## 2. 模型

模拟器市场下载路径（debug 包）：

```
/sdcard/Android/data/io.github.pisces312.droidllm.debug/files/models/
  modelScope/models--MNN--LFM2-350M-MNN/snapshots/_no_sha_/
    config.json  llm.mnn  llm.mnn.weight  llm.mnn.json  llm_config.json  tokenizer.txt
```

拉到 PC（约 213MB，只拉 snapshot 内容，不带 HF 缓存壳）：

```powershell
adb pull "/sdcard/Android/data/io.github.pisces312.droidllm.debug/files/models/modelscope/models--MNN--LFM2-350M-MNN/snapshots/_no_sha_" D:\models\LFM2-350M-MNN
```

`config.json` 关键字段：`backend_type=cpu` / `thread_num=4` / `precision=low` / `memory=low`。
host 与 Android 共用同一份模型目录布局（`Llm::createLLM` 吃 `config.json` 绝对路径）。

## 3. Windows MNN host 构建（一次性）

工具链：VS 18 BuildTools（MSVC 14.51）+ CMake（`D:\dev\android_sdk\cmake\3.22.1`）+
Ninja（`D:\dev\miniconda3\Scripts`）。在 `MNN_ROOT` 下另建 host 树（**不要**和
`project/android/build_64` 混用）：

```powershell
# 需先 vcvars64（VS BuildTools）
cmake -S "$env:MNN_ROOT" -B "$env:MNN_ROOT/build_win64" -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON `
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_SEP_BUILD=OFF `
  -DMNN_BUILD_TOOLS=OFF -DMNN_BUILD_DEMO=OFF `
  -DMNN_LLM_BUILD_DEMO=ON
cmake --build "$env:MNN_ROOT/build_win64" --target llm_demo -j
```

产物：`build_win64/MNN.dll`（或静态 `MNN.lib`）+ `llm` + `llm_demo`。
首次约 20–40 分钟；之后增量只编 harness。

> `llm_demo` 可作纯冒烟（模型能否在 PC 跑通），但**测不到**我们的 status 复位 /
> end_with / UTF-8 契约 —— 那些走 `mnn_host_test`。

## 4. 工程布局（droid-llm）

```
engine/mnn/src/main/cpp/
  mnn_chat_core.hpp      # Session / Utf8StreamProcessor / generate 契约
  mnn_chat_core.cpp
  mnn_chat_jni.cpp       # 只做 JNI 桥接
  CMakeLists.txt         # Android：编 jni + core
  host/
    CMakeLists.txt       # Windows：编 host_test + core，链 MNN host
    mnn_host_test.cpp    # 回归入口
```

Android 侧 `CMakeLists.txt` 把 `mnn_chat_core.cpp` 编进 `libmnn_chat_jni.so`；
host 侧单独 CMake，用 `-DMNN_ROOT_WIN=<build_win64>` 链 Windows MNN。

## 5. 已踩坑

| 坑 | 现象 | 处理 |
|----|------|------|
| **MSVC CRT / `std::string` ABI** | host 用 Debug CRT（`/MDd`，`_ITERATOR_DEBUG_LEVEL=2`）链 Release `MNN.dll` 时，`createLLM` 读到错误路径（`llm_config.json` 找不到）、`dump_config()` 返回 `(null)`、随后 AV | host CMake 强制 `CMAKE_BUILD_TYPE=Release` + `/MD`，与 `MNN.dll` 一致 |
| UTF-8 分片 | 多字节字符跨两次 `processStream` 会拼成**一片**再回调（不会按字节截断） | 回归断言「join 往返一致 + 片内不残缺」，不要断言 `pieces.size()>=2` |

## 6. 日常回归环

```powershell
# 1) 重编 harness（秒级）
cmake --build <host-build-dir>

# 2) 跑回归
.\mnn_host_test.exe --model D:\models\LFM2-350M-MNN --cases all
```

期望：全部 PASS；任一 FAIL 打印 case 名 + 实际输出摘要 + metrics。
脚本入口：`scripts/mnn_host_regress.ps1`（wrapper，检测模型/构建产物缺失时给指引）。

## 7. 边界（明确不测）

- 性能数字（PC CPU 与手机 arm64 不可比，仍以真机为准，见 `docs/mnn.md` §6）
- Kotlin `MnnEngine` / UI / Room / benchmark 流程
- 跨引擎共存（仍走 `EngineCoexistenceTest`）
- 模拟器（2GB + 二进制翻译，`docs/mnn.md` §6.1）

真机 arm64 的 load / 流式聊天 / TTFT 口径，仍是合并前的最终验收。
