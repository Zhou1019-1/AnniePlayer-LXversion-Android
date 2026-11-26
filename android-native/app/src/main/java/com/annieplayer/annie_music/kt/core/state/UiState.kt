package com.annieplayer.annie_music.kt.core.state

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.core.routing.OverlayVisibilityFlags
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.local.TagEditForm
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 状态（1:1 移植 Flutter `core/providers/` 下的 `ui_provider.dart`，对齐 RN 版
 * store/useUIStore.ts）。
 *
 * 本层只承载「浮层可见性 + 详情数据」这一组纯 UI 状态。浮层之间叠放顺序敏感，
 * 因此字段名与 Dart 完全一致：songActionsVisible / artistPickerVisible /
 * pickerVisible / drawerVisible / playerOverlayVisible / queueVisible /
 * downloadQueueVisible，UI 层据此镜像同一套层序。
 *
 * 与 Dart 的唯一结构性差异：专辑 / 歌手 / 歌单三个全屏详情不再各占一对
 * `xDetailVisible` + `xDetailData` 布尔槽，而是共用一条**真栈** [pageStack]：
 * 从任意子页面跳转到另一个子页面时原页面不被收起，返回逐层退回（见 [AppRoute]）。
 */

/** 专辑详情数据（对齐 Dart `AlbumDetailData`） */
data class AlbumDetailData(
    val id: String,
    val cover: String,
    val name: String,
    val artist: String,
    /** 所属平台（详情页按它取曲目） */
    val platform: MusicPlatform = MusicPlatform.kuwo,
    /**
     * 专辑歌手 id（有就「点歌手」秒开；没有就退回按名字搜索）。
     *
     * 从 [com.annieplayer.annie_music.kt.data.model.Album] 进来的入口能带上（酷我已读 `artistid`），
     * 从单曲进来的入口只有单歌手时才敢认（合辑的专辑歌手≠单曲歌手）。
     */
    val artistId: String = "",
)

/** 歌手详情数据（对齐 Dart `SingerDetailData`） */
data class SingerDetailData(
    val id: String,
    val name: String,
    val picture: String,
    /** 所属平台（详情页按它取单曲 / 专辑） */
    val platform: MusicPlatform = MusicPlatform.kuwo,
)

/** 歌单详情数据（对齐 Dart `PlaylistDetailData`） */
data class PlaylistDetailData(
    val id: String,
    /** 平台标识（Dart 为 String，UI 层原样透传） */
    val platform: String,
    val name: String? = null,
    val cover: String? = null,
    /** 榜单复用歌单面板时置 true：id 是榜单 id，不是歌单 id，不能回跳歌单 */
    val isRank: Boolean = false,
)

data class PlaylistDetailPreload(
    val name: String,
    val creator: String = "",
    val cover: String = "",
    val songs: List<com.annieplayer.annie_music.kt.data.model.Song>,
)

/**
 * 页面路由的类型标签。
 *
 * 用途是把「同一类页面」作为一个整体关掉（例如从某个详情跳走时清掉同类的旧条目）。
 * 与渲染无关，渲染只看 [AppRoute] 的具体类型。
 */
enum class RouteKind {
    Album,
    Singer,
    Playlist,

    /** 主页区块的「全部」子页（歌单 / 专辑卡片的完整列表） */
    HomeShelf,

    /** Qobuz 发现页区块的「全部」子页 */
    QobuzSeeAll,

    /** Qobuz「Playlists by Category」分类下的歌单列表页 */
    QobuzTag,

    /** 酷狗「按风格听」的流派二级页 */
    KugouStyle,

    /** 榜单详情页 */
    RankDetail,

    /** Apple「新发现」区块的「全部」页（room） */
    NewTabRoom,

    /** Apple「排行榜」区块的「查看全部」页（room） */
    AppleRankRoom,

    /** 本地曲库：一级列表页（艺术家 / 专辑 / 文件夹 / 最近添加） */
    LocalSection,

    /** 收藏库：一级子页（我喜欢 / 艺人 / 专辑 / 播放列表） */
    FavSection,

    /** 收藏库：自建播放列表详情（从「播放列表」子页进入） */
    UserPlaylist,

    /** 本地曲库：专辑详情 */
    LocalAlbum,

    /** 本地曲库：歌手详情 */
    LocalArtist,

    /** 本地曲库：歌手的「全部歌曲」子页 */
    LocalArtistSongs,

    /** 本地曲库：歌手的「全部专辑」子页 */
    LocalArtistAlbums,

    /** 流媒体歌手的「全部单曲」子页（overview 之上压一层） */
    SingerSongs,

    /** 流媒体歌手的「全部专辑」子页（Apple 从这里再分 EP/单曲 与专辑两段） */
    SingerAlbums,

    /** 本地曲库：文件夹内容 */
    LocalFolder,

    /** 本地曲库：单曲「编辑元数据」页（从歌曲详情面板底部进入） */
    LocalTrackEdit,

    /** 本地曲库：单曲「搜索元数据」页（从编辑页顶栏的搜索图标进入） */
    LocalMetadataSearch,

    /** 本地曲库：导入元数据页（从搜索结果点进来；压栈，与编辑页/搜索页同一条栈） */
    LocalTagImport,

    /** 本地曲库：单曲「搜索歌词」页（从编辑页顶栏的「词」按钮进入） */
    LocalLyricSearch,

