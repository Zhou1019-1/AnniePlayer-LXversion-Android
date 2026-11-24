package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 顶栏胶囊高度（对齐「新发现」room 页返回键的 38dp 圆钮） */
private val PillHeight = 38.dp

/** 胶囊左右内边距 */
private val PillPadH = 14.dp

/** 纯图标胶囊的左右内边距（38dp 高 + 20dp 图标 ≈ 正方形命中区） */
private val PillIconPadH = 10.dp

/** 胶囊底色：暗色卡片底（[AppColors.surface]）+ 60% 不透明度 */
private val PillFill = Color.Black.copy(alpha = 0.4f)

/**
 * 顶栏上的圆形／胶囊按钮。
 *
 * 样式取自本项目「新发现 → room 页」的返回键：**38dp 高、white 14% 实心、无描边无投影**，
 * 内容（图标 / 文字）纯白。比之前那颗玻璃胶囊更实、更小，在封面取色背景上也不会被冲淡。
 *
 * 详情页顶栏的「多选 / 完成」与多选态的「全选」都用它 —— 两个按钮必须同一套语言，
 * 否则一进多选就是「一颗胶囊 + 一段裸文字」。
 *
 * @param label 按钮文案
 * @param glyph 可选的左图标（跟随文字颜色，20dp）
 */
@Composable
fun HeaderPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null,
    fill: Color? = null,
    contentColor: Color = AppColors.white,
) {
    Row(
        modifier = modifier
            .height(PillHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(fill ?: PillFill)
            .clickableRipple(onClick)
            .padding(horizontal = if (label.isEmpty()) PillIconPadH else PillPadH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            glyph()
            if (label.isNotEmpty()) Spacer(modifier = Modifier.width(AppSpacing.sp6))
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = contentColor,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
