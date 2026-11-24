package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

private val LatestReleaseCoverSize = 88.dp

private val LatestReleaseAddSize = 44.dp

private val LatestReleaseFullDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

private val LatestReleaseYearMonth = Regex("""^(\d{4})-(\d{1,2})$""")

private val LatestReleaseYearOnly = Regex("""^(\d{4})$""")

internal fun latestReleaseDateText(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    val head = text.take(10)
    LatestReleaseFullDate.find(head)?.let { m ->
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month in 1..12 && day in 1..31) return "${year}年${month}月${day}日"
        return text
    }
    LatestReleaseYearMonth.find(head)?.let { m ->
        val month = m.groupValues[2].toInt()
        if (month in 1..12) return "${m.groupValues[1].toInt()}年${month}月"
    }
    LatestReleaseYearOnly.find(head)?.let { m ->
        return "${m.groupValues[1].toInt()}年"
    }
    return text
}

internal fun latestReleaseTrackCountText(count: Int): String =
    if (count > 0) "$count 首歌曲" else ""

internal fun latestReleaseTitle(name: String, kind: String): String =
    if (kind.isEmpty()) name else "$name - $kind"

@Composable
internal fun LatestReleaseCard(
    cover: Any?,
    dateText: String,
    title: String,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r20))
            .background(AppColors.whiteA06)
            .clickableRipple(onClick)
            .padding(AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
    ) {
        Box(
            modifier = Modifier
                .size(LatestReleaseCoverSize)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA06),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sp2),
        ) {
            if (dateText.isNotEmpty()) {
                Text(
                    text = dateText,
                    color = AppColors.textSecondary,
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (countText.isNotEmpty()) {
                Text(
                    text = countText,
                    color = AppColors.textSecondary,
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onAdd != null) {
            Box(
                modifier = Modifier
                    .size(LatestReleaseAddSize)
                    .clip(CircleShape)
                    .background(AppColors.whiteA06)
                    .clickableRipple(onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "收藏",
                    tint = AppColors.textPrimary,
                    modifier = Modifier.size(AppIconSize.icon20),
                )
            }
        }
    }
}