    /** 本地曲库：歌词对比导入页（从歌词搜索结果点进来） */
    LocalLyricImport,
}

/**
 * **页面栈**（`UiState.pageStack`）的条目：任何「从某一页 push 进去的子页面」。
 *
 * ## 这个名字为什么不是 Detail
 * 栈里装的一直不止详情：本地曲库的「全部歌曲 / 全部专辑 / 文件夹 / 最近添加」
 * 是普通子页面，从 v1 起就和专辑详情共用同一条栈。叫 `detailStack` 会让所有
 * 层级判断（`OverlayVisibilityFlags`、`BackTarget`、各 sheet 的 `isTop`）读起来像
 * 「详情开着了吗」，而实际语义是「当前有子页面压在根页之上」。
 *
 * ## 为什么必须是真栈
 * 旧实现里专辑 / 歌手 / 歌单各占一对「可见性布尔 + 数据槽」，互跳前必须先关掉其它详情；
 * 改成栈之后，从任意页面跳到另一个页面只是把新条目**压栈**（原页面留在下层，自身的
 * 滚动位置 / 已加载数据 / 多选状态全部保留），返回逐层退回。
 *
 * ## key
 * [key] 是栈内唯一标识，渲染时用 `key(entry.key)` 维持条目身份。用进程内自增序号生成
 * （而不是只取 `data.id`），保证「同类型多层」（专辑A→歌手S→专辑B，甚至同一张专辑
 * 重复入栈）也不会撞键。
 */
sealed interface AppRoute {
    /** 栈内唯一键（含自增序号，同类型多层也不重复）。 */
    val key: String

    /** 条目类型。 */
    val kind: RouteKind
}

/** 进程内自增序号：为每个入栈条目分配唯一 key。 */
private val routeSeq = java.util.concurrent.atomic.AtomicLong(0L)

/** 专辑详情页。 */
class AlbumRoute(val data: AlbumDetailData) : AppRoute {
    override val kind: RouteKind = RouteKind.Album
    override val key: String = "album:${routeSeq.incrementAndGet()}"
}

/** 歌手详情页。 */
class SingerRoute(val data: SingerDetailData) : AppRoute {
    override val kind: RouteKind = RouteKind.Singer
    override val key: String = "singer:${routeSeq.incrementAndGet()}"
}

/**
 * 歌手的「全部单曲」页（overview →「最热门 全部 ›」）。
 *
 * 与本地曲库的 `LocalArtistSongsRoute` 对称：子页是**独立的一层栈**，返回逐层退回。
 * 数据不随路由携带 —— 歌曲在 `SingerDetailCache` 里按歌手缓存，两个页面共享同一份，
 * 所以从 overview 点进来不会重新拉一次。
 */
class SingerSongsRoute(val data: SingerDetailData) : AppRoute {
    override val kind: RouteKind = RouteKind.SingerSongs
    override val key: String = "singerSongs:${routeSeq.incrementAndGet()}"
}

/**
 * 歌手的「全部专辑」页。
 *
 * [section] 是「全部」页要看的那一段：单曲 / EP 或专辑。Apple 由接口自报分组，
 * 其它平台按曲目数推断；为 null 时两段依次全显示。
 */
class SingerAlbumsRoute(
    val data: SingerDetailData,
    val section: AlbumGroup? = null,
) : AppRoute {
    override val kind: RouteKind = RouteKind.SingerAlbums
    override val key: String = "singerAlbums:${routeSeq.incrementAndGet()}"
}

/**
 * 主页区块的「全部」子页数据。
 *
 * **不带网络语义**：条目是主页拉取时就已经拿到的（`HomeFeedState` 里那份），点「全部」只是
 * 换一种排版把这些条目全列出来，不再请求 —— 主页的歌单/专辑上限本来就是「展示上限」，
 * 不是「接口上限」。
 */
data class HomeShelfAllData(
    val title: String,
    val subtitle: String = "",
    val items: List<com.annieplayer.annie_music.kt.data.model.HomeItem>,
)

data class QobuzSeeAllData(
    val title: String,
    val baseline: String = "",
    val kind: com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverKind,
    val items: List<com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverItem>,
)

/** 歌单详情页。 */
class PlaylistRoute(val data: PlaylistDetailData) : AppRoute {
    override val kind: RouteKind = RouteKind.Playlist
    override val key: String = "playlist:${routeSeq.incrementAndGet()}"
}

/** 主页区块的「全部」子页（与专辑 / 歌手 / 歌单详情共用同一条页面栈）。 */
class HomeShelfRoute(val data: HomeShelfAllData) : AppRoute {
    override val kind: RouteKind = RouteKind.HomeShelf
    override val key: String = "homeShelf:${routeSeq.incrementAndGet()}"
}

/** Qobuz 发现页区块的「全部」子页。 */
class QobuzSeeAllRoute(val data: QobuzSeeAllData) : AppRoute {
    override val kind: RouteKind = RouteKind.QobuzSeeAll
    override val key: String = "qobuzSeeAll:${routeSeq.incrementAndGet()}"
}

/** Qobuz「Playlists by Category」某个分类下的歌单列表页。 */
class QobuzTagRoute(
    val slug: String,
    val title: String,
) : AppRoute {
    override val kind: RouteKind = RouteKind.QobuzTag
    override val key: String = "qobuzTag:${routeSeq.incrementAndGet()}"
}

