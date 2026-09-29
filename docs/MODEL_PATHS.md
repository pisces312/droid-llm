# 模型下载与存放设计（MODEL_PATHS）

> 本文是**模型下载、目录布局、导入迁移**的权威说明。路径契约摘要见 `DESIGN.md` §1.3（仅入口）。
> 引擎格式与导出见 `docs/ENGINE_INTEGRATION.md`（Genie 目录细节与 skel 排障见 `docs/genie.md`）。App **不要求**「同一基座 × 四份导出」：每个引擎独立配置自己的模型文件/目录。

## 1. 模型根目录

| 项 | 约定 |
|----|------|
| 默认根 | `getExternalFilesDir("models")` → `/sdcard/Android/data/<pkg>/files/models/`，免权限 |
| 可改 | Settings → 数据 → 修改模型根；任意用户目录（需 `MANAGE_EXTERNAL_STORAGE`） |
| 迁移 | `ModelRootMigrator`：**永不删除/覆盖目标已有文件**；重名跳过；原目录有数据可选择不迁移；成功后改写 `ModelPathStore` 路径 |
| 引擎子目录 | `litert/` `mnn/` `genie/` `llamacpp/`（`ModelsViewModel.engineDir`） |

## 2. 目录布局

### 2.1 推荐 / 第三方导入布局

```
{modelRoot}/
├── litert/
│   └── qwen1.5b.litertlm          # 或 .task
├── mnn/
│   └── qwen1.5b/                  # config.json + llm.mnn(+分片)
├── genie/
│   └── qwen1.5b/                  # genie_config.json + tokenizer.json + *.bin
└── llamacpp/
    └── qwen1.5b-q4_k_m.gguf
```

### 2.2 市场下载布局（MnnLlmChat cache）

市场（HF / ModelScope）下载与「命中市场条目的导入迁移」统一使用：

```
{modelRoot}/
├── hf/                                 # 或 modelscope/
│   └── models--{org}--{repo}/
│       └── snapshots/
│           └── _no_sha_/
│               ├── ...                 # kind=repo / mnn_repo：整仓
│               └── {fileInRepo}        # kind=file：单文件
└── mnn/
    └── hf/                             # 兼容 MnnLlmChat 的 mnn/ 前缀布局
        └── models--{org}--{repo}/...
```

相对路径由 `CatalogModel.downloadRelPath(source)` / `canonicalRelPath()` 决定：

| 条件 | 相对路径 |
|------|----------|
| 有 HF / ModelScope repo | `{hf\|modelscope}/models--org--repo/snapshots/_no_sha_[/{file}]` |
| 无 repo（仅本地目录约定） | `{engine}/{localPath}` |

扫描识别（`findModelDir`）额外兼容 legacy：`mnn/{localPath}`、`modelscope/{localPath}`、根下直接 `{localPath}` 等（见 `CatalogModel.candidateRelPaths`）。

## 3. 来源与落盘策略

| 来源 | 策略 |
|------|------|
| **市场下载**（HF / ModelScope） | 严格写入 §2.2 布局（`ModelDownloader`）；对话模型下载成功即注册；ImageGen/AudioGen 只落盘不入库 |
| **用户导入**（文件浏览器 / 路径） | 见 §4：非市场模型**原路径注册**；命中市场且根下无副本则**移入**规范布局 |
| **扫描发现** | `ModelAutoImporter.registerFound`：按 catalog 识别根下已存在但未登记的模型，补注册 |

### 注册 vs 复制

- 路径一律**真实路径**（native 直读），不用 `content://`。
- GB 级模型**不做**无意义的全局复制；仅当 §4 命中「移入模型根」时在同一存储卷内 `rename`（失败则 copy+delete 源副本）。
- 源与目标冲突时**永不覆盖**：保持原路径注册，并在 UI 文案中说明。

## 4. 导入迁移（市场一致则归位）

**规则**（`ModelsViewModel.relocateCatalogMatch`）：

1. 格式校验通过后，用文件/目录名匹配 `assets/model_catalog.json`（`matchImported`：引擎一致 + `localPath` / `fileInRepo` / `models--org--repo` 目录名）。
2. **不移动**：已在模型根下；或未命中市场；或根下已有该市场条目（`findModelDir` 非空）；或规范目标已存在。
3. **移动**：命中市场 **且** 根下没有 **且** 目标空闲 → `ModelRootMigrator.moveItem` 到 `CatalogModel.canonicalRelPath()`，注册新路径。
4. UI 提示区分：`原路径 …` / `已移入 …` / `根目录已有市场副本，未移动` 等。

```
导入 path
   │
   ├─ 在 modelRoot 内 ──────────────► 原地注册
   │
   ├─ 名称无法匹配 catalog ─────────► 原地注册（非市场模型）
   │
   ├─ 匹配，但根下已有 / 目标占用 ──► 原地注册 + 提示
   │
   └─ 匹配且根下无 ── move ─────────► 注册 canonical 路径
```

