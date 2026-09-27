package io.github.pisces312.droidllm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pisces312.droidllm.R

/**
 * Vendor logo drawn next to a model name, so a list of models can be skimmed by brand
 * instead of read line by line.
 *
 * Assets are WebP copies of the vendor marks MnnLlmChat ships in its `drawable-nodpi`
 * directory, shrunk from 1024px to 192px. The UI shows 40dp, so the whole set costs 96KB
 * instead of 913KB. Only vendors our catalog actually uses are carried over — an unused
 * logo is dead weight in every APK.
 *
 * These are trademarks of their respective owners, used to identify the origin of a
 * model. See `docs/LICENSING.md`.
 *
 * Vendors without a logo fall back to their initial in a drawn tile, which keeps the
 * column aligned instead of leaving a ragged edge.
 */
@Composable
fun VendorLogo(
    vendor: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    val res = vendor?.trim()?.lowercase()?.let { VENDOR_LOGOS[it] }
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = vendor,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                vendor?.trim()?.take(1)?.uppercase().orEmpty().ifEmpty { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * Catalog `vendor` values that have a logo, keyed lowercase.
 *
 * Keyed by the exact catalog spelling rather than by substring. A substring match would
 * hand `TinyLlama` the Meta Llama mark and `Google` the Gemma one, which misattributes
 * both — showing nothing is better than showing the wrong brand.
 */
private val VENDOR_LOGOS: Map<String, Int> = mapOf(
    "qwen" to R.drawable.qwen_icon,
    "smol" to R.drawable.smolm_icon,
    "gemma" to R.drawable.gemma_icon,
    "deepseek" to R.drawable.deepseek_icon,
    "llama" to R.drawable.llama_icon,
    "hunyuan" to R.drawable.hunyuan_icon,
    "thudm" to R.drawable.chatglm_icon,
    "minicpm" to R.drawable.minicpm_icon,
    "internlm" to R.drawable.internlm_icon,
    "gpt" to R.drawable.openai_icon,
    "01.ai" to R.drawable.yi_icon,
    "baichuan" to R.drawable.baichuan_icon,
    "phi" to R.drawable.phi_icon,
)