/**
 * 酷狗「按风格听」的流派二级页。
 *
 * 只带 `tagIds` 与标题，曲目由那一页自己现拉（见 `features/home/KugouStyleSheet.kt`）——
 * 主页列 18 个流派时不可能预先取 18 份曲目。
 */
class KugouStyleRoute(
    val tagIds: String,
    val title: String,
) : AppRoute {
    override val kind: RouteKind = RouteKind.KugouStyle
    override val key: String = "kugouStyle:${routeSeq.incrementAndGet()}"
}

class RankDetailRoute(
    val platform: com.annieplayer.annie_music.kt.data.model.MusicPlatform,
    val boardId: String,
    val name: String,
    val cover: String,
    val subtitle: String = "",
    val preload: PlaylistDetailPreload? = null,
) : AppRoute {
    override val kind: RouteKind = RouteKind.RankDetail
    override val key: String = "rankDetail:${routeSeq.incrementAndGet()}"
}

class NewTabRoomRoute(
    val platform: com.annieplayer.annie_music.kt.data.model.MusicPlatform,
    val roomId: String,
    val title: String,
) : AppRoute {
    override val kind: RouteKind = RouteKind.NewTabRoom
    override val key: String = "newTabRoom:${routeSeq.incrementAndGet()}"
}

class AppleRankRoomRoute(
    val sectionId: String,
    val title: String,
) : AppRoute {
    override val kind: RouteKind = RouteKind.AppleRankRoom
    override val key: String = "appleRankRoom:${routeSeq.incrementAndGet()}"
}

/**
 * 主页卡片目标 → 详情栈路由。
 *
 * 只是「形状翻译」，不带任何网络逻辑：详情页（`AlbumDetailSheet` / `SingerDetailSheet` /
 * `PlaylistDetailSheet`）拿到 id 后照自己的平台分发去取曲目，与从搜索结果点进去**完全同一条路**。
 */
fun com.annieplayer.annie_music.kt.data.model.HomeTarget.toRoute(): AppRoute = when (this) {
    is com.annieplayer.annie_music.kt.data.model.HomeTarget.Album -> AlbumRoute(
        AlbumDetailData(
            id = id,
            cover = cover,
            name = name,
            artist = artist,
            platform = platform,
        ),
    )

    is com.annieplayer.annie_music.kt.data.model.HomeTarget.Artist -> SingerRoute(
        SingerDetailData(
            id = id,
            name = name,
            picture = picture,
            platform = platform,
        ),
    )

    is com.annieplayer.annie_music.kt.data.model.HomeTarget.Playlist -> PlaylistRoute(
        PlaylistDetailData(
            id = id,
            // Dart 侧 platform 是字符串，UI 层原样透传；这里用枚举名保证与既有调用一致
            platform = platform.name,
            name = name,
            cover = cover,
        ),
    )

    is com.annieplayer.annie_music.kt.data.model.HomeTarget.Style -> KugouStyleRoute(
        tagIds = tagIds,
        title = title,
    )
}


/**
 * 本地曲库的子页面（**和流媒体详情共用同一条页面栈**）。
 *
 * 为什么不单开一条本地栈：两个栈必然导致返回裁决分裂 —— 播放页 / 队列 / sheet 都按
 * 「这条栈是否非空」判断要不要让位，本地栈不在它们的视野里，就会出现「播放页开着时
 * 返回穿透到下层页面」。并进来之后只有一条栈、一套 `handleBack` 优先级。
 */
class LocalSectionRoute(val section: com.annieplayer.annie_music.kt.data.local.LocalSection) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalSection
    override val key: String = "localSection:${routeSeq.incrementAndGet()}"
}

/**
 * 收藏库的子页面（我喜欢 / 艺人 / 专辑 / 播放列表）。
 *
 * 与本地曲库同样并进**这一条栈**：收藏库里点专辑会 push `AlbumRoute`、点艺人会 push
 * `SingerRoute`，两条栈并存会让返回裁决分裂。
 */
class FavoriteSectionRoute(
    val section: com.annieplayer.annie_music.kt.data.favorites.FavSection,
) : AppRoute {
    override val kind: RouteKind = RouteKind.FavSection
    override val key: String = "favSection:${routeSeq.incrementAndGet()}"
}

/**
 * 自建播放列表详情页。
 *
 * 只带 id、不带歌单内容：歌单是可编辑的（加歌 / 删歌），页面必须每次从
 * `FavoriteViewModel.snapshot` 现取，否则「加完歌返回上一层再进来」看到的是旧列表。
 */
class UserPlaylistRoute(val playlistId: String) : AppRoute {
    override val kind: RouteKind = RouteKind.UserPlaylist
    override val key: String = "userPlaylist:${routeSeq.incrementAndGet()}"
}

class LocalAlbumRoute(val albumKey: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalAlbum
    override val key: String = "localAlbum:${routeSeq.incrementAndGet()}"
}

class LocalArtistRoute(val artistName: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalArtist
    override val key: String = "localArtist:${routeSeq.incrementAndGet()}"
}

/** 歌手详情的「全部歌曲」子页（只存歌手名，列表由页面自己从曲库筛） */
class LocalArtistSongsRoute(val artistName: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalArtistSongs
    override val key: String = "localArtistSongs:${routeSeq.incrementAndGet()}"
}

