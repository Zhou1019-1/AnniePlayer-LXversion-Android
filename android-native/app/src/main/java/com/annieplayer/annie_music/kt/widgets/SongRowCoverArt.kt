package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize

/**
 * 歌曲行里的方封面（[AppSize.songRowCover] 44dp、圆角 [AppRadius.r6]、底色 [AppColors.whiteA03]）。
 *
 * 抽出来是为了让六处歌曲行用**同一张**封面：以前歌手 / 主页 / 本地各写一份 40dp，歌单详情
 * 干脆没有封面 —— 同一个列表体系里封面大小不一致、缺一块，比少一个圆角更明显。
 *
 * 空地址或加载失败退回音符占位（失败态跟着 [coverUrl] 复位，列表复用不会串图）。
 */
@Composable
fun SongRowCoverArt(coverUrl: String, modifier: Modifier = Modifier) {
    var failed by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(AppSize.songRowCover)
            .clip(RoundedCornerShape(AppRadius.r6))
            .background(AppColors.whiteA03),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isEmpty() || failed) {
            MusicNoteGlyph(
                modifier = Modifier.size(AppIconSize.icon16),
                tint = AppColors.whiteA15,
            )
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
