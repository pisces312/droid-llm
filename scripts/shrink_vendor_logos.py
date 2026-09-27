"""Shrink the MnnLlmChat vendor logos into drawable-nodpi as 192px WebP.

Upstream ships 1024x1024 PNGs totalling 913KB (smolm alone is 407KB). The UI draws them
at 40dp, so 192px covers a 4x-density screen with room to spare and the whole set lands
under 100KB. Only vendors our catalog actually uses are carried over — a logo for a
vendor nothing references is dead weight in every APK.

The source tree is found via MNN_LLM_CHAT_ROOT, the same variable AGENTS.md lists under
"reference sources", so no machine-specific path is baked into the repo. The output goes
next to the other app resources.

Run: MNN_LLM_CHAT_ROOT=/path/to/MNN/apps/Android/MnnLlmChat python scripts/shrink_vendor_logos.py
"""

import os
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
DST = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# Vendors present in app/src/main/assets/model_catalog.json that upstream has a logo for.
# Keys are the file stems; the catalog vendor each one serves is in VendorLogo.kt.
KEEP = [
    "baichuan",
    "chatglm",
    "deepseek",
    "gemma",
    "hunyuan",
    "internlm",
    "llama",
    "minicpm",
    "openai",
    "phi",
    "qwen",
    "smolm",
    "yi",
]

TARGET_PX = 192
QUALITY = 88


def main() -> int:
    root = os.environ.get("MNN_LLM_CHAT_ROOT")
    if not root:
        print("MNN_LLM_CHAT_ROOT is not set", file=sys.stderr)
        return 1
    src_dir = Path(root) / "app" / "src" / "main" / "res" / "drawable-nodpi"
    if not src_dir.is_dir():
        print(f"source directory not found: {src_dir}", file=sys.stderr)
        return 1

    DST.mkdir(parents=True, exist_ok=True)
    total = 0
    for name in KEEP:
        src = next((src_dir / f"{name}_icon{ext}" for ext in (".png", ".webp") if (src_dir / f"{name}_icon{ext}").exists()), None)
        if src is None:
            print(f"{name:10s} MISSING SOURCE")
            continue
        with Image.open(src) as image:
            image = image.convert("RGBA").resize((TARGET_PX, TARGET_PX), Image.LANCZOS)
            out = DST / f"{name}_icon.webp"
            image.save(out, "WEBP", quality=QUALITY, method=6)
        size = out.stat().st_size
        total += size
        print(f"{name:10s} {size / 1024:6.1f}KB")
    print(f"{'TOTAL':10s} {total / 1024:6.1f}KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
