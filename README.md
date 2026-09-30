# droid-llm

**同一台真机上四引擎实测对比** —— 单 APK 集成 Google LiteRT-LM / 阿里 MNN / 高通 Genie(QNN) / llama.cpp，统一聊天 UI + 轻量 Benchmark（Load / Prefill / Decode，可选 TPS 持续）。

推理为主，评测为辅。不做功耗测量、质量评分、雷达图。

## 功能

- **聊天**：四引擎同一页面切换；采样参数面板（不适用字段灰显）；助手气泡挂 TTFT / tok/s；溢出菜单「新建会话」
- **模型**：每引擎独立配置；绝对路径 + 内置文件浏览器（`MANAGE_EXTERNAL_STORAGE`）；格式校验行内提示
- **评测**：引擎×模型一键跑 L/P/D；warmup+runs 中位数；RSS 三段 delta；电池温度；退后台自动暂停；结果表 + 导出 JSON
- **设置**：默认采样、深/浅/跟随系统、多模型驻留开关（默认关）、清空 benchmark 库

## 截图

真机实测（HONOR BKQ-AN80 / Snapdragon 8 Elite / Android 17）。四张图分别是各引擎在**本机跑得最快的后端组合**下的聊天界面：

<table>
<tr>
<td align="center"><img src="docs/screenshots/engine-genie-npu.jpg" width="255"><br><sub>Genie (QNN) · NPU · Qwen3-4B</sub></td>
<td align="center"><img src="docs/screenshots/engine-llamacpp-cpu.jpg" width="255"><br><sub>llama.cpp · CPU · Qwen3-0.6B</sub></td>
<td align="center"><img src="docs/screenshots/engine-litert-cpu.jpg" width="255"><br><sub>LiteRT-LM · CPU · Gemma3-1B</sub></td>
<td align="center"><img src="docs/screenshots/engine-mnn-gpu.jpg" width="255"><br><sub>MNN · GPU · Qwen3.5-9B</sub></td>
</tr>
</table>

| 引擎 | 最快后端 | 实测模型 | TTFT | Decode |
|------|----------|----------|------|--------|
| Genie (QNN) | **NPU**（HTP V81） | Qwen3-4B-Instruct-2507 w4a16 | 49 ms | 48.3 tok/s |
| llama.cpp | **CPU** | Qwen3-0.6B Q4_K_M | 187 ms | 78.6 tok/s |
| LiteRT-LM | **CPU** | Gemma3-1B-IT int4 | 375 ms | 38.1 tok/s |
| MNN | **GPU**（OpenCL） | Qwen3.5-9B-MNN | 2041 ms | 9.4 tok/s |

> 四张图是同一次真机会话的抓取，**模型各不相同，故上表不可横向比快慢** —— 它只回答「该引擎在本机该用哪个后端」。跨模型/跨量化的对比请在评测页固定同一模型跑。

## 已知问题

- **LiteRT-LM + GPU**：生成会不断重复同一段回答且**无法停止**。请使用 **LiteRT-LM + CPU**（也是本项目默认验证口径）。历史上的同类退化修复见 `docs/litert.md`。

## Benchmark 导出格式示意

> 下表只说明评测页导出的**字段**，**数字是占位值、不是实测结果**（真实数字由你在评测页跑出来）。

| Engine | Model | Quant | Load(ms) | TTFT(ms) | Prefill tps | Decode tps | RSS peak |
|--------|-------|-------|----------|----------|-------------|------------|----------|
| MNN | qwen1.5b/mnn | w4a16 | 1200 | 180 | 320 | 38 | 2100 |
| llama.cpp | qwen1.5b-q4_k_m.gguf | Q4_K_M | 900 | 220 | 280 | 42 | 1800 |
| LiteRT | qwen1.5b.litertlm | f16 | 2100 | 260 | 250 | 30 | 3200 |
| Genie | qwen1.5b_genie/ | w4a16 | 1500 | 150 | 350 | 45 | 1900 |

> 跨模型/跨量化数字**只作参考**，不构成绝对快慢结论。

## 构建

```powershell
$env:JAVA_HOME = "D:\dev\AndroidStudio\jbr"
$env:ANDROID_HOME = "D:\dev\android_sdk"

# Debug（applicationId 后缀 .debug，可与正式版并存）
.\gradlew.bat :app:assembleDebug

# 开发机 / 无 QAIRT（Genie 灰显）
.\gradlew.bat :app:assembleDebug -Pdroid.skipGenie=true

# 正式版（读环境变量签名）
.\gradlew.bat :app:assembleRelease
```