/** 歌手详情的「全部专辑」子页 */
class LocalArtistAlbumsRoute(val artistName: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalArtistAlbums
    override val key: String = "localArtistAlbums:${routeSeq.incrementAndGet()}"
}

class LocalFolderRoute(val folderName: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalFolder
    override val key: String = "localFolder:${routeSeq.incrementAndGet()}"
}

/**
 * 单曲「编辑元数据」页。
 *
 * 只带 [path]，**不带整份 meta**：内容每次从 `LocalLibraryViewModel.state.tracks` 现取，
 * 保存成功那一瞬间页面就跟着刷新；带快照会出现「保存成功、页面还显示旧值」。
 */
class LocalTrackEditRoute(val path: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalTrackEdit
    override val key: String = "localTrackEdit:${routeSeq.incrementAndGet()}"
}

/**
 * 单曲「搜索元数据」页（从编辑页顶栏的搜索图标**压栈**进入）。
 *
 * 只带 [path]：关键词、原值、候选全部由页面自己从曲库索引与网络现取，
 * 路由里放快照会让「导入完回编辑页再进来」看到过期数据。
 */
class LocalMetadataSearchRoute(val path: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalMetadataSearch
    override val key: String = "localMetadataSearch:${routeSeq.incrementAndGet()}"
}

/**
 * 导入元数据页（搜索结果里点某一条进入）。
 *
 * 直接带上候选 [candidate]：详情页的 `AlbumRoute` 也是这个做法 —— 路由里放整份数据，
 * 页面就不用再请求一次，也不会出现"重新搜索后候选变了"的错位。
 */
class LocalTagImportRoute(val path: String, val candidate: Song) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalTagImport
    override val key: String = "localTagImport:${routeSeq.incrementAndGet()}"
}

/**
 * 单曲「搜索歌词」页（从编辑页顶栏的「词」按钮压栈进入）。
 *
 * 平台范围 = Apple / 网易 / QQ / 酷狗 / 酷我（与「搜索元数据」同一批；
 * KKBOX 要会话、Qobuz 没有公开歌词接口，两边都不参与）。
 */
class LocalLyricSearchRoute(val path: String) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalLyricSearch
    override val key: String = "localLyricSearch:${routeSeq.incrementAndGet()}"
}

/** 歌词对比页的候选来源 */
sealed interface LyricImportSource {
    /** 流媒体平台的一条搜索结果：进页面后还要再拉一次歌词 */
    data class Platform(val song: Song) : LyricImportSource

    /** LRCLIB：歌词随搜索结果一起拿到（`syncedLrc`），进页面不再请求 */
    data class Lrclib(val hit: com.annieplayer.annie_music.kt.data.sources.lyric.LrclibHit) :
        LyricImportSource
}

/** 歌词对比导入页（从歌词搜索结果点进来）：只回填歌词，不动其它字段。 */
class LocalLyricImportRoute(val path: String, val source: LyricImportSource) : AppRoute {
    override val kind: RouteKind = RouteKind.LocalLyricImport
    override val key: String = "localLyricImport:${routeSeq.incrementAndGet()}"
}

/**
 * 元数据导入的一次性回填：搜索页把「选中的字段」合成一份表单交给编辑页。
 *
 * 为什么走 UiState 而不是直接回调：搜索页是**压在编辑页之上**的独立页面，
 * 导入后先 `popRoute` 再回填 —— 中间隔着一次页面销毁，只有宿主（AppShell）同时看得到两边。
 */
data class PendingTagImport(val path: String, val form: TagEditForm)

/**
 * 歌词导入的一次性回填：搜索/对比页把选中的那份歌词交给编辑页。
 *
 * 与 [PendingTagImport] **分开**是刻意的：那个是整份表单（字段 + 封面），
 * 这个只替换歌词文本框，别的字段一个字都不许动。
 */
data class PendingLyricImport(val path: String, val lyric: String)

data class PageTint(val primary: Color, val coverUrl: String?)

/** 多选是从哪个页面打开的 —— 决定动作做完之后清空哪一边的选中。 */
enum class MultiSelectOrigin {
    /** 单曲搜索结果（选中在 `SearchSelectionViewModel`） */
    Search,

    /** 歌手「全部单曲」页（选中在 `SingerDetailCache` 里那份） */
    ArtistSongs,

    /** 歌单详情页：选中在页面自己的 `PlaylistDetailState`，宿主不清（页面自己退出多选） */
    PlaylistDetail,

    /** 专辑详情页：选中在页面自己的 `AlbumDetailState`，宿主不清 */
    AlbumDetail,

    /** 本地曲库页 / 本地歌手全部单曲页：选中在那两页自己的状态里，宿主不清 */
    LocalPage,
}

enum class HomeSection {
    Recommend,
    Rank,
}

/**
 * 「多选操作」抽屉的内容。
 *
 * 抽屉本身由 `AppShell` 渲染（页面内联会被迷你播放条压住），所以页面只把
 * 「已选了哪些歌 + 属于哪一边 + 是谁打开的」交出来，两个动作（下载 / 加入到歌单）
 * 全在宿主执行。收起时按 [origin] 清空对应页面的选中。
 */