## 5. 市场 catalog（预存）

| 项 | 说明 |
|----|------|
| 文件 | `app/src/main/assets/model_catalog.json`（预存，不运行时拉全量列表） |
| 生成 | `scripts/gen_model_catalog.py`（自 MnnLlmChat `model_market.json` 等） |
| 字段 | `id/name/engine/vendor/sizeBytes/kind=repo\|mnn_repo\|file/localPath/fileInRepo/markerFile/sources/tags` |
| 匹配 | `matchImported` / `findModelDir`；解析一次即可，扫描侧应少做盲探测（见 `ModelAutoImporter`） |

**唯一一条 `GENIE` 条目是自发布的**（`genie-qwen3-4b-instruct-2507-qnn`）：指向本项目自己的 HF 空间
`pisces312-hf/Qwen3-4B-Instruct-2507-QNN-Genie`（Qwen3-4B-Instruct-2507 的 QNN/Genie w4a16 构建，
4 分片约 3.2 GB）。它**不在** MnnLlmChat 的 `model_market.json` 里，由 `gen_model_catalog.py` 的
`extra` 列表硬编码维护 —— 重跑生成脚本不会丢。字段取值与理由：

| 字段 | 值 | 理由 |
|------|----|------|
| `kind` | `repo` | Genie 吃的是**整个目录**，不是单文件 |
| `markerFile` | `genie_config.json` | 与 `GenieConfigResolver.validateModelDir` 同一条判据，`findModelDir` 靠它判定「已下载」 |
| `localPath` | `qwen3_4b_instruct` | 取设备上实际目录名，使**手工拷进来**的同一份模型被 `matchesName` 认出并归位到市场布局 |
| `sources` | 仅 `HuggingFace` | 无 ModelScope 镜像；UI 在 ModelScope 标签页会提示「暂无源，请切换服务器」 |

## 6. 权限与浏览器

| 机制 | 说明 |
|------|------|
| 主路径 | 绝对路径输入 + **内置文件浏览器**（`java.io.File` 语义） |
| 权限 | `MANAGE_EXTERNAL_STORAGE`（特殊权限，无运行时弹窗；跳系统设置页授权） |
| 未授权 | 仅可浏览 App 私有目录 + 授权引导 |
| Android/data/ | **其他 App 的 `Android/data/` 灰显不可选**（Android 11+ 即使有所有文件权限也读不了） |
| SAF | **降级为可选**（外部分享场景），P5 未强制；native 需真实路径，不引入 `content://` 反解负担 |

## 7. 格式校验（添加 / 校验按钮）

| 引擎 | 选什么 | 校验规则 |
|------|--------|----------|
| LiteRT | `.litertlm` / `.task` 文件 | 扩展名；存在性 |
| MNN | 目录 | 必须有 `config.json` + `llm.mnn` |
| Genie | 目录 | 必须有 `genie_config.json` + `tokenizer.json` + 至少一个 `*.bin` |
| llama.cpp | `.gguf` 文件 | 扩展名 + 文件头魔数 `GGUF` |
| Fake | 任意 | 始终通过（仅用于模型根选择器） |

校验失败：行内写出**缺哪个文件**。浏览器过滤：LiteRT→文件扩展名；MNN/Genie→目录；llama.cpp→`.gguf`；FAKE→全部。

## 8. `LocalModel` / 登记

```kotlin
data class LocalModel(
    val id: String,              // uuid
    val engineId: EngineId,
    val displayName: String,     // 用户可改
    val location: ModelLocation, // FilePath | SafUri | AppPrivate
    val formatHint: String?,     // "gguf" / "mnn_dir" / "genie_dir" / "litertlm"
    val fileSizeBytes: Long?,
    val quantHint: String?,
)
```

- `ModelPathStore`（DataStore）持久化列表；按路径去重。
- Benchmark 仅保证「测的是用户指定的那份文件」，结果记录路径/文件名/大小/quant。

## 9. 模型从哪来（获取渠道）

| 格式 | 渠道 |
|------|------|
| `.gguf` | Hugging Face（`TheBloke` / `bartowski` / 官方）、`llama-quantize` 自转 |
| `.litertlm` / `.task` | Google AI Edge、LiteRT-LM 转换脚本、社区分享 |
| MNN 目录 | MNN `llmexport` / `transformers`；ModelZoo |
| Genie 目录 | Qualcomm AI Hub、`chatapp_android` 样例、qnn 流水线 |

## 10. 注意

1. **路径一律真实路径**；native 直接 open。
2. GB 级模型不要无谓复制到 App 私有目录（除非本来就在私有目录）。
3. 切换模型默认 unload 上一 Session；Settings 可开多模型驻留。
4. Benchmark 跑前 `unloadAll()` 其它 Session，保证 RSS 归因干净。
