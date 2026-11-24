package com.annieplayer.annie_music.kt.widgets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import kotlinx.coroutines.delay

/** 复制按钮图标边长（贴着字段文字右侧，不吃行高） */
private val CopyGlyphSize = 14.dp

/** 命中区（视觉 14dp，手指按得到的 28dp） */
private val CopyHitSize = 28.dp

/** 「已复制」状态的停留时长 */
private const val CopiedStateMillis = 1200L

/**
 * 字段右侧的「复制」按钮：点一下把 [text] 写进系统剪贴板。
 *
 * 反馈有两层：
 * 1. **按钮自身进入「已复制」状态** —— 图标切到 [R.drawable.ic_action_copy_done]（勾），
 *    颜色补间到主色，停留 [CopiedStateMillis] 后自动回到复制图标。状态跟着 [text] 走：
 *    换了一行/换了一首歌就自动复位，不会把「已复制」带到别的字段上。
 * 2. 一句 `已复制` 的 Toast：图标只有 14dp，滚动中或视线不在按钮上时，勾可能被漏掉。
 *
 * 图标都是 VectorDrawable（Android 侧的 SVG），用 `tint` 上色，不跟文字颜色走。
 * 空白字段不渲染，由调用方用 `isNotBlank` 过滤。
 */
@Composable
fun CopyFieldButton(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedStateMillis)
            copied = false
        }
    }

    val tint by animateColorAsState(
        targetValue = if (copied) AppColors.primary else AppColors.whiteA40,
        animationSpec = tween(durationMillis = 120),
        label = "copyFieldTint",
    )

    Box(
        modifier = modifier
            .size(CopyHitSize)
            .clickableNoRipple {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("AnnieMusic", text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                copied = true
            }
            .semantics { contentDescription = if (copied) "已复制" else "复制" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (copied) R.drawable.ic_action_copy_done else R.drawable.ic_action_copy,
            ),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(CopyGlyphSize),
        )
    }
}
