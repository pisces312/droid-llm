# llama.cpp decode 独立复现 / 回归工具

> `tools/llama_decode_repro`：脱离 APK、在模拟器（或真机）上直接跑 `load →
> chat_template → tokenize → llama_decode` 的 arm64 CLI。
>
> 用途：
> 1. 排障：隔离「JNI / app 进程环境」与「llama.cpp 计算本身」
> 2. **native 重构后的回归**：改 `llamacpp_jni.cpp` / vendored llama 后，秒级验证
>    decode 契约，不必点 UI
>
> 首次落地 2026-09-28。单引擎细节见 `docs/llamacpp.md`。

## 1. 覆盖的契约

| 步骤 | 与 app 对齐的点 | 回归判定 |
|------|-----------------|----------|
| 模型加载 | `llama_model_load_from_file` 默认 mparams | `model loaded` |
| 上下文 | `n_ctx=2048` / `n_ubatch=512` / `n_threads=4` | `context ready` |
| 模板 | `llama_chat_apply_template`（模型自带 tmpl） | `written>0` |
| 分词 | 两段式：负返回值取绝对值再填缓冲（见 llamacpp.md §2） | `tokenized n>0` |
| prefill | `llama_memory_clear` + `llama_decode` | `llama_decode rc=0` |
| 失败可观测 | `ggml_set_abort_callback` 打到 stderr | 崩溃时有 `ggml_abort:` |

不在范围内：采样出 token、KV 多轮、多线程 batch、性能数字。

## 2. 目录

```
tools/llama_decode_repro/
  main.cpp           CLI 本体
  CMakeLists.txt     add_subdirectory(third_party/llama.cpp) 静态链入
  write_prompt.py    生成普通文本 prompt（可选）
  prompt_exact.bin   精确回归输入（app 实测 121 字节，勿改）
  build-arm64/       本地产物（gitignore，勿提交）
```

## 3. 构建（Android NDK，一次配置）

工具链：SDK CMake 3.22.1 + NDK 27 + Ninja。产物为 **arm64-v8a**（与 APK 相同，
x86 模拟器走 ndk_translation；真机 arm64 原生跑）。

```powershell
$cmake = "D:\dev\android_sdk\cmake\3.22.1\bin\cmake.exe"
$env:PATH = "D:\dev\android_sdk\cmake\3.22.1\bin;$env:PATH"
$ndk  = "D:\dev\android_sdk\ndk\27.0.12077973"
$src  = "D:\my-projects\droid-llm\tools\llama_decode_repro"
$out  = "$src\build-appflags"

cmake -S $src -B $out -G Ninja `
  -DCMAKE_MAKE_PROGRAM="D:\dev\android_sdk\cmake\3.22.1\bin\ninja.exe" `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 `
  -DANDROID_STL=c++_shared `
  -DCMAKE_BUILD_TYPE=RelWithDebInfo `
  "-DCMAKE_CXX_FLAGS=-g -O2 -D_FORTIFY_SOURCE=2 -fstack-protector-strong -fopenmp=libomp" `
  "-DCMAKE_C_FLAGS=-g -O2 -D_FORTIFY_SOURCE=2 -fstack-protector-strong" `
  -DGGML_OPENMP=ON

cmake --build $out --target llama_decode_repro -j 8
```

与 `engine/llamacpp` 的 Gradle 配置对齐：`android-31` / `c++_shared` /
`RelWithDebInfo`+`-O2` / `FORTIFY` / OpenMP。CMakeLists 里把 NDK 的 `libomp.so`
以全路径 + `-Wl,-rpath,/data/local/tmp` 链入。

## 4. 推到模拟器并运行

```powershell
$adb = "D:\dev\android_sdk\platform-tools\adb.exe"
$bin = "D:\my-projects\droid-llm\tools\llama_decode_repro\build-appflags\llama_decode_repro"
$omp = "D:\dev\android_sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\lib\linux\aarch64\libomp.so"
$gguf = "/data/media/0/Android/data/io.github.pisces312.droidllm.debug/files/models/hf/models--unsloth--Qwen3-0.6B-GGUF/snapshots/_no_sha_/Qwen3-0.6B-Q4_K_M.gguf"

