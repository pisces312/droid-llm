"""Convert MnnLlmChat model_market.json into droid-llm model_catalog.json.

Also appends hand-curated LiteRT (.litertlm / .task) and llama.cpp GGUF entries
(single-file Q4_K_M where possible; params ≤ 10B). GGUF rows were verified
against the HF tree API (hf-mirror.com); LiteRT rows follow Google AI Edge
Gallery `model_allowlists` and were checked on HF Content-Length + ModelScope
tree — keep fileInRepo / sizeBytes in sync when editing.

Gemma weights are gated on HF (403 without license acceptance) but the same
`litert-community/*` / `google/*` repos download freely from ModelScope, so
Gemma rows always carry both sources.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = Path(r"D:\3rd-party-projects\MNN\apps\Android\MnnLlmChat\app\src\main\assets\model_market.json")
DST = ROOT / "app" / "src" / "main" / "assets" / "model_catalog.json"

# (id, name, vendor, description, size_bytes, file_in_repo, hf_repo, tags)
# Only single-file GGUF quants — the downloader has no shard reassembly.
GGUF_MODELS = [
    (
        "qwen2.5-0.5b-instruct-q4k-gguf",
        "Qwen2.5-0.5B-Instruct Q4_K_M (gguf)",
        "Qwen",
        "0.5B 指令模型，GGUF Q4_K_M",
        491400032,
        "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "qwen2.5-1.5b-instruct-q4k-gguf",
        "Qwen2.5-1.5B-Instruct Q4_K_M (gguf)",
        "Qwen",
        "1.5B 指令模型，GGUF Q4_K_M",
        1117320736,
        "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "qwen2.5-3b-instruct-q4k-gguf",
        "Qwen2.5-3B-Instruct Q4_K_M (gguf)",
        "Qwen",
        "3B 指令模型，GGUF Q4_K_M",
        2104932768,
        "qwen2.5-3b-instruct-q4_k_m.gguf",
        "Qwen/Qwen2.5-3B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "qwen2.5-7b-instruct-q4k-gguf",
        "Qwen2.5-7B-Instruct Q4_K_M (gguf)",
        "Qwen",
        "7B 指令模型，GGUF Q4_K_M（单文件）",
        4683074240,
        "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        "bartowski/Qwen2.5-7B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "qwen3-0.6b-q4k-gguf",
        "Qwen3-0.6B Q4_K_M (gguf)",
        "Qwen",
        "0.6B 混合思考，GGUF Q4_K_M",
        396705472,
        "Qwen3-0.6B-Q4_K_M.gguf",
        "unsloth/Qwen3-0.6B-GGUF",
        ["chat", "thinking"],
    ),
    (
        "qwen3-1.7b-q4k-gguf",
        "Qwen3-1.7B Q4_K_M (gguf)",
        "Qwen",
        "1.7B 混合思考，GGUF Q4_K_M",
        1107409472,
        "Qwen3-1.7B-Q4_K_M.gguf",
        "unsloth/Qwen3-1.7B-GGUF",
        ["chat", "thinking"],
    ),
    (
        "qwen3-4b-q4k-gguf",
        "Qwen3-4B Q4_K_M (gguf)",
        "Qwen",
        "4B 混合思考，GGUF Q4_K_M",
        2497280256,
        "Qwen3-4B-Q4_K_M.gguf",
        "Qwen/Qwen3-4B-GGUF",
        ["chat", "thinking"],
    ),
    (
        "qwen3-8b-q4k-gguf",
        "Qwen3-8B Q4_K_M (gguf)",
        "Qwen",
        "8B 混合思考，GGUF Q4_K_M",
        5027783488,
        "Qwen3-8B-Q4_K_M.gguf",
        "Qwen/Qwen3-8B-GGUF",
        ["chat", "thinking"],
    ),
    (
        "llama-3.2-1b-instruct-q4k-gguf",
        "Llama-3.2-1B-Instruct Q4_K_M (gguf)",
        "Meta",
        "1B 指令模型，GGUF Q4_K_M",
        807694464,
        "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        "bartowski/Llama-3.2-1B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "llama-3.2-3b-instruct-q4k-gguf",
        "Llama-3.2-3B-Instruct Q4_K_M (gguf)",
        "Meta",
        "3B 指令模型，GGUF Q4_K_M",
        2019377696,
        "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        "bartowski/Llama-3.2-3B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "llama-3.1-8b-instruct-q4k-gguf",
        "Llama-3.1-8B-Instruct Q4_K_M (gguf)",
        "Meta",
        "8B 指令模型，GGUF Q4_K_M",
        4920739232,
        "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "gemma-2-2b-it-q4k-gguf",
        "Gemma-2-2B-it Q4_K_M (gguf)",
        "Google",
        "2B 指令模型，GGUF Q4_K_M",
        1708582752,
        "gemma-2-2b-it-Q4_K_M.gguf",
        "bartowski/gemma-2-2b-it-GGUF",
        ["chat"],
    ),
    (
        "gemma-3-1b-it-q4k-gguf",
        "Gemma-3-1B-it Q4_K_M (gguf)",
        "Google",
        "1B 指令模型，GGUF Q4_K_M",
        806058240,
        "gemma-3-1b-it-Q4_K_M.gguf",
        "ggml-org/gemma-3-1b-it-GGUF",
        ["chat"],
    ),
    (
        "gemma-3-4b-it-q4k-gguf",
        "Gemma-3-4B-it Q4_K_M (gguf)",
        "Google",
        "4B 指令模型，GGUF Q4_K_M",
        2489757856,
        "gemma-3-4b-it-Q4_K_M.gguf",
        "ggml-org/gemma-3-4b-it-GGUF",
        ["chat"],
    ),
    (
        "phi-3.5-mini-instruct-q4k-gguf",
        "Phi-3.5-mini-instruct Q4_K_M (gguf)",
        "Microsoft",
        "3.8B 指令模型，GGUF Q4_K_M",
        2393232672,
        "Phi-3.5-mini-instruct-Q4_K_M.gguf",
        "bartowski/Phi-3.5-mini-instruct-GGUF",
        ["chat"],
    ),
    (
        "phi-4-mini-instruct-q4k-gguf",
        "Phi-4-mini-instruct Q4_K_M (gguf)",
        "Microsoft",
        "3.8B 指令模型，GGUF Q4_K_M",
        2491874688,
        "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
        "bartowski/microsoft_Phi-4-mini-instruct-GGUF",
        ["chat"],
    ),
    (
        "smollm2-1.7b-instruct-q4k-gguf",
        "SmolLM2-1.7B-Instruct Q4_K_M (gguf)",
        "HuggingFace",
        "1.7B 轻量指令模型，GGUF Q4_K_M",
        1055609536,
        "smollm2-1.7b-instruct-q4_k_m.gguf",
        "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
        ["chat"],
    ),
    (
        "mistral-7b-instruct-v0.3-q4k-gguf",
        "Mistral-7B-Instruct-v0.3 Q4_K_M (gguf)",
        "Mistral",
        "7B 指令模型，GGUF Q4_K_M",
        4372812000,
        "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
        ["chat"],
    ),
    (
        "deepseek-r1-distill-qwen-1.5b-q4k-gguf",
        "DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M (gguf)",
        "DeepSeek",
        "1.5B 推理蒸馏，GGUF Q4_K_M",
        1117320800,
        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
        ["reasoning"],
    ),
    (
        "deepseek-r1-distill-qwen-7b-q4k-gguf",
        "DeepSeek-R1-Distill-Qwen-7B Q4_K_M (gguf)",
        "DeepSeek",
        "7B 推理蒸馏，GGUF Q4_K_M",
        4683073504,
        "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
        "bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF",
        ["reasoning"],
    ),
    (
        "minicpm3-4b-q4k-gguf",
        "MiniCPM3-4B Q4_K_M (gguf)",
        "OpenBMB",
        "4B 指令模型，GGUF Q4_K_M",
        2469791584,
        "minicpm3-4b-q4_k_m.gguf",
        "openbmb/MiniCPM3-4B-GGUF",
        ["chat"],
    ),
    (
        "internlm2.5-1.8b-chat-q4k-gguf",
        "InternLM2.5-1.8B-Chat Q4_K_M (gguf)",
        "InternLM",
        "1.8B 对话模型，GGUF Q4_K_M",
        1172364032,
        "internlm2_5-1_8b-chat-q4_k_m.gguf",
        "internlm/internlm2_5-1_8b-chat-GGUF",
        ["chat"],
    ),
    (
        "tinyllama-1.1b-chat-q4k-gguf",
        "TinyLlama-1.1B-Chat-v1.0 Q4_K_M (gguf)",
        "TinyLlama",
        "1.1B 轻量对话，GGUF Q4_K_M",
        668788096,
        "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
        "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
        ["chat"],
    ),
]


def gguf_entry(row) -> dict:
    model_id, name, vendor, desc, size, file_name, repo, tags = row
    return {
        "id": model_id,
        "name": name,
        "engine": "LLAMACPP",
        "vendor": vendor,
        "description": desc,
        "sizeBytes": size,
        "kind": "file",
        "localPath": file_name,
        "fileInRepo": file_name,
        "sources": {"HuggingFace": repo},
        "tags": tags,
    }


# (id, name, vendor, description, size_bytes, file_in_repo, hf_repo, ms_repo, tags)
# Prefer single-file .litertlm (LiteRT-LM). Gallery allowlist is the source of
# truth for recommended models; sizes cross-checked on MS tree / HF headers.
LITERT_MODELS = [
    (
        "gemma3-1b-it-int4-litertlm",
        "Gemma3-1B-IT int4 (.litertlm)",
        "Google",
        "1B 指令模型，QAT int4，Gallery 推荐",
        584417280,
        "gemma3-1b-it-int4.litertlm",
        "litert-community/Gemma3-1B-IT",
        "litert-community/Gemma3-1B-IT",
        ["chat"],
    ),
    (
        "qwen2.5-1.5b-instruct-q8-litertlm",
        "Qwen2.5-1.5B-Instruct q8 (.litertlm)",
        "Qwen",
        "1.5B 指令模型，q8 ekv4096，Gallery 推荐",
        1597931520,
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        "litert-community/Qwen2.5-1.5B-Instruct",
        "litert-community/Qwen2.5-1.5B-Instruct",
        ["chat"],
    ),
    (
        "deepseek-r1-distill-qwen-1.5b-q8-litertlm",
        "DeepSeek-R1-Distill-Qwen-1.5B q8 (.litertlm)",
        "DeepSeek",
        "1.5B 推理蒸馏，q8 ekv4096，Gallery 推荐",
        1833451520,
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        ["reasoning"],
    ),
    (
        "phi-4-mini-instruct-q8-litertlm",
        "Phi-4-mini-instruct q8 (.litertlm)",
        "Microsoft",
        "3.8B 指令模型，q8 ekv4096",
        3910090752,
        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        "litert-community/Phi-4-mini-instruct",
        "litert-community/Phi-4-mini-instruct",
        ["chat"],
    ),
    (
        "qwen3-0.6b-litertlm",
        "Qwen3-0.6B (.litertlm)",
        "Qwen",
        "0.6B 混合思考，单文件",
        614236160,
        "Qwen3-0.6B.litertlm",
        "litert-community/Qwen3-0.6B",
        "litert-community/Qwen3-0.6B",
        ["chat", "thinking"],
    ),
    (
        "gemma3-270m-it-q8-litertlm",
        "Gemma3-270M-it q8 (.litertlm)",
        "Google",
        "270M 超轻量指令模型",
        304005120,
        "gemma3-270m-it-q8.litertlm",
        "litert-community/gemma-3-270m-it",
        "litert-community/gemma-3-270m-it",
        ["chat"],
    ),
    (
        "minicpm5-2b-int4-litertlm",
        "MiniCPM5-2B int4 (.litertlm)",
        "OpenBMB",
        "2B 指令模型，int4",
        1553670064,
        "MiniCPM5-2B_int4.litertlm",
        "litert-community/MiniCPM5-2B",
        "litert-community/MiniCPM5-2B",
        ["chat"],
    ),
    (
        "qwen3.5-2b-int8-litertlm",
        "Qwen3.5-2B int8 (.litertlm)",
        "Qwen",
        "2B 指令模型，int8",
        2116592816,
        "Qwen3.5-2B_int8.litertlm",
        "litert-community/Qwen3.5-2B",
        "litert-community/Qwen3.5-2B",
        ["chat"],
    ),
    (
        "qwen2.5-coder-3b-it-litertlm",
        "Qwen2.5-Coder-3B-Instruct (.litertlm)",
        "Qwen",
        "3B 代码模型，单文件",
        3433083824,
        "Qwen2.5_Coder_3B_It.litertlm",
        "litert-community/Qwen2.5-Coder-3B-Instruct",
        "litert-community/Qwen2.5-Coder-3B-Instruct",
        ["coding"],
    ),
    (
        "gemma-3n-e2b-it-int4-litertlm",
        "Gemma-3n-E2B-it int4 (.litertlm)",
        "Google",
        "E2B 多模态（文/图/音），Gallery 推荐",
        3655827456,
        "gemma-3n-E2B-it-int4.litertlm",
        "google/gemma-3n-E2B-it-litert-lm",
        "google/gemma-3n-E2B-it-litert-lm",
        ["chat", "multimodal"],
    ),
    (
        "gemma-3n-e4b-it-int4-litertlm",
        "Gemma-3n-E4B-it int4 (.litertlm)",
        "Google",
        "E4B 多模态（文/图/音），Gallery 推荐",
        4919541760,
        "gemma-3n-E4B-it-int4.litertlm",
        "google/gemma-3n-E4B-it-litert-lm",
        "google/gemma-3n-E4B-it-litert-lm",
        ["chat", "multimodal"],
    ),
]


def litert_entry(row) -> dict:
    model_id, name, vendor, desc, size, file_name, hf_repo, ms_repo, tags = row
    sources = {}
    if hf_repo:
        sources["HuggingFace"] = hf_repo
    if ms_repo:
        sources["ModelScope"] = ms_repo
    return {
        "id": model_id,
        "name": name,
        "engine": "LITERT",
        "vendor": vendor,
        "description": desc,
        "sizeBytes": size,
        "kind": "file",
        "localPath": file_name,
        "fileInRepo": file_name,
        "sources": sources,
        "tags": tags,
    }


def main() -> None:
    src = json.loads(SRC.read_text(encoding="utf-8"))
    models = []
    for m in src["models"]:
        name = m["modelName"]
        sources = m.get("sources") or {}
        hf = sources.get("HuggingFace")
        ms = sources.get("ModelScope")
        if not hf and not ms:
            continue
        tags = m.get("tags") or []
        cats = m.get("categories") or []
        desc = "、".join(tags) if tags else "MNN 量化模型"
        size = int(m.get("file_size") or 0)
        slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
        entry = {
            "id": f"mnn-{slug}",
            "name": name,
            "engine": "MNN",
            "vendor": m.get("vendor") or "",
            "description": desc,
            "sizeBytes": size,
            "kind": "mnn_repo",
            "localPath": name,
            "markerFile": "config.json",
            "sources": {"HuggingFace": hf, "ModelScope": ms},
            "tags": tags,
        }
        if cats:
            entry["categories"] = cats
        models.append(entry)

    # Legacy .task MediaPipe packs (kept for devices that prefer that format).
    extra = [
        {
            "id": "gemma3-1b-it-q4-task",
            "name": "Gemma3-1B-IT q4 (.task)",
            "engine": "LITERT",
            "vendor": "Google",
            "description": "LiteRT-LM / MediaPipe 模型包（Gallery allowlist 同源）",
            "sizeBytes": 554661246,
            "kind": "file",
            "localPath": "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            "fileInRepo": "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            "sources": {
                "HuggingFace": "litert-community/Gemma3-1B-IT",
                "ModelScope": "litert-community/Gemma3-1B-IT",
            },
            "tags": [],
        },
        {
            "id": "qwen2.5-1.5b-instruct-q8-task",
            "name": "Qwen2.5-1.5B-Instruct q8 (.task)",
            "engine": "LITERT",
            "vendor": "Qwen",
            "description": "LiteRT-LM 量化模型包",
            "sizeBytes": 1597913616,
            "kind": "file",
            "localPath": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "fileInRepo": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "sources": {
                "HuggingFace": "litert-community/Qwen2.5-1.5B-Instruct",
                "ModelScope": "litert-community/Qwen2.5-1.5B-Instruct",
            },
            "tags": [],
        },
        # Self-published Genie / QNN HTP build, hosted under the project's own HF
        # namespace. Not present in MnnLlmChat's `model_market.json`, so it is
        # maintained here — regenerate the catalog and this entry stays.
        #
        # kind=repo: Genie consumes a whole directory (genie_config.json +
        # tokenizer.json + 4 ctx-bin shards, ~3.2 GB), never a single file.
        # markerFile=genie_config.json is the same check GenieConfigResolver
        # validates on import. localPath is the folder name such a model arrives
        # as when copied from a device by hand, so an existing local copy is
        # recognised as this entry and relocated into the market layout.
        {
            "id": "genie-qwen3-4b-instruct-2507-qnn",
            "name": "Qwen3-4B-Instruct-2507 (QNN/Genie)",
            "engine": "GENIE",
            "vendor": "Qwen",
            "description": "骁龙 NPU（HTP）w4a16 · ctx 4096 · 4 分片 · 需 QAIRT 2.45+",
            "sizeBytes": 3184834343,
            "kind": "repo",
            "localPath": "qwen3_4b_instruct",
            "markerFile": "genie_config.json",
            "sources": {
                "HuggingFace": "pisces312-hf/Qwen3-4B-Instruct-2507-QNN-Genie",
            },
            "tags": ["NPU", "QNN", "Genie", "w4a16"],
        },
    ]
    extra.extend(litert_entry(row) for row in LITERT_MODELS)
    extra.extend(gguf_entry(row) for row in GGUF_MODELS)

    out = {"version": 2, "models": models + extra}
    DST.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    both = sum(1 for m in models if m["sources"]["HuggingFace"] and m["sources"]["ModelScope"])
    hf_only = sum(1 for m in models if m["sources"]["HuggingFace"] and not m["sources"]["ModelScope"])
    ms_only = sum(1 for m in models if m["sources"]["ModelScope"] and not m["sources"]["HuggingFace"])
    litert = sum(1 for m in extra if m["engine"] == "LITERT")
    print(
        f"mnn={len(models)} litert={litert} gguf={len(GGUF_MODELS)} total={len(out['models'])} "
        f"both={both} hf_only={hf_only} ms_only={ms_only}"
    )
    print(f"wrote {DST}")


if __name__ == "__main__":
    main()
