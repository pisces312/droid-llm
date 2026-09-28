# llama.cpp 引擎笔记

> llama.cpp 专属的集成细节、API 约定、实测行为与排障记录。通用契约见 `DESIGN.md`，
> vendored 源码与编译开关见 `ENGINE_INTEGRATION.md`。
>
> **记录规则**：凡是指向单个引擎的坑、结论与调试手法，写进本文，不堆进通用文档。

## 1. 源码与绑定

- 上游：`https://github.com/ggml-org/llama.cpp`，已 **vendored** 到 `third_party/llama.cpp/`
  （勿用 submodule）。
- JNI：`engine/llamacpp/src/main/cpp/llamacpp_jni.cpp`，纯 `llama.h` C API，
  **不链 `llama.cpp/common`**，batch 工具函数本地内联。
- 这意味着 `common_*` 帮助函数（`common_tokenize` / `common_sampler` /
  `common_chat_templates_apply`）**都不能直接用**，自己调 C API 时必须遵守
  下文第 2 节的返回值约定。
- 独立 decode 回归 CLI：`tools/llama_decode_repro`，用法见
  `docs/llamacpp-decode-repro.md`（native 重构后先跑它）。

### 1.1 可参考的成熟实现

ChatterUI（`D:\3rd-party-projects\ChatterUI`，包 `cui-llama.rn` = llama.rn 分支）
在同一套 llama.cpp 上能完整跑通，是很好的对照实现：

| 环节 | ChatterUI / llama.rn | 本项目 JNI |
|------|----------------------|------------|
| 分词 | `common_tokenize`（先给足缓冲区，负数当扩容） | 裸 `llama_tokenize` |
| 模板 | `common_chat_templates_apply`（支持 jinja） | `llama_chat_apply_template` |
| 采样 | `common_sampler` | 手写 sampler chain |
| decode | `common_batch` + `n_batch` 分块 | `llama_batch` 本地内联 |
| 输出 | `utf8_stream_gate` | 自写 `utf8_cache` |

ChatterUI JS 侧用法（`lib/engine/LocalInference.ts`）：

```ts
// 1) 用模型自带模板渲染 messages（优先 jinja）
context.getFormattedChat(messages, null, { jinja: true, enable_thinking })
// 2) 整包 completion
context.completion({ prompt, stop, n_threads, ... })
// 3) 纯分词计数：add_special=false, parse_special=true
context.tokenize(text)
```

## 2. `llama_tokenize` 返回值约定（高危）

`llama.h` 明确约定：

| 返回值 | 含义 |
|--------|------|
| `> 0` | 成功，且 `<= n_tokens_max` |
| `< 0` | **缓冲区不够**，绝对值 = 需要的 token 数（不是致命错误） |
| `INT32_MIN` | 溢出 |

**错误用法**（曾导致所有输入都报 `tokenize failed`）：

```cpp
// 错：nullptr/0 探测。只要文本能分出 >=1 个 token，就必然返回负数
int32_t n = llama_tokenize(vocab, text, len, nullptr, 0, add_special, parse_special);
if (n <= 0) throw "tokenize failed";  // 恒成立
```

**正确用法**（两段式，或对齐 `common_tokenize`）：

```cpp
// 先按上限开缓冲区
int32_t n_needed = (int32_t)text.size() + 2;
std::vector<llama_token> tokens(n_needed);
int32_t n = llama_tokenize(vocab, text, len, tokens.data(), (int32_t)tokens.size(),
                           add_special, parse_special);
if (n < 0) {
    // 缓冲区不够：按 -n 扩容重试，不是失败
    tokens.resize(-n);
    n = llama_tokenize(vocab, text, len, tokens.data(), (int32_t)tokens.size(),
                       add_special, parse_special);
}
if (n <= 0) { /* 真失败 / 空结果 */ }
```

`common_tokenize`（`third_party/llama.cpp/common/common.cpp:1652`）是权威参考：
初值 `text.length() + 2 * add_special`，负数则 `resize(-n_tokens)` 重试。

**排障**：`nativePrefill` 失败时先看 logcat 标签 `llamacpp_jni`：
`tokenize produced 0 tokens` = 空 prompt；`tokenize fill failed (ret=..., needed=...)`
= 第二次调用仍失败。两者含义不同。

## 3. `add_special` / `parse_special` 怎么选

