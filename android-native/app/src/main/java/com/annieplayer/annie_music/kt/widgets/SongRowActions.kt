package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize

/**
 * 行尾爱心画成哪一种。
 *
 * - [Normal]：未收藏描边、已收藏填红（除「我喜欢」以外的所有页面）；
 * - [RemoveOnly]：「我喜欢」列表专用 —— 那里每一行都已经收藏，爱心恒定画成空心斜杠，
 *   点它就是**取消收藏**（行随即从列表消失）。红心+斜杠会读成「差一点收藏」，所以那种页面
 *   不该复用 Normal 的两种状态。
 */
enum class SongLikeStyle { Normal, RemoveOnly }

/**
 * 「这首歌收藏了没有」的宿主注入点。
 *
 * 为什么用 CompositionLocal 而不是层层传参：行组件有 8 套各自独立的实现
 * （搜索结果 / 本地曲库两种 / 专辑 / 歌单 / 歌手 / Qobuz / KKBOX / 主页货架），
 * 每一套的宿主页面又各不相同。把 `likedKeys + onToggle` 沿调用链传下去，等于给
 * 十几个签名各加两个参数，而它们的语义完全一样。
 *
 * [enabled] = false 时**行尾不出现爱心**（没有宿主注入的预览 / 测试场景）。
 *
 * [onRemoveSong] 与 [onToggleSong] 分开：前者是幂等的「移除」，只有 [SongLikeStyle.RemoveOnly]
 * 用它。用 toggle 顶替会在连点时把歌重新加回来（第二次 toggle = 加）。
 */
data class SongLikeBridge(
    val likedKeys: Set<String>,
    val onToggleSong: (Song) -> Unit,
    val onRemoveSong: (Song) -> Unit = onToggleSong,
    val enabled: Boolean = true,
    val style: SongLikeStyle = SongLikeStyle.Normal,
)

val LocalSongLike = compositionLocalOf {
    SongLikeBridge(likedKeys = emptySet(), onToggleSong = {}, enabled = false)
}

/**
 * 歌曲行尾的一组按钮：`♥ · ⋮`（喜欢 + 歌曲操作）。
 *
 * 两个按钮**尺寸完全相同**（[ActionSize]）、并排无间隙，所以在任何行里都读成一组，
 * 而不是「一个图标 + 一个附属图标」。
 *
 * 为什么单独成文件：这组按钮出现在搜索结果、自建歌单、专辑 / 歌单 / 歌手详情、
 * 本地曲库、主页货架这些各自独立的行实现里。以前每处自己画一个 ⋮，改一次尺寸要改
 * 六七个文件；现在「行尾长什么样」只有这一份，行只负责把自己的 `onMore` 交出来。
 *
 * 多选态**不走这里**：那时行尾换成一个勾选框（见 [SelectCheckBox]），由各行自己判断。
 *
 * @param song 这一行的歌（爱心状态按 `FavoriteKeys.song(song)` 查宿主给的集合）
 * @param onMore 点 ⋮（打开歌曲操作面板）
 * @param downloading 下载中：⋮ 换旋转环
 */
@Composable
fun SongRowActions(
    song: Song,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    downloading: Boolean = false,
) {
    val bridge = LocalSongLike.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bridge.enabled) {
            if (bridge.style == SongLikeStyle.RemoveOnly) {
                // 「我喜欢」列表：恒定空心斜杠爱心 → 取消收藏（列表里这一行随即消失）
                Box(
                    modifier = Modifier
                        .size(ActionSize)
                        .clickableRipple { bridge.onRemoveSong(song) }
                        .semantics { contentDescription = "取消喜欢 ${song.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    HeartOffGlyph(modifier = Modifier.size(AppIconSize.icon20))
                }
            } else {
                val liked = FavoriteKeys.song(song) in bridge.likedKeys
                Box(
                    modifier = Modifier
                        .size(ActionSize)
                        .clickableRipple { bridge.onToggleSong(song) }
                        .semantics {
                            contentDescription = if (liked) "取消喜欢 ${song.title}" else "喜欢 ${song.title}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    HeartGlyph(
                        modifier = Modifier.size(AppIconSize.icon20),
                        // 未收藏用半透明白描边（与播放页 / 详情页的爱心同一档），收藏后填正红
                        tint = if (liked) AppColors.heart else AppColors.glyphStrong,
                        filled = liked,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(ActionSize)
                .clickableRipple(onMore)
                .semantics { contentDescription = "更多操作 ${song.title}" },
            contentAlignment = Alignment.Center,
        ) {
            SongMoreGlyph(tint = AppColors.textTertiary, downloading = downloading)
        }
    }
}

/**
 * 单个按钮的边长（44dp = [AppSize.detailDownloadBarHeight]，与搜索框右端放大镜、
 * 标题行多选图标同一档）。两个按钮共用它 —— 「一样大」是这组按钮的定义，不要各传一个尺寸。
 */
private val ActionSize = AppSize.detailDownloadBarHeight
