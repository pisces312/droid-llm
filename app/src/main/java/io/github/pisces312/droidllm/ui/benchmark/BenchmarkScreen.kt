package io.github.pisces312.droidllm.ui.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * P0 placeholder. P4 implements L/P/D/T cases, model picker per engine,
 * RSS three-phase deltas, and JSON export.
 */
@Composable
fun BenchmarkScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Text("Benchmark", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "P4 实现。计划用例：L(加载) / P(Prefill) / D(Decode) / T(持续TPS，可选)。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "默认 warmup=1，runs=3 取中位数。跑某引擎前强制 unload 其他 Session。" +
                "RSS 采集 baseline→加载后→峰值三段。结果表强制展示 engineId/modelName/modelPath/quantHint。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
