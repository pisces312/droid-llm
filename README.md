# droid-llm

**同一台真机上四引擎实测对比** —— 单 APK 集成 Google LiteRT-LM / 阿里 MNN / 高通 Genie(QNN) / llama.cpp，统一聊天 UI + 轻量 Benchmark（Load / Prefill / Decode，可选 TPS 持续）。

推理为主，评测为辅。不做功耗测量、质量评分、雷达图。

## 功能

- **聊天**：四引擎同一页面切换；采样参数面板（不适用字段灰显）；助手气泡挂 TTFT / tok/s；溢出菜单「新建会话」
- **模型**：每引擎独立配置；绝对路径 + 内置文件浏览器（`MANAGE_EXTERNAL_STORAGE`）；格式校验行内提示
- **评测**：引擎×模型一键跑 L/P/D；warmup+runs 中位数；RSS 三段 delta；电池温度；退后台自动暂停；结果表 + 导出 JSON
- **设置**：默认采样、深/浅/跟随系统、多模型驻留开关（默认关）、清空 benchmark 库

## 截图

> 待真机截图后补充（聊天 / 模型浏览器 / 评测结果表）。

## Benchmark 示例表

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
- QAIRT：`QAIRT_PATH=D:\dev\qairt\2.50.0.260828`（见 `docs/ENGINE_INTEGRATION.md`）
- 工具链：AGP 8.13.2 / Kotlin 2.2.21 + KSP 2.3.6 / Compose BOM 2025.05.00

## 文档

| 文档 | 内容 |
|------|------|
| `DESIGN.md` | 架构与引擎契约（权威） |
| `UI_DESIGN.md` | 界面与交互（权威） |
| `IMPLEMENTATION.md` | 阶段执行与交付 |
| `docs/ENGINE_INTEGRATION.md` | 依赖、编译开关、模型导出 |
| `docs/MODEL_PATHS.md` | 目录组织与获取渠道 |

## 许可

droid-llm 自身代码见仓库 LICENSE（如有）。第三方组件许可见 `docs/ENGINE_INTEGRATION.md`「许可摘要」：LiteRT-LM / MNN / Compose 系为 Apache-2.0，llama.cpp 为 MIT，QAIRT/Genie 需遵守 Qualcomm SDK 条款。
