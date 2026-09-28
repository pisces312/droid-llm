from pathlib import Path
p = Path(r"D:\my-projects\droid-llm\tools\llama_decode_repro\prompt.txt")
body = (
    "system\n"
    "You are a helpful assistant.\n"
    "user\n"
    "Please introduce yourself briefly in English.\n"
    "assistant\n"
)
p.write_text(body, encoding="utf-8")
print("wrote", p, "bytes", p.stat().st_size, "chars", len(body))
