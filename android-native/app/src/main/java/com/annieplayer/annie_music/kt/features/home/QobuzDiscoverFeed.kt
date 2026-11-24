package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverItem
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverKind
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverSection
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DetailChevronRight
import com.annieplayer.annie_music.kt.widgets.clickableRipple

internal val QobuzSectionPaddingH: Dp = 16.dp
internal val QobuzSectionGap: Dp = 24.dp
internal val QobuzCardGap: Dp = 8.dp
internal val QobuzGridColumnGap: Dp = 12.dp
internal val QobuzGridRowGap: Dp = 16.dp
internal val QobuzRowEndPadding: Dp = 24.dp
internal val QobuzCoverRadius: Dp = 6.dp
internal val QobuzTitleBaselineGap: Dp = 2.dp
internal val QobuzHeaderContentGap: Dp = 12.dp
internal val QobuzCardTextGap: Dp = 8.dp
internal val QobuzCardSubtitleGap: Dp = 2.dp
internal val QobuzTagTileHeight: Dp = 56.dp
internal val QobuzEmptyHintTopGap: Dp = 120.dp
internal val QobuzEmptyHintPaddingH: Dp = 32.dp

internal val QobuzCardWidthDefault: Dp = 170.dp
internal val QobuzCardWidthLarge: Dp = 266.dp
internal val QobuzCardWidthWide: Dp = 294.dp

internal fun qobuzCardWidth(kind: QobuzDiscoverKind): Dp = when (kind) {
    QobuzDiscoverKind.AlbumRowWide -> QobuzCardWidthWide
    QobuzDiscoverKind.AlbumRowLarge -> QobuzCardWidthLarge
    else -> QobuzCardWidthDefault
}

@Composable
internal fun QobuzDiscoverFeed(
    state: QobuzDiscoverState,
    onOpenTarget: (HomeTarget) -> Unit,
    onSeeAll: (QobuzDiscoverSection) -> Unit,
    onOpenTag: (QobuzDiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = state.sections
    if (sections.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = qobuzEmptyHint(state.failure)
            if (hint != null) {
                Spacer(modifier = Modifier.height(QobuzEmptyHintTopGap))
                Text(
                    text = hint.first,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.lg,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = hint.second,
                    color = AppColors.textSecondary,
                    fontSize = AppFontSize.base,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = QobuzEmptyHintPaddingH),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.searchListBottomInset),
        verticalArrangement = Arrangement.spacedBy(QobuzSectionGap),
    ) {
        items(items = sections, key = { it.id }, contentType = { it.kind }) { section ->
            QobuzDiscoverSectionBlock(
                section = section,
                onOpenTarget = onOpenTarget,
                onSeeAll = { onSeeAll(section) },
                onOpenTag = onOpenTag,
            )
        }
    }
}

@Composable
private fun QobuzDiscoverSectionBlock(
    section: QobuzDiscoverSection,
    onOpenTarget: (HomeTarget) -> Unit,
    onSeeAll: () -> Unit,
    onOpenTag: (QobuzDiscoverItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QobuzSectionPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickableRipple(onSeeAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.title,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp4))
                DetailChevronRight(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.whiteA50,
                )
            }
        }
        if (section.baseline.isNotEmpty()) {
            Spacer(modifier = Modifier.height(QobuzTitleBaselineGap))
            Text(
                text = section.baseline,
                color = AppColors.textSecondary,
                fontSize = AppFontSize.base,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = QobuzSectionPaddingH),
            )
        }
        Spacer(modifier = Modifier.height(QobuzHeaderContentGap))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = QobuzSectionPaddingH,
                end = QobuzRowEndPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(QobuzCardGap),
        ) {
            items(items = section.items, key = { it.key }, contentType = { section.kind }) { item ->
                QobuzCard(
                    item = item,
                    kind = section.kind,
                    onOpenTarget = onOpenTarget,
                    onOpenTag = onOpenTag,
                )
            }
        }
    }
}

@Composable
private fun QobuzCard(
    item: QobuzDiscoverItem,
    kind: QobuzDiscoverKind,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenTag: (QobuzDiscoverItem) -> Unit,
) {
    Box(modifier = Modifier.width(qobuzCardWidth(kind))) {
        QobuzCardBody(
            item = item,
            kind = kind,
            modifier = Modifier.fillMaxWidth(),
            onOpenTarget = onOpenTarget,
            onOpenTag = onOpenTag,
        )
    }
}

@Composable
internal fun QobuzCardBody(
    item: QobuzDiscoverItem,
    kind: QobuzDiscoverKind,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenTag: ((QobuzDiscoverItem) -> Unit)? = null,
) {
    if (kind == QobuzDiscoverKind.TagRow) {
        QobuzTagTile(item = item, modifier = modifier, onOpenTag = onOpenTag)
        return
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QobuzCoverRadius))
            .clickable(enabled = item.target != null) {
                item.target?.let(onOpenTarget)
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.height(QobuzCardWidthDefault) else Modifier.aspectRatio(1f))
                .clip(RoundedCornerShape(QobuzCoverRadius))
                .background(AppColors.surface),
        ) {
            if (item.cover.isNotEmpty()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.rank > 0) {
                QobuzRankBadge(rank = item.rank)
            }
        }
        Spacer(modifier = Modifier.height(QobuzCardTextGap))
        Text(
            text = item.title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.base,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(QobuzCardSubtitleGap))
            Text(
                text = item.subtitle,
                color = AppColors.textSecondary,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QobuzRankBadge(rank: Int) {
    Box(
        modifier = Modifier
            .padding(AppSpacing.sp8)
            .clip(RoundedCornerShape(AppSpacing.sp4))
            .background(AppColors.primary)
            .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
    ) {
        Text(
            text = rank.toString(),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.sm,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QobuzTagTile(
    item: QobuzDiscoverItem,
    modifier: Modifier = Modifier,
    onOpenTag: ((QobuzDiscoverItem) -> Unit)? = null,
) {
    val clickable = onOpenTag != null && item.slug.isNotEmpty()
    Box(
        modifier = modifier
            .height(QobuzTagTileHeight)
            .clip(RoundedCornerShape(QobuzCoverRadius))
            .background(qobuzTagColor(item))
            .then(if (clickable) Modifier.clickable { onOpenTag?.invoke(item) } else Modifier)
            .padding(horizontal = AppSpacing.sp12),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = item.title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.base,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun qobuzTagColor(item: QobuzDiscoverItem): Color {
    val parsed = qobuzParseHexColor(item.color)
    if (parsed != null) return parsed
    val palette = listOf(
        Color(0xFF1F3A5F),
        Color(0xFF3A1F5F),
        Color(0xFF5F1F3A),
        Color(0xFF1F5F4A),
        Color(0xFF5F4A1F),
        Color(0xFF2A2A3E),
    )
    var hash = 0
    for (ch in item.title) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return palette[hash % palette.size]
}

internal fun qobuzParseHexColor(raw: String): Color? {
    val text = raw.trim().removePrefix("#")
    if (text.length != 6 && text.length != 8) return null
    val value = text.toLongOrNull(16) ?: return null
    return if (text.length == 6) Color(value or 0xFF000000L) else Color(value)
}