| 场景 | `add_special` | `parse_special` | 说明 |
|------|---------------|-----------------|------|
| 纯计数 / KV 对齐 | `false` | `true` | ChatterUI `tokenize()` 即此 |
| 真正 load prompt | 跟随模型 `add_bos` | `true` | 读 `llama_vocab_get_add_bos(vocab)` |
| 文本可能含控制符 | — | `true` | 解析为 CONTROL token，不拆成普通子词 |

本项目 `nativePrefill` 曾写死 `add_special=true`。对 `add_bos_token=false` 的模型
（见第 4 节）目前无害，但**不应写死**，建议改为读 `llama_vocab_get_add_bos`。

## 4. 模型元数据异常（LFM2 / Gated Delta Net）

模拟器实测模型（Gated Delta Net，Qwen2 BPE，`tokenizer.ggml.pre=qwen2`）：

```text
tokenizer.ggml.add_bos_token = false
BOS token = 11 ','          # 异常：BOS 被标成逗号 token
EOS = 151645 (Qwen2 im_end)
PAD = 151654 (im_think)
load: control-looking token: 128247 '</s>' was not control-type; this is probably a bug in the model.
```

结论：

- **BOS=11 是模型侧元数据错误**。因 `add_bos_token=false`，BPE 路径的 `append_bos`
  不会真正插入它，所以不致命；若强制 `add_special` 且模型改回 `add_bos=true`，
  会在 prompt 前插一个逗号。
- Chat template 为 Qwen 风格 ChatML，`llama_chat_apply_template` 可用；更稳妥是走
  `common_chat_templates_apply` / jinja（ChatterUI 即此）。
- 架构为 **Gated Delta Net**（LFM2 系），`llama_context` 日志会显示
  `fused Gated Delta Net (autoregressive/chunked) enabled`，属正常。
- 加载时 `token_embd.weight (q6_K) ... cannot be used with preferred buffer type
  CPU_REPACK, using CPU instead` —— 模拟器纯 CPU 可忽略。

## 5. 空 prompt 与 chat template 失败

`nativeApplyChatTemplate` 有两条静默降级路径，都会让 `nativePrefill` 拿到空串：

1. `llama_chat_apply_template` 首次 `needed <= 0` → 本地拼 `role: content\n` fallback。
2. 第二次 `written <= 0` → **直接返回空字符串**（Kotlin 侧 `catch` 不会触发）。

空 prompt + `add_bos_token=false` 时 `llama_tokenize` 返回 0，同样会走到
`tokenize failed`。排障时若日志只有 `tokenize produced 0 tokens`，优先查模板输出。

Kotlin 侧（`LlamaCppEngine.generate`）目前是：

```kotlin
val prompt = try {
    LlamaCppNative.nativeApplyChatTemplate(...)
} catch (_: Throwable) {
    ChatTemplate.format(...)   // 仅在 JNI 抛异常时兜底
}
```

模板返回空串时不会走 fallback —— 这是第二个潜在坑。

## 6. 其他实测记录

- `n_ctx=2048`，模型 `n_ctx_train=40960`，日志会提示
  `n_ctx_seq (2048) < n_ctx_train (40960)`，属预期（P1 默认小上下文）。
- 纯 CPU 路径可用；OpenCL 未链接（P1），设置里选 GPU 会被拒绝。
- 输出侧自写 `utf8_cache` 做多字节字符拼接；对照 llama.rn 的 `utf8_stream_gate`
  （含 incomplete suffix 检测 + sanitize），中文逐 token 输出时两边都必须做，否则会
  吐出半个汉字。

## 7. 改进清单（未做）

- [ ] `add_special` 改为读 `llama_vocab_get_add_bos`
- [ ] chat template 空结果时回退 `ChatTemplate.format`（JNI 返回可空 + Kotlin 判空）
- [ ] 中长期：评估引入 `common_*`（需链 `llama.cpp/common`，体积/符号冲突要过
  `EngineCoexistenceTest`）

## 8. 崩溃案例：2026-09-28 发「你好」后 SIGABRT

**现象**：模拟器（x86_64）加载 LFM2 成功后发「你好」，app 直接崩溃退出；
随后宿主机 QEMU 也因 GPU TDR 挂掉。

**Guest tombstone**（`tombstone_34`，artifacts 在 `build/crash-logs/`）：

