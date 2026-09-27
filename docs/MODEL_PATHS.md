# 模型路径与目录组织（MODEL_PATHS）

> 路径契约见 `DESIGN.md` §1.3。App **不要求**「同一基座 × 四份导出」：每个引擎独立配置自己的模型文件/目录。

## 权限与浏览器

| 机制 | 说明 |
|------|------|
| 主路径 | 绝对路径输入 + **内置文件浏览器**（`java.io.File` 语义） |
| 权限 | `MANAGE_EXTERNAL_STORAGE`（特殊权限，无运行时弹窗） |
| 授权 | `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 系统页 |
| 未授权 | 仅可浏览 App 私有目录 + 授权引导 |
| Android/data/ | **其他 App 的 `Android/data/` 灰显不可选**（Android 11+ 即使有所有文件权限也读不了） |
| SAF | **降级为可选**（外部分享场景），P5 未强制实现；native 需真实路径，不引入 `content://` 反解负担 |

## 推荐目录

```
/storage/emulated/0/Android/data/io.github.pisces312.droidllm/files/
├── models/
│   ├── litert/
│   │   └── qwen1.5b.litertlm          # 或 .task
│   ├── mnn/
│   │   └── qwen1.5b/                  # config.json + llm.mnn(+分片)
│   ├── genie/
│   │   └── qwen1.5b/                  # genie_config.json + tokenizer.json + *.bin
│   └── llamacpp/
│       └── qwen1.5b-q4_k_m.gguf
└── benchmark/
    └── bench_*.json                   # 评测导出
```

也可放在任意用户目录（如 `/sdcard/Download/models/...`），需已授予「所有文件访问」。

## 格式校验（添加 / 校验按钮）

| 引擎 | 选什么 | 校验规则 |
|------|--------|----------|
| LiteRT | `.litertlm` / `.task` 文件 | 扩展名；存在性 |
| MNN | 目录 | 必须有 `config.json` + `llm.mnn` |
| Genie | 目录 | 必须有 `genie_config.json` + `tokenizer.json` + 至少一个 `*.bin` |
| llama.cpp | `.gguf` 文件 | 扩展名 + 文件头魔数 `GGUF` |
| Fake | 任意 | 始终通过（UI 勾选后才显示） |

校验失败：行内红字写出**缺哪个文件**（如 `missing config.json in ...`）。

## 浏览器过滤

| 引擎 | 显示 |
|------|------|
| LiteRT | 目录 + `.litertlm` / `.task` |
| llama.cpp | 目录 + `.gguf` |
| MNN / Genie | 目录（可「选此目录」） |
| FAKE | 全部 |

## 模型从哪来

| 格式 | 获取渠道 |
|------|----------|
| `.gguf` | Hugging Face（`TheBloke` / `bartowski` / 官方 GGUF 仓库）、`llama-quantize` 自转 |
| `.litertlm` / `.task` | Google AI Edge 发布物、LiteRT-LM 转换脚本、社区分享 |
| MNN 目录 | MNN `llmexport` / `transformers` 转换；MNN 官方 ModelZoo 部分模型 |
| Genie 目录 | Qualcomm AI Hub 导出、`chatapp_android` 样例、qnn 转换流水线（ctx bin + config + tokenizer） |

## 注意

1. **路径一律真实路径**。native 引擎直接 open；不要用 `content://`
2. GB 级模型**不要**复制到 App 私有目录（除非本来就在私有目录）
3. 切换模型默认即 unload 上一个 Session（单模型驻留）；Settings 可开多模型驻留（8GB 机型易 OOM）
4. Benchmark 跑前会 `unloadAll()` 其它 Session，保证 RSS 归因干净
