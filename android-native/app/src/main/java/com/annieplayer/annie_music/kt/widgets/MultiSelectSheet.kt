package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「多选操作」抽屉：单曲搜索结果与歌手「全部单曲」页共用。
 *
 * 页面只负责「打开它 + 交出已选歌曲」；两个动作都由宿主执行，所以这里只发回调、
 * 不碰任何数据层。
 *
 * 与 ⋮ 面板的两处刻意差异：
 * 1. **两个动作**（下载 / 加入到歌单），没有「我喜欢」「歌手」「专辑」那些单曲专属项；
 * 2. **加入到歌单点完就整条收起**（二级面板 + 抽屉一起关）—— 单曲那边是「加完不关」，
 *    因为那里还要接着加进别的歌单；这里是批量动作，做完就该回到列表。
 *
 * 二级面板复用 [PlaylistPickerSheet]（`onAddToFavorites = null` ⇒ 不渲染「我喜欢」行）。
 */
@Composable
internal fun MultiSelectSheet(
    visible: Boolean,
    count: Int,
    playlistOptions: List<SongPlaylistOption>,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: (playlistId: String) -> Unit,
    local: Boolean = false,
    onDelete: () -> Unit = {},
) {
    var pickerVisible by remember { mutableStateOf(false) }
    // 抽屉收起时复位二级面板，否则下次打开会直接闪出「添加到」
    LaunchedEffect(visible) {
        if (!visible) pickerVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppSheet(
            visible = visible,
            title = "多选操作",
            onClose = onClose,
            contentMaxHeightFraction = 1f,
        ) {
            // 选项上面显示选中数目：两个动作都是「对这一批歌」生效，主语要先摆出来
            Text(
                text = "已选 $count 首",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp4),
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
            )

            SongActionsRow(
                icon = { tint ->
                    if (local) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(AppIconSize.icon22),
                        )
                    } else {
                        DownloadIconPlaceholder(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = tint,
                        )
                    }
                },
                label = if (local) "删除" else "下载",
                subtitle = if (local) "从曲库移除" else "选择音质",
                tone = if (local) SongActionTone.Danger else SongActionTone.Accent,
                showsChevron = false,
                onTap = if (local) onDelete else onDownload,
            )

            SongActionsRow(
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                },
                label = "加入到歌单",
                subtitle = if (playlistOptions.isEmpty()) {
                    "还没有自建歌单"
                } else {
                    "${playlistOptions.size} 个歌单"
                },
                showsChevron = false,
                onTap = { pickerVisible = true },
            )

            Spacer(modifier = Modifier.height(AppSpacing.sp6))
        }

        // 二级：选择歌单。点中之后**连抽屉一起收**（与 ⋮ 面板相反，见文件头注释）
        PlaylistPickerSheet(
            visible = visible && pickerVisible,
            options = playlistOptions,
            favoriteSelected = false,
            favoriteCount = 0,
            onClose = { pickerVisible = false },
            onSelect = { playlistId ->
                pickerVisible = false
                onAddToPlaylist(playlistId)
            },
            onAddToFavorites = null,
        )
    }
}