& $adb -s emulator-5554 push $bin /data/local/tmp/llama_decode_repro
& $adb -s emulator-5554 push $omp /data/local/tmp/libomp.so
& $adb -s emulator-5554 push `
  "D:\my-projects\droid-llm\tools\llama_decode_repro\prompt_exact.bin" `
  /data/local/tmp/prompt_exact.bin
& $adb -s emulator-5554 shell "chmod 755 /data/local/tmp/llama_decode_repro"

# 用精确回归输入（@file 从文件读 prompt，避免 shell 转义）
& $adb -s emulator-5554 shell `
  "/data/local/tmp/llama_decode_repro $gguf @/data/local/tmp/prompt_exact.bin"
```

期望尾部：

```
repro: tokenized n=23
repro: llama_decode start n=23
repro: llama_decode rc=0
repro: done
```

真机（arm64）同理，把 `gguf` 换成真机上的模型路径即可。

### 其它调用方式

| 参数 | 含义 |
|------|------|
| `argv[1]` | 模型 `.gguf` 绝对路径 |
| `argv[2]` | prompt 字面量，或 `@/path/to/file` 读文件 |
| `argv[3]` | 可选；仅当 `argv[2]==CHATML` 时覆盖 user 消息 |

`CHATML` 模式会走 `llama_chat_apply_template`（system 固定为
"You are a helpful assistant."），用于快速试不同 user 文本。

## 5. 固定回归输入 `prompt_exact.bin`

来自 2026-09-28 模拟器 app 实测（`prefill: prompt_hex` / `token_ids`）：

```text
<|im_start|>system
You are a helpful assistant.<|im_end|>
<|im_start|>user
你好
/think<|im_end|>
<|im_start|>assistant
```

（121 字节；`/think` 为 Qwen3 thinking 开关，不可省。）

分词结果（23 tokens，应保持稳定）：

```
151644 8948 198 2610 525 264 10950 17847 13 151645 198
151644 872 198 108386 198 20439 766 151645 198 151644 77091 198
```

native 重构后跑通 `llama_decode rc=0` 即认为 prefill 路径未回归。

## 6. 实测状态（2026-09-28）

| 环境 | 输入 | 结果 |
|------|------|------|
| **真机 arm64 + APK** | llama + Qwen3-0.6B，正常发消息 | **成功，无崩溃** |
| 模拟器 x86_64 + 本 CLI（arm64 + ndk_translation） | `prompt_exact.bin`（n=23） | **`llama_decode rc=0`，连续 3 次成功** |
| 模拟器 + 本 CLI | CHATML `你好`（n=20） | 成功 |
| 模拟器 + 本 CLI | 1MB 小栈 pthread 上 decode（对齐 DefaultDispatch） | 成功 |
| 模拟器 + APK（同模型同 prompt） | 同上 23 tokens | **`llama_decode` 内 SIGABRT**（见 llamacpp.md §8） |

**结论（已由真机验收钉死）**：

1. **llama.cpp 计算路径本身正确**——真机 arm64 上 APK 同模型可正常推理；
   模拟器上 standalone 对同一 token 序列也 `rc=0`。
2. 崩溃 **只发生在 x86 模拟器 + APK 进程**（arm64 so 经 `libndk_translation`
   执行时）。不是算法 bug，也不是符号冲突（其它引擎 so 不导出 llama/ggml）。
3. 本工具适合做 **native 路径回归**；它复现不了的「模拟器 APK 崩溃」不要
   当成它的失败，那是 **ndk_translation × app 进程环境** 的兼容问题。
4. 模拟器上的 llama 功能结论 **一律以真机为准**；模拟器仅作 UI/流程烟测
   （且建议 `-gpu swiftshader_indirect`）。

## 7. 注意事项

- 需要 `/data/local/tmp/libomp.so`（CMake 已写 rpath）；缺了会报
  `library "libomp.so" not found`。
- 勿在仓内提交 `build-*/` 产物。
- 模型路径随市场下载目录变化；用 `adb shell find ... -name '*.gguf'` 现查。
- x86 模拟器上 arm64 二进制走 ndk_translation，栈信息不完整；要完整 native 栈
  用真机 arm64。
- 与 MNN PC 回归（`docs/mnn-pc-regression.md`）分工：那边是 Windows host +
  胶水层契约；这边是 Android/arm64 + llama_decode 计算路径。