| 项 | 值 |
|----|-----|
| 进程 | `io.github.pisces312.droidllm.debug` |
| 线程 | `DefaultDispatch`（Kotlin Default，即 generate 协程） |
| 信号 | `signal 6 (SIGABRT), code -1 (SI_QUEUE)` |
| 栈 | 仅 3 帧：`libc syscall` → `libndk_translation RunKernelSyscall` → 匿名 JIT 区 |
| 时间 | guest `11:18:55 UTC` = 宿主 `19:18:55`（app 启动后 19s） |

**关键结论**：

1. **这是 ARM-on-x86 翻译层崩溃**。APK 只带 `arm64-v8a`，x86 模拟器靠
   `libndk_translation` 执行 ARM64 llama.cpp 重 SIMD 代码。native 栈被翻译层
   吃掉，**无法从 tombstone 还原 ggml/llama 帧**。
2. 崩溃发生在 **model load 成功之后、prefill/decode 期间**。日志停在
   `Model loaded`，用户点发送后约 1s 内 abort；没有 `tokenize failed`，
   说明 tokenize 这次可能已通过，更像 `llama_decode` / GDN 计算里
   `GGML_ASSERT`/`GGML_ABORT` 调 `abort()`。
3. LFM2 走 **fused Gated Delta Net**（load 日志：`fused GDN (autoregressive/chunked)
   enabled`）。`ggml_compute_forward_gated_delta_net` 有多条 `GGML_ASSERT`
   （连续性、形状、`K>=1`）和 `GGML_ABORT("fatal error")`。
4. tombstone 内存附近残留字符串碎片 `...project/issues/` / `...machine configu...`，
   疑似翻译层或 ggml 的报错正文，未完整落到 logcat（tombstone 日志截止在 abort
   前 ~240ms）。

**Host 侧伴随事件**（非根因，但是本次会话副作用）：

- 启动参数含 `-gpu host`（`emu-launch-params.txt`）
- NVIDIA OpenGL：`GPU has been disconnected`（pid=QEMU，error code 10）
- `LiveKernelEvent 117`（GPU TDR watchdog），`nvoglv64.dll` + `c0000409`
- 历史 WER `AppCrash_qemu-system-x86_64` 同样是 `fault module nvoglv64.dll`

**复现/定位建议**：

1. **真机 arm64 复现**（`adb-RFCNC0MT5VV`）：native 栈完整，可直接看是
   哪条 `GGML_ASSERT`。这是首选。
2. 模拟器抓完整 abort message：`logcat -b all` 过滤 `llamacpp_jni|ggml|DEBUG|libc`，
   在 `ggml_abort` 的 `fprintf(stderr)` 落盘前不要清 buffer。
3. 若确认 GDN：JNI load 时把 `fused_gdn_ar/ch` 关掉做 A/B（需改
   `llama_context_params`）。
4. 模拟器验功能时用 `-gpu swiftshader_indirect`，避免宿主 NVIDIA TDR 再杀 QEMU。

**二次复现（tombstone_35 / 11:42:34）**：签名完全一致（DefaultDispatch + SIGABRT +
ndk_translation 3 帧）。`Model loaded` → 发送 → ~1s abort，中间**零日志**，
说明 `ggml_abort` 文案走了 `fprintf(stderr)` 没进 logcat。已补：
`ggml_set_abort_callback` → `LOGE("ggml_abort: ...")`，以及 prefill/模板/decode
的 breadcrumb（`prefill: tokenized` / `llama_decode start` / `chat_template: written`）。
下次复现直接看 logcat 里的 `ggml_abort:` 一行即可定位 assert。

**三次/四次复现**：breadcrumb 钉死在 `llama_decode`（prefill 23 tokens）。
`std::set_terminate` + try/catch 均未触发 → **不是 C++ 异常，也不是 ggml_abort**，
是裸 `abort()`（ndk_translation / FORTIFY / 其它）。模型实为
`Qwen3-0.6B-Q4_K_M.gguf`（GDN 日志只是能力探测，与架构无关）。

**独立复现工具** `tools/llama_decode_repro`：arm64 CLI，同一模型 + ChatML +
FORTIFY/O2/c++_shared/OpenMP，**decode 成功**，无法在进程外复现。差异在 app
运行环境（JNI/ART/其它 so）。app 已加 `prompt_hex` / `token_ids` dump，
便于把精确输入喂给该工具做 A/B。