data class MultiSelectSheetState(
    val songs: List<Song>,
    val scope: PlaylistScope,
    val origin: MultiSelectOrigin,
    /** origin = [MultiSelectOrigin.ArtistSongs] 时用来定位那份缓存 */
    val artistPlatform: MusicPlatform? = null,
    val artistId: String? = null,
)

/**
 * 「添加到」面板的请求：把这一批歌加进哪份自建歌单。
 *
 * [scope] 决定候选歌单（在线 / 本地分开）；面板本身由宿主渲染，页面只发起请求。
 */
data class PlaylistPickerRequest(
    val songs: List<Song>,
    val scope: PlaylistScope,
)

/** 纯 UI 状态（对齐 Dart `UIState`） */data class UiState(
    val playerOverlayVisible: Boolean = false,
    val queueVisible: Boolean = false,
    val drawerVisible: Boolean = false,
    val pickerVisible: Boolean = false,
    /** ⋮ 歌曲操作面板是否可见 */
    val songActionsVisible: Boolean = false,
    val songActionsSong: Song? = null,
    /**
     * 打开 ⋮ 面板时所在的自建歌单（null = 不在自建歌单里）。
     *
     * 只有它非空时面板才多出「从本歌单移除」。**必须能被清空**，所以
     * [UiViewModel.setSongActionsVisible] 用 `copy()` 写它而不是 `copyWith`。
     */
    val songActionsFromUserPlaylistId: String? = null,
    /** 多选操作抽屉的内容（null = 抽屉关着）。**必须能被清空**，所以关闭时用 `copy()`。 */
    val multiSelectSheet: MultiSelectSheetState? = null,
    /**
     * 「添加到」面板（选歌单）的请求：非空时由 `AppShell` 在底栏之上渲染。
     *
     * 页面**不能内联**渲染它：那会画在页面内部，被迷你播放条 / 底栏压住（穿模）。
     * 与 ⋮ → 加入到歌单、搜索多选 → 加入到歌单走同一颗面板、同一个层级。
     */
    val playlistPicker: PlaylistPickerRequest? = null,
    /** 多歌手选择面板是否可见 */
    val artistPickerVisible: Boolean = false,
    val pickerSong: Song? = null,
    val contactVisible: Boolean = false,
    val updateVisible: Boolean = false,

    /** 「清除缓存」弹窗（列出各类缓存体积，勾选后清理） */
    val cacheClearVisible: Boolean = false,
    /**
     * 待删除的本地歌曲（非 null = 删除确认弹窗可见）。
     *
     * 由 ⋮ 面板的「删除」写入：点删除时**先收起 ⋮ 面板**，再把这个目标挂出来，
     * 所以确认弹窗必须是宿主的浮层（放在 `SongActionsSheet` 内部会被一起收掉）。
     *
     * 刻意**不进 [copyWith]**：那份 `copyWith` 是「null = 保留旧值」的语义（对齐 Dart），
     * 而这个字段恰恰需要能被清空，只能在 [UiViewModel.setLocalDeleteTarget] 里用 `copy()`。
     */
    val localDeleteTarget: Song? = null,
    /**
     * 批量删除的本地歌曲（非 null = 批量删除确认弹窗可见）。同样**不进 [copyWith]**。
     */
    val localDeleteBatch: List<Song>? = null,
    val pageTint: PageTint? = null,
    /**
     * 「搜索元数据」导入回来的一次性回填（非 null 时编辑页会取走并立刻清空）。
     *
     * 与 [localDeleteTarget] 同样**不进 [copyWith]**：它需要能被清空，
     * 而 `copyWith` 的语义是「null = 保留旧值」。
     */
    val pendingTagImport: PendingTagImport? = null,
    /**
     * 「搜索歌词」导入回来的一次性回填（非 null 时编辑页会取走并立刻清空）。
     *
     * 与 [pendingTagImport] 同样**不进 [copyWith]**，理由相同。
     */
    val pendingLyricImport: PendingLyricImport? = null,
    val isDragging: Boolean = false,
    val seekRatio: Double = 0.0,
    /**
     * 页面栈（栈底在前、栈顶在后；栈顶渲染在最上层、返回先处理）。
     *
     * 装的是**所有子页面**：专辑 / 歌手 / 歌单详情，以及本地曲库的列表页与详情页。
     * 当前 tab 的根页不在栈里；栈空 = 停在根页。
     */
    /**
     * 主页的搜索态是否展开。
     *
     * 搜索不是独立页面，而是主页（[AppTab.Home]）的一种形态：展开后搜索框与模式按钮淡入、
     * 主页内容淡出，返回时反向。所以它不进 [pageStack]，是一个模式开关。
     *
     * 但**必须参与返回裁决**（[com.annieplayer.annie_music.kt.core.routing.BackTarget.SEARCH]）——
     * 否则搜索态下按返回会直接落到底部的退出确认。
     */
    val homeSearchExpanded: Boolean = false,
    val homeSection: HomeSection = HomeSection.Recommend,
    val pageStack: List<AppRoute> = emptyList(),
    val downloadQueueVisible: Boolean = false,
    /** 「更多设置」整页是否可见 */
    val moreSettingsVisible: Boolean = false,
    /** 「落雪音源」插件管理页是否可见 */
    val lxPluginManagerVisible: Boolean = false,
    /** 「DSD 输出」页是否可见（更多设置的子页，压在它之上） */
    val dsdOutputVisible: Boolean = false,
    val qobuzTokenVisible: Boolean = false,
    /** 「Apple Music 服务」（本机 wrapper-lite）浮层是否可见 */
    val appleServiceVisible: Boolean = false,
    val kkboxAccountVisible: Boolean = false,
    /** 「本地扫描」（扫描范围 / 规则 / 索引）浮层是否可见 */
    val localScanVisible: Boolean = false,
    /** 「遥控 PC 端安妮播放器」整页浮层是否可见 */
    val lanRemoteVisible: Boolean = false,
    val showLyricsTranslation: Boolean = true,
    /** 逐字歌词展示开关（有逐字数据时生效） */
    val showWordByWord: Boolean = true,
    val currentLineIndex: Int = -1,
) {
    /**
     * 局部更新（对齐 Dart `UIState.copyWith`）。
     *
     * 注意：与 Dart 语义一致，可空字段传 `null` **不会**清空原值（`?: this.xxx` 回退），
     * 因为 Dart 原实现即如此（非幂等 setter）。
     */
    fun copyWith(
        playerOverlayVisible: Boolean? = null,
        queueVisible: Boolean? = null,
        drawerVisible: Boolean? = null,
        pickerVisible: Boolean? = null,
        songActionsVisible: Boolean? = null,
    songActionsSong: Song? = null,
        songActionsFromUserPlaylistId: String? = null,
        artistPickerVisible: Boolean? = null,
        pickerSong: Song? = null,
        contactVisible: Boolean? = null,
        updateVisible: Boolean? = null,
    cacheClearVisible: Boolean? = null,
        isDragging: Boolean? = null,
        seekRatio: Double? = null,
        homeSearchExpanded: Boolean? = null,
        homeSection: HomeSection? = null,
        pageStack: List<AppRoute>? = null,
        downloadQueueVisible: Boolean? = null,
        moreSettingsVisible: Boolean? = null,
        lxPluginManagerVisible: Boolean? = null,
        dsdOutputVisible: Boolean? = null,
        qobuzTokenVisible: Boolean? = null,
        appleServiceVisible: Boolean? = null,
        kkboxAccountVisible: Boolean? = null,
        localScanVisible: Boolean? = null,
        lanRemoteVisible: Boolean? = null,
        showLyricsTranslation: Boolean? = null,
        showWordByWord: Boolean? = null,
        currentLineIndex: Int? = null,
    ): UiState = UiState(
        playerOverlayVisible = playerOverlayVisible ?: this.playerOverlayVisible,
        queueVisible = queueVisible ?: this.queueVisible,
        drawerVisible = drawerVisible ?: this.drawerVisible,
        pickerVisible = pickerVisible ?: this.pickerVisible,
        songActionsVisible = songActionsVisible ?: this.songActionsVisible,
        songActionsSong = songActionsSong ?: this.songActionsSong,
        songActionsFromUserPlaylistId = songActionsFromUserPlaylistId
            ?: this.songActionsFromUserPlaylistId,
        artistPickerVisible = artistPickerVisible ?: this.artistPickerVisible,
        pickerSong = pickerSong ?: this.pickerSong,
        contactVisible = contactVisible ?: this.contactVisible,
        updateVisible = updateVisible ?: this.updateVisible,
        cacheClearVisible = cacheClearVisible ?: this.cacheClearVisible,
        isDragging = isDragging ?: this.isDragging,
        seekRatio = seekRatio ?: this.seekRatio,
        homeSearchExpanded = homeSearchExpanded ?: this.homeSearchExpanded,
        homeSection = homeSection ?: this.homeSection,
        pageStack = pageStack ?: this.pageStack,
        downloadQueueVisible = downloadQueueVisible ?: this.downloadQueueVisible,
        moreSettingsVisible = moreSettingsVisible ?: this.moreSettingsVisible,
        lxPluginManagerVisible = lxPluginManagerVisible ?: this.lxPluginManagerVisible,
        dsdOutputVisible = dsdOutputVisible ?: this.dsdOutputVisible,
        qobuzTokenVisible = qobuzTokenVisible ?: this.qobuzTokenVisible,
        appleServiceVisible = appleServiceVisible ?: this.appleServiceVisible,
        kkboxAccountVisible = kkboxAccountVisible ?: this.kkboxAccountVisible,
        localScanVisible = localScanVisible ?: this.localScanVisible,
        lanRemoteVisible = lanRemoteVisible ?: this.lanRemoteVisible,
        showLyricsTranslation = showLyricsTranslation ?: this.showLyricsTranslation,
        showWordByWord = showWordByWord ?: this.showWordByWord,
        currentLineIndex = currentLineIndex ?: this.currentLineIndex,
        // 下面三个字段**故意不在参数列表里**（`copyWith` 的 null 回退语义清不掉它们，
        // 只能靠各自的专用 setter）。但构造时**必须显式带过去** —— 少了这几行，
        // 任何一次 `copyWith(...)`（例如 popRoute 改 pageStack）都会把它们悄悄重建成 null：
        // 表现就是「导入的元数据/歌词回到编辑页是空的」「删除确认弹窗莫名消失」。
        localDeleteTarget = this.localDeleteTarget,
        pendingTagImport = this.pendingTagImport,
        pendingLyricImport = this.pendingLyricImport,
    )
}

