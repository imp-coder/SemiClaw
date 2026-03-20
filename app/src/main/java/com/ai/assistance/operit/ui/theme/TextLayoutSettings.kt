package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.UserPreferencesManager

/**
 * AI Markdown 文本布局设置。
 *
 * - lineHeightMultiplier：额外行距倍率，1f 为默认值
 * - letterSpacingSp：额外字距，单位 sp
 */
data class AiMarkdownTextLayoutSettings(
    val lineHeightMultiplier: Float = 1f,
    val letterSpacingSp: Float = 0f
)

val LocalAiMarkdownTextLayoutSettings = compositionLocalOf {
    AiMarkdownTextLayoutSettings()
}

@Composable
fun ProvideAiMarkdownTextLayoutSettings(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val lineHeightMultiplier by preferencesManager.aiMarkdownLineHeightMultiplier.collectAsState(initial = 1f)
    val letterSpacing by preferencesManager.aiMarkdownLetterSpacing.collectAsState(initial = 0f)

    val settings = remember(lineHeightMultiplier, letterSpacing) {
        AiMarkdownTextLayoutSettings(
            lineHeightMultiplier = lineHeightMultiplier,
            letterSpacingSp = letterSpacing
        )
    }

    CompositionLocalProvider(LocalAiMarkdownTextLayoutSettings provides settings) {
        content()
    }
}
