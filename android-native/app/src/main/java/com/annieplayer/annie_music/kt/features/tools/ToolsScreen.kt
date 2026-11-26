package com.annieplayer.annie_music.kt.features.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppTopBar

private val ToolCardHeight = 176.dp
private val ToolCardRadius = 20.dp
private val ToolBadgeSize = 56.dp
private val ToolBadgeGlyph = 28.dp

private data class ToolEntry(
    val id: String,
    val title: String,
    val description: String,
    val accent: Color,
)

private val RemotePcTool = ToolEntry(
    id = "remote-pc",
    title = "遥控 PC 端安妮播放器",
    description = "手机控制电脑端播放：播放、进度、音量、歌词、曲库点播",
    accent = AppColors.primary,
)

private val ToolEntries = listOf(RemotePcTool)

@Composable
fun ToolsScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    uiViewModel: UiViewModel = activityViewModel(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        AppTopBar(title = "工具箱", onOpenMenu = onOpenMenu)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppSpacing.sp16,
                end = AppSpacing.sp16,
                top = AppSpacing.sp4,
                bottom = AppSpacing.listBottomInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
        ) {
            items(ToolEntries.size) { index ->
                val tool = ToolEntries[index]
                ToolCard(
                    tool = tool,
                    onClick = {
                        if (tool.id == RemotePcTool.id) uiViewModel.setLanRemoteVisible(true)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ToolCardRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed) AppColors.whiteA05 else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "toolCardPress",
    )
    Column(
        modifier = modifier
            .height(ToolCardHeight)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AppColors.whiteA06, AppColors.whiteA03)))
            .background(pressTint)
            .border(AppBorder.thin, AppColors.whiteA15, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ToolBadgeSize)
                .background(tool.accent.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RemotePcGlyph(modifier = Modifier.size(ToolBadgeGlyph), tint = tool.accent)
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        Text(
            text = tool.title,
            color = AppColors.white,
            fontSize = AppFontSize.size15,
            fontWeight = AppFontWeight.semiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 19.sp,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Text(
            text = tool.description,
            color = AppColors.white54,
            fontSize = AppFontSize.sm,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 16.sp,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
