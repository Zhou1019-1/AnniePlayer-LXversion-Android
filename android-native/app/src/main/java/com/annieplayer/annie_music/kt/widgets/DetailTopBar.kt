package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette

@Composable
internal fun DetailTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    count: String? = null,
    loading: Boolean = false,
    topInset: Boolean = true,
    endPadding: Dp = AppSpacing.sp16,
    blockTouches: Boolean = true,
    /** 返回箭头**右边**的图标动作（自建歌单的「删除整份歌单」用它，徽标紧贴返回键） */
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (blockTouches) Modifier.blockTouches() else Modifier)
            .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
            .padding(
                start = AppSpacing.sp12,
                end = endPadding,
                top = AppSpacing.sp40,
                bottom = AppSpacing.sp4,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickableRipple(onClose)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(
                modifier = Modifier.size(AppIconSize.icon22),
                tint = AppColors.textSecondary,
            )
        }
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
        }
        if (!title.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size17,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!count.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                    Text(
                        text = count,
                        color = AppColors.whiteA40,
                        fontSize = AppFontSize.size13,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon16),
                color = AppColors.white,
                strokeWidth = 2.dp,
            )
        } else {
            actions?.invoke(this)
        }
    }
}

@Composable
internal fun SelectModeActions(
    selectMode: Boolean,
    selectedAll: Boolean,
    onToggleSelectAll: () -> Unit,
    onToggleSelectMode: () -> Unit,
    accent: Color = AppColors.white,
    palette: ColorPalette? = null,
) {
    val fill = palette?.let { selectPillFill(it) }
    if (selectMode) {
        HeaderPillButton(
            label = if (selectedAll) "取消全选" else "全选",
            onClick = onToggleSelectAll,
            fill = fill,
            contentColor = accent,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
    }
    HeaderPillButton(
        label = "",
        onClick = onToggleSelectMode,
        fill = fill,
        contentColor = accent,
        glyph = {
            Icon(
                painter = painterResource(R.drawable.ic_action_multi_select),
                contentDescription = if (selectMode) "退出多选" else "多选",
                tint = accent,
                modifier = Modifier.size(AppIconSize.icon20),
            )
        },
    )
}

/**
 * 多选胶囊的底色：**页面主色压暗后的半透明**，而不是主色原色。
 *
 * 顶栏底下是极光背景 —— 它把页面主色压到很暗再铺开；胶囊若直接用主色原色，
 * 就是一块亮着的异色贴片，再高透明度也突兀。这里改为
 * 「黑 → 页面主色按 35% 混合」（等于极光的暗调同色系），再给 40% 透明度：
 * 色相跟随页面、明度贴住背景，白字仍有足够对比度。
 *
 * 取色失败（无封面 / 灰度）返回 null ⇒ [HeaderPillButton] 用固定的黑胶囊兜底。
 */
internal fun selectPillFill(palette: ColorPalette): Color? =
    if (palette.coverGridDim > 0) {
        lerp(Color.Black, palette.primary, 0.35f).copy(alpha = 0.4f)
    } else {
        null
    }