/** UI 状态 ViewModel（对齐 Dart `UINotifier`，Riverpod Notifier -> Kotlin ViewModel + StateFlow） */
class UiViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())

    /** 只读状态流（UI 层 collect 渲染） */
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setPlayerOverlayVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(playerOverlayVisible = visible)
    }

    fun setQueueVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(queueVisible = visible)
    }

    fun setDrawerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(drawerVisible = visible)
    }

    /** 播放选择器（pickerVisible + pickerSong 一并设置；song 为 null 时不覆盖旧值，对齐 Dart） */
    fun setPicker(visible: Boolean, song: Song? = null) {
        _state.value = _state.value.copyWith(pickerVisible = visible, pickerSong = song)
    }

    /**
     * ⋮ 歌曲操作面板（[song] 为本次要操作的歌；任意页面行尾都能呼出）。
     *
     * [fromUserPlaylistId] 只有「从自建歌单详情页」打开时才传 —— 面板据此多出
     * 「从本歌单移除」。这里**用 `copy()` 而不是 `copyWith()`**：后者对 null 走
     * `?: this.x` 回退，关面板时清不掉，下次在别的页面打开还会看到那一行。
     */
    fun setSongActionsVisible(
        visible: Boolean,
        song: Song? = null,
        fromUserPlaylistId: String? = null,
    ) {
        val cur = _state.value
        _state.value = cur.copy(
            songActionsVisible = visible,
            songActionsSong = song ?: cur.songActionsSong,
            songActionsFromUserPlaylistId = fromUserPlaylistId,
        )
    }

    /**
     * 打开「多选操作」抽屉。
     *
     * 页面只交出快照（已选歌曲 + 属于哪一边 + 是谁打开的），两个动作由宿主执行。
     * 收起时按 `origin` 清空对应页面的选中 —— 那一步在 `AppShell` 里做，这里只管开关。
     */
    fun openMultiSelectSheet(state: MultiSelectSheetState) {
        _state.value = _state.value.copy(multiSelectSheet = state)
    }

    /** 关抽屉。**必须 `copy()`**：`copyWith` 对 null 走 `?: this.x`，关不掉。 */
    fun closeMultiSelectSheet() {
        _state.value = _state.value.copy(multiSelectSheet = null)
    }

    /** 打开「添加到」面板（页面 → 宿主渲染，见 [PlaylistPickerRequest]）。 */
    fun openPlaylistPicker(request: PlaylistPickerRequest) {
        _state.value = _state.value.copy(playlistPicker = request)
    }

    /** 关「添加到」面板。**必须 `copy()`**。 */
    fun closePlaylistPicker() {
        _state.value = _state.value.copy(playlistPicker = null)
    }

    /**
     * 补全**当前**面板在操作的那首歌（Apple 的多歌手信息是晚一步拿到的）。
     *
     * 只在「面板还开着、而且仍然是同一首」时替换：晚到的结果不能贴到已经关掉的、
     * 或者用户已经换成别的歌的面板上。
     */
    fun updateSongActionsSong(expectId: String, song: Song) {
        val cur = _state.value
        if (!cur.songActionsVisible) return
        if (cur.songActionsSong?.id != expectId) return
        _state.value = cur.copyWith(songActionsSong = song)
    }

    /** 多歌手选择面板 */
    fun setArtistPickerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(artistPickerVisible = visible)
    }

    fun setContactVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(contactVisible = visible)
    }

    fun setUpdateVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(updateVisible = visible)
    }

    fun setCacheClearVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(cacheClearVisible = visible)
    }

    /**
     * 本地歌曲删除确认弹窗的目标（null = 关闭弹窗）。
     *
     * 这里必须用 `copy()` 而不是 `copyWith()`：后者对 null 走 `?: this.x` 回退，
     * 关不掉弹窗。
     */
    fun setLocalDeleteTarget(song: Song?) {
        if (_state.value.localDeleteTarget == song) return
        _state.value = _state.value.copy(localDeleteTarget = song)
    }

    fun setLocalDeleteBatch(songs: List<Song>?) {
        if (_state.value.localDeleteBatch == songs) return
        _state.value = _state.value.copy(localDeleteBatch = songs)
    }

    fun setPageTint(tint: PageTint?) {
        if (_state.value.pageTint == tint) return
        _state.value = _state.value.copy(pageTint = tint)
    }

    /**
     * 搜索元数据页导入完成后，把表单交给编辑页。
     *
     * 同样用 `copy()`：编辑页取走时要把这个槽清成 null（`copyWith` 清不掉）。
     */
    fun setPendingTagImport(path: String, form: TagEditForm) {
        _state.value = _state.value.copy(pendingTagImport = PendingTagImport(path, form))
    }

    fun clearPendingTagImport() {
        if (_state.value.pendingTagImport == null) return
        _state.value = _state.value.copy(pendingTagImport = null)
    }

    /** 「搜索歌词」导入回来的一次性回填（编辑页取走后会调 [clearPendingLyricImport]） */
    fun setPendingLyricImport(path: String, lyric: String) {
        _state.value = _state.value.copy(pendingLyricImport = PendingLyricImport(path, lyric))
    }

    fun clearPendingLyricImport() {
        if (_state.value.pendingLyricImport == null) return
        _state.value = _state.value.copy(pendingLyricImport = null)
    }

    /** 拖动进度条（seekRatio 为 null 时归 0，对齐 Dart） */
    fun setDragging(dragging: Boolean, seekRatio: Double? = null) {
        _state.value = _state.value.copyWith(isDragging = dragging, seekRatio = seekRatio ?: 0.0)
    }

    fun setDownloadQueueVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(downloadQueueVisible = visible)
    }

    fun setMoreSettingsVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(moreSettingsVisible = visible)
    }

    fun setLxPluginManagerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(lxPluginManagerVisible = visible)
    }

    fun setDsdOutputVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(dsdOutputVisible = visible)
    }

    fun setQobuzTokenVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(qobuzTokenVisible = visible)
    }

    fun setAppleServiceVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(appleServiceVisible = visible)
    }

    fun setKkboxAccountVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(kkboxAccountVisible = visible)
    }

    fun setLocalScanVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(localScanVisible = visible)
    }

    fun setLanRemoteVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(lanRemoteVisible = visible)
    }

    /** 展开 / 收起主页的搜索态（搜索是主页的形态，不是页面）。 */
    fun setHomeSearchExpanded(expanded: Boolean) {
        if (_state.value.homeSearchExpanded == expanded) return
        _state.value = _state.value.copyWith(homeSearchExpanded = expanded)
    }

    fun setHomeSection(section: HomeSection) {
        if (_state.value.homeSection == section) return
        _state.value = _state.value.copyWith(homeSection = section)
    }

    /** 压入一个子页面：栈顶渲染在最上层，返回先处理。 */
    fun pushRoute(route: AppRoute) {
        // 用 `copy()` 而不是 `copyWith()`：这里只改栈，别的字段一个都不该被"重建"
        _state.value = _state.value.copy(pageStack = _state.value.pageStack + route)
    }

    /** 弹出栈顶页面（返回逐层退回；空栈时为 no-op）。 */
    fun popRoute() {
        val stack = _state.value.pageStack
        if (stack.isEmpty()) return
        _state.value = _state.value.copy(pageStack = stack.dropLast(1))
    }

    /** 清空页面栈（回到当前 tab 的根页）。 */
    fun clearRoutes() {
        if (_state.value.pageStack.isEmpty()) return
        _state.value = _state.value.copy(pageStack = emptyList())
    }

    fun setShowLyricsTranslation(visible: Boolean) {
        _state.value = _state.value.copyWith(showLyricsTranslation = visible)
    }

    fun setShowWordByWord(visible: Boolean) {
        _state.value = _state.value.copyWith(showWordByWord = visible)
    }

    fun setCurrentLineIndex(index: Int) {
        _state.value = _state.value.copyWith(currentLineIndex = index)
    }
}

