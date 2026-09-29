package io.github.pisces312.droidllm.benchmark
import io.github.pisces312.droidllm.benchmark.R

/**
 * Built-in benchmark prompts (DESIGN.md §2.2): Chinese QA / English QA / code / summarize.
 * Single-select at run time.
 */
object BenchmarkPrompts {

    val chineseQa = BenchPrompt(
        id = "zh_qa",
        labelRes = R.string.bench_prompt_zh_qa,
        text = "请用大约两百字介绍 Android 端侧大语言推理的常见加速方式，" +
            "包括 CPU、GPU delegate、OpenCL 与 NPU/HTP，并说明各自的适用场景与取舍。",
    )

    val englishQa = BenchPrompt(
        id = "en_qa",
        labelRes = R.string.bench_prompt_en_qa,
        text = "In about two hundred words, explain common approaches to on-device LLM " +
            "acceleration on Android, including CPU, GPU delegates, OpenCL, and NPU/HTP. " +
            "Discuss typical trade-offs and when each path is preferred.",
    )

    val code = BenchPrompt(
        id = "code",
        labelRes = R.string.bench_prompt_code,
        text = "Write a Kotlin function that computes the median of a List<Double>, " +
            "ignoring nulls, and explain its time complexity in one sentence.",
    )

    val summarize = BenchPrompt(
        id = "summarize",
        labelRes = R.string.bench_prompt_summarize,
        text = "请将下面这段话压缩成三点要点：\n" +
            "端侧推理评测应关注加载耗时、首 token 延迟、持续解码速度与内存占用。" +
            "不同引擎往往使用不同量化与模型格式，跨模型直接比数字容易误导。" +
            "因此结果表必须完整记录模型名、量化与路径，结论只作参考。",
    )

    val all: List<BenchPrompt> = listOf(chineseQa, englishQa, code, summarize)

    val default: BenchPrompt = chineseQa

    fun byId(id: String): BenchPrompt = all.firstOrNull { it.id == id } ?: default
}