**QNN HTP arch 裁剪**：debug 等非 release 构建默认只打 `libQnnHtpV81{Skel,Stub}.so`
（开发机 SM8850 → V81，省约 23 MB）；release 保留 QAIRT SDK 全部 arch 供 GitHub Release。
需要其它组合时用 `-Pdroid.qnnHtpVersions=79,81`，全量用 `-Pdroid.qnnHtpVersions=all`。

**正式版签名**（环境变量，不写进仓库）：

| 变量 | 说明 |
|------|------|
| `KEY_STORE` / `KEY_STORE_LOCATION` | keystore 路径 |
| `KEY_STORE_PASSWORD` | store 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

- arm64-v8a only，单 APK，无 Dynamic Feature
- debug：`io.github.pisces312.droidllm.debug` / 名称 `droid-llm debug`；release：`io.github.pisces312.droidllm` / 名称 `droid-llm`，可同机安装
- QAIRT：`QAIRT_PATH=D:\dev\qairt\2.50.0.260828`（见 `docs/genie.md`）
- 工具链：AGP 8.13.2 / Kotlin 2.2.21 + KSP 2.3.6 / Compose BOM 2025.05.00

## 参考项目（上游）

四个引擎各自的原项目与参考实现：

| 引擎 | 上游项目 | GitHub | 参考内容 |
|------|----------|--------|----------|
| LiteRT | LiteRT-LM | https://github.com/google-ai-edge/LiteRT-LM | 推理框架本体（`litertlm-android` AAR 闭源） |
| LiteRT | Google AI Edge Gallery | https://github.com/google-ai-edge/gallery | LiteRT-LM 接入、模型 allowlist / HF 下载 URL、`LlmChatModelHelper` |
| MNN | MNN | https://github.com/alibaba/MNN | 引擎本体（预编译 `libMNN.so`，`MNN_BUILD_LLM=ON`）；参考实现 `apps/Android/MnnLlmChat`（模型市场、HF/ModelScope 双源、目录扫描） |
| Genie (QNN) | AI Hub Apps | https://github.com/qualcomm/ai-hub-apps | `chatapp_android`：`genie_config.json` 解析、prompt tags、QAIRT 打包布局（Genie SDK 本体闭源，需遵守 Qualcomm 条款） |
| llama.cpp | llama.cpp | https://github.com/ggml-org/llama.cpp | 已 **vendored** 到 `third_party/llama.cpp/`（非 submodule） |
| llama.cpp | ChatterUI | https://github.com/Vali-98/ChatterUI | Android 端集成方式与 GGUF 模型管理 |

引擎专属结论与踩坑见 `docs/<engine>.md`；依赖获取、编译开关、许可摘要见 `docs/ENGINE_INTEGRATION.md`。

## 文档

| 文档 | 内容 |
|------|------|
| `DESIGN.md` | 架构与引擎契约（权威） |
| `UI_DESIGN.md` | 界面与交互（权威） |
| `IMPLEMENTATION.md` | 阶段执行与交付 |
| `docs/ENGINE_INTEGRATION.md` | 依赖、编译开关、模型导出 |
| `docs/MODEL_PATHS.md` | 模型下载与存放（布局、导入迁移） |

## 许可与第三方合规

本项目自身代码以 **Apache-2.0** 发布，全文见仓库根 `LICENSE`。

随 APK 打包的第三方组件：

| 组件 | 集成方式 | 许可 |
|------|----------|------|
| llama.cpp / ggml / minja | vendored 源码，静态链接 | MIT |
| cpp-httplib / nlohmann-json / miniaudio / subprocess.h | vendored | MIT / Public Domain |
| MNN（`libMNN.so`） | 预编译 `.so`，动态链接 | Apache-2.0 |
| LiteRT-LM（`litertlm-android` AAR） | Gradle 依赖 | Apache-2.0 |
| AndroidX / Compose / Lifecycle / Navigation / AppCompat / Kotlin | Gradle 依赖 | Apache-2.0 |
| **QAIRT / Genie（`libGenie.so` + QNN 运行时）** | 预编译 `.so`，随应用打包 | **Qualcomm 专有** |

- **Qualcomm QAIRT / Genie**：按 Qualcomm AI Stack License 使用 —— 仅允许以**目标码形式与应用集成**分发，**禁止单独再分发 SDK**，禁止逆向工程 / 反编译，并受美国出口管制约束。本仓库**不包含**该 SDK；自行构建 Genie 引擎需自备 QAIRT 并自行接受其条款。
- **模型权重**不随仓库与 APK 分发，由用户自备；权重的许可与代码许可相互独立（内置模型市场只提供下载地址）。
- **厂商 logo**（`ui/components/VendorLogo.kt` + `res/drawable-nodpi/*.webp`）仅作标识性使用；若权利方要求下架，从该映射中移除即可。
- 完整的核查方法、逐项依赖许可表与选型决策记录见 `docs/LICENSING.md`。
