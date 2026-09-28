"""Convert MnnLlmChat model_market.json into droid-llm model_catalog.json.

Also appends hand-curated LiteRT / llama.cpp GGUF entries (single-file Q4_K_M
where possible; params ≤ 10B). GGUF rows were verified against the HF tree API
(hf-mirror.com) — keep fileInRepo / sizeBytes in sync when editing.
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
            "sources": {"HuggingFace": "litert-community/Gemma3-1B-IT"},
            "tags": [],
        },
        {
            "id": "qwen2.5-1.5b-instruct-q8-task",
            "name": "Qwen2.5-1.5B-Instruct q8 (.task)",
            "engine": "LITERT",
            "vendor": "Qwen",
            "description": "LiteRT-LM 量化模型包",
            "sizeBytes": 1625493432,
            "kind": "file",
            "localPath": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "fileInRepo": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "sources": {"HuggingFace": "litert-community/Qwen2.5-1.5B-Instruct"},
            "tags": [],
        },
    ]
    extra.extend(gguf_entry(row) for row in GGUF_MODELS)

    out = {"version": 2, "models": models + extra}
    DST.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    both = sum(1 for m in models if m["sources"]["HuggingFace"] and m["sources"]["ModelScope"])
    hf_only = sum(1 for m in models if m["sources"]["HuggingFace"] and not m["sources"]["ModelScope"])
    ms_only = sum(1 for m in models if m["sources"]["ModelScope"] and not m["sources"]["HuggingFace"])
    print(
        f"mnn={len(models)} litert=2 gguf={len(GGUF_MODELS)} total={len(out['models'])} "
        f"both={both} hf_only={hf_only} ms_only={ms_only}"
    )
    print(f"wrote {DST}")


if __name__ == "__main__":
    main()
