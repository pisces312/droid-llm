"""Convert MnnLlmChat model_market.json into droid-llm model_catalog.json."""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = Path(r"D:\3rd-party-projects\MNN\apps\Android\MnnLlmChat\app\src\main\assets\model_market.json")
DST = ROOT / "app" / "src" / "main" / "assets" / "model_catalog.json"


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
        {
            "id": "qwen2.5-1.5b-instruct-q4k-gguf",
            "name": "Qwen2.5-1.5B-Instruct Q4_K_M (gguf)",
            "engine": "LLAMACPP",
            "vendor": "Qwen",
            "description": "llama.cpp GGUF 量化权重",
            "sizeBytes": 1120000000,
            "kind": "file",
            "localPath": "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "fileInRepo": "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "sources": {"HuggingFace": "Qwen/Qwen2.5-1.5B-Instruct-GGUF"},
            "tags": [],
        },
    ]

    out = {"version": 2, "models": models + extra}
    DST.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    both = sum(1 for m in models if m["sources"]["HuggingFace"] and m["sources"]["ModelScope"])
    hf_only = sum(1 for m in models if m["sources"]["HuggingFace"] and not m["sources"]["ModelScope"])
    ms_only = sum(1 for m in models if m["sources"]["ModelScope"] and not m["sources"]["HuggingFace"])
    print(f"mnn={len(models)} total={len(out['models'])} both={both} hf_only={hf_only} ms_only={ms_only}")
    print(f"wrote {DST}")


if __name__ == "__main__":
    main()
