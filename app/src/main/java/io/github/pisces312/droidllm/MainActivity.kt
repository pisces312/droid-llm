package io.github.pisces312.droidllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.pisces312.droidllm.ui.DroidLlmRoot
import io.github.pisces312.droidllm.ui.theme.DroidLlmTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidLlmTheme {
                DroidLlmRoot()
            }
        }
    }
}
