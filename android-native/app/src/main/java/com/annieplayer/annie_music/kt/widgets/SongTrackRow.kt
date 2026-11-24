package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

@Composable
fun SongTrackRow(
    song: Song,
    subtitle: String,
    index: Int,
    playing: Boolean,
    playingTint: Color,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    downloadStatus: DownloadStatus? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.detailRowHeight)
            .then(
                when {
                    playing -> Modifier.playingRowBackground(playingTint)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .clickableNoRipple { if (selectMode) onToggleSelect?.invoke() else onClick() }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(AppSpacing.sp28),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                PlayingIndicator(tint = playingTint)
            } else {
                Text(
                    text = "${index + 1}",
                    color = AppColors.white60,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        SongRowCoverArt(coverUrl = song.cover)
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.title,
                color = if (playing) playingTint else AppColors.textPrimary,
                fontSize = AppFontSize.lg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectMode && onToggleSelect != null) {
            SelectCheckBox(checked = selected, onToggle = onToggleSelect)
        } else {
            SongRowActions(
                song = song,
                onMore = onMore,
                downloading = downloadStatus == DownloadStatus.downloading ||
                    downloadStatus == DownloadStatus.pending,
            )
        }
    }
}
