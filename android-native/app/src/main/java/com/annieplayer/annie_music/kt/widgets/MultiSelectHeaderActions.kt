package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 标题行右端的多选簇：`清空 · 已选 N 首 · ⇉`。搜索页与歌手「全部单曲」页共用。
 *
 * 三条硬约束，改之前先确认：
 * 1. **未选中时整簇不出现**（`count <= 0` 直接不组合）。多选只有「长按行」一个入口，
 *    标题行不再兼任入口 —— 否则那个图标在常态下是个没有对象的按钮。
 * 2. **图标命中区 44×44、行尾内缩 16dp**：搜索结果页搜索框右端的放大镜也是
 *    「44dp 命中区 + 右内缩 16」，两者因此同心，图标正好压在放大镜那一列上
 *    （改尺寸/内缩必须两边一起改）。
 * 3. 顺序固定 `清空 → 已选 → 图标`：清空是最左的次要动作，图标是最右的主动作。
 */
@Composable
fun MultiSelectHeaderActions(
    count: Int,
    onClear: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickableRipple(onClear)
                // 竖向补 8dp 让「清空」和 22dp 图标有接近的命中高度，横向只留 4dp 以免离数字太远
                .padding(horizontal = AppSpacing.sp4, vertical = AppSpacing.sp8),
            contentAlignment = Alignment.Center,
        ) {
            // 与图标同色：这一簇里没有「主色按钮」，层级靠字号（12 / 13sp）与间距拉开
            Text(text = "清空", color = AppColors.textPrimary, fontSize = AppFontSize.sm)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Text(
            text = "已选 $count 首",
            color = AppColors.white,
            fontSize = AppFontSize.size13,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .size(AppSize.detailDownloadBarHeight)
                .clickableRipple(onOpenActions),
            contentAlignment = Alignment.Center,
        ) {
            MultiSelectGlyph(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.white)
        }
    }
}