/**
 * 由 [UiState] 生成返回裁决用的可见性快照。
 *
 * 为什么需要这个函数：`OverlayVisibilityFlags` 的字段必须和 [UiState] 的布尔一一对应，
 * 而这份快照有 4 个调用方（宿主 `AppShell` + 专辑 / 歌单 / 歌手三个全屏详情页）。
 * 以前它们各自手写一遍字段列表 —— 每加一个浮层字段就要改 4 处，漏一处就是
 * 「某个浮层打开时返回键判错」。
 *
 * 现在只有这一份映射。调用方若有本页特有的标记（例如详情页自己的批量音质面板），
 * 通过 [extraQualityPicker] 传进来，或用 `copy(...)` 覆盖结果。
 */
fun UiState.toOverlayFlags(
    extraQualityPicker: Boolean = false,
    /** `TranslatePicker.visible` —— 这个面板的可见性不在 [UiState] 里，由宿主传。 */
    translatePicker: Boolean = false,
    /** `SearchScreenState.sourcePickerVisible` —— 同上。 */
    sourcePicker: Boolean = false,
    /**
     * 搜索页是否处于多选态（`selectedSongs` 非空）—— 同上。
     *
     * 它是搜索结果的一种**形态**，不是浮层：返回时先退它、再收搜索态（见 `BACK_RULES`）。
     */
    searchSelection: Boolean = false,
): OverlayVisibilityFlags = OverlayVisibilityFlags(
    songActions = songActionsVisible,
    multiSelect = multiSelectSheet != null,
    playlistPicker = playlistPicker != null,
    artistPicker = artistPickerVisible,
    qualityPicker = pickerVisible || extraQualityPicker,
    queue = queueVisible,
    playerOverlay = playerOverlayVisible,
    translatePicker = translatePicker,
    cacheClear = cacheClearVisible,
    localDelete = localDeleteTarget != null || localDeleteBatch != null,
    contact = contactVisible,
    update = updateVisible,
    sourcePicker = sourcePicker,
    lxPluginManager = lxPluginManagerVisible,
    dsdOutput = dsdOutputVisible,
    moreSettings = moreSettingsVisible,
    kkboxAccount = kkboxAccountVisible,
    qobuzToken = qobuzTokenVisible,
    appleService = appleServiceVisible,
    localScan = localScanVisible,
    lanRemote = lanRemoteVisible,
    page = pageStack.isNotEmpty(),
    searchSelection = searchSelection,
    searchExpanded = homeSearchExpanded,
    downloadQueue = downloadQueueVisible,
    menuDrawer = drawerVisible,
)
