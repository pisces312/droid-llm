# 许可证调研与决策记录

> 日期：2026-09-27
> 范围：droid-llm 仓库自身许可选型 + 全部第三方依赖的许可证传染性核查
> 状态：已记录（仓库根目录已添加 `LICENSE`，Apache-2.0）

## 1. 核查方法

逐项核对以下一手来源，未依赖二手摘要：

- `settings.gradle.kts` / `build.gradle.kts` / `gradle/libs.versions.toml`：Gradle 依赖清单
- `third_party/llama.cpp/`：vendored 源码树内各 LICENSE 文件与源码头注释
- 本机 MNN 源码树（`MNN_ROOT`）：`LICENSE.txt`
- 本机 QAIRT SDK（`QAIRT_PATH`）：`LICENSE.pdf`（Qualcomm AI Stack License）
- LiteRT-LM：Google AI Edge 官方发布信息（PyPI / GitHub）

## 2. 依赖许可清单

### ① 原生推理引擎层（直接进入 APK）

| 组件 | 集成方式 | 许可证 | 传染性判定 |
|------|----------|--------|-----------|
| llama.cpp（含 ggml / minja） | vendored 源码，静态链入 | MIT | 无传染，可闭源商用 |
| cpp-httplib / nlohmann/json | vendored | MIT | 无传染 |
| miniaudio / subprocess.h / stb | vendored | Public Domain / MIT-0 | 无传染 |
| MNN（libMNN.so） | 预编译 .so，动态链接打包 | Apache-2.0 | 无传染 |
| LiteRT-LM（litertlm-android AAR） | Gradle AAR 依赖 | Apache-2.0 | 无传染 |
| QAIRT / Genie（libGenie.so + QNN 系 .so） | 预编译 .so 打包（专有 SDK） | Qualcomm 专有 | 非传染，但分发受限 |

### ② 应用框架层

| 组件 | 集成方式 | 许可证 | 传染性判定 |
|------|----------|--------|-----------|
| Jetpack Compose / AndroidX / Lifecycle / Navigation / AppCompat | Gradle 依赖 | Apache-2.0 | 无传染 |
| Kotlin / kotlinx-coroutines / kotlinx-serialization | Gradle 依赖 | Apache-2.0 | 无传染 |
| Hilt/Dagger / Room / DataStore | Gradle 依赖 | Apache-2.0 | 无传染 |

### ③ 构建期工具（不随 APK 分发）

| 组件 | 许可证 |
|------|--------|
| AGP / Gradle / KSP | Apache-2.0 |

### ④ 厂商 logo 商标（仅标识性使用，非代码依赖）

| 项 | 说明 |
|----|------|
| 资产 | `app/src/main/res/drawable-nodpi/*_icon.webp`，13 个文件共 96KB |
| 来源 | MNN 官方 Android demo `MnnLlmChat` 的 `app/src/main/res/drawable-nodpi/`；由 `scripts/shrink_vendor_logos.py` 从 1024px 压到 192px WebP |
| 用途 | 在「模型市场」列表标注模型的**厂商**出处（`VendorLogo.kt`）。属**指示性使用**：仅用于说明模型来自哪家，不代表任何关联、赞助或背书；不改色、不叠加、不二次创作 |
| 权利归属 | 各 logo 是其所属公司的**商标**。MNN 仓库的 Apache-2.0 只覆盖其代码，**不覆盖这些商标**；商标权归各厂商所有 |
| 已映射 | Qwen / Smol / Gemma / DeepSeek / Llama / Hunyuan（THUDM→ChatGLM）/ MiniCPM / InternLM / GPT→OpenAI / 01.AI→Yi / Baichuan / Phi |
| 未命中 | 其余厂商（LFM、FastVLM、MiMo、MobileLLM…）不显示 logo，回落为文字首字母。**不做子串推断**，避免把 `TinyLlama` 标成 Meta Llama、把 `Google` 标成 Gemma |

## 3. 结论：无 copyleft「传染」case

全依赖树未发现 GPL / LGPL / AGPL / MPL / EPL / SSPL 等 copyleft 组件：

- 所有开源组件（MIT / Apache-2.0 / Public Domain）均允许闭源、商用再分发；
- 没有任何组件强制仓库自身代码以 GPL 类许可开源；
- 仓库选何种宽松许可均与依赖兼容，仅需保留第三方版权与许可声明（如 vendored llama.cpp 的 `LICENSE`）。

## 4. 唯一注意项：Qualcomm 专有 SDK

`QAIRT / Genie`（libGenie.so + QNN 运行时）按 Qualcomm "AI Stack License" 使用：

- 不传染你的代码，但再分发受限：仅限目标码形式且须与应用集成，禁止单独分发 SDK；
- 禁止逆向工程、反汇编、反编译；
- 受美国出口管制约束；
- 应用商店上架引发的第三方索赔需对 Qualcomm 承担赔偿义务；
- 建议在 README 中声明该 SDK 的使用条款。

## 5. 决策：仓库采用 Apache-2.0

- **首选 Apache-2.0**：与 AndroidX / Compose / Hilt / MNN / LiteRT-LM 等全部主要依赖许可一致；提供显式专利授权，对 LLM 推理类项目更有价值；允许闭源与商用分发。
- **备选 MIT**：更简单、更主流，同样完全兼容。
- **不建议 GPL-3.0**：APK 会打包 Qualcomm 专有二进制（libGenie.so / libQnn*.so），GPL 的再分发义务与专有二进制打包直接冲突。
- 已执行：仓库根目录添加 `LICENSE`（Apache-2.0 全文，版权持有人暂填 `pisces312`，如需可改为真实姓名/主体）。

## 6. 合规义务清单（再分发时）

- [ ] 保留 vendored 第三方源码的 LICENSE 声明（llama.cpp 等）
- [ ] Apache-2.0 组件按条款保留许可与声明
- [ ] 声明 Qualcomm SDK 使用条款
- [ ] 厂商 logo 仅作标识性使用（§2④）；若某厂商要求下架其标识，从 `VendorLogo.kt` 的映射中移除即可
- [ ] 模型权重（GGUF / MNN / litertlm / task）由用户自备，各自许可与代码许可无关，分发模型时另行遵守

## 7. 关联文档

- `docs/ENGINE_INTEGRATION.md`「许可摘要」：各引擎第三方组件许可一览
- 仓库根目录 `LICENSE`：droid-llm 自身代码许可
