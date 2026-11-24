package com.annieplayer.annie_music.kt.features.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.MetadataPlatformResult
import com.annieplayer.annie_music.kt.data.MetadataSearchPlatforms
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.recommendedMetadata
import com.annieplayer.annie_music.kt.data.searchSongsByPlatform
import com.annieplayer.annie_music.kt.data.sources.common.RankQuery
import com.annieplayer.annie_music.kt.data.sources.kkbox.kkboxSidProvider
import com.annieplayer.annie_music.kt.data.sources.lyric.LrclibHit
import com.annieplayer.annie_music.kt.data.sources.lyric.LrclibSource
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 同一个搜索页的两种用途。
 *
 * [Lyric] 额外带上 **LRCLIB**（只提供歌词的公共库，直连、无需 key），
 * 并把标题与「点结果之后压哪一页」改掉；平台搜索那一套完全共用。
 */
enum class MetadataSearchMode { Metadata, Lyric }

/**
 * 搜索页的 Tab。
 *
 * [Recommended] + 五家是「一进来就搜」的集合，也是推荐的来源。
 * [Qobuz] / [Kkbox] 需要凭据，**第一次切到才搜**。
 * [Lrclib] 只在歌词模式出现，直连可用，所以它进推荐。
 */
enum class MetadataTab(
    val label: String,
    val platform: MusicPlatform?,
    /** 这一栏的结果来自 LRCLIB（不是流媒体平台） */
    val lrclib: Boolean = false,
) {
    Recommended("推荐结果", null),
    Apple("Apple Music", MusicPlatform.apple),
    Netease("网易云", MusicPlatform.netease),
    Qq("QQ音乐", MusicPlatform.qq),
    Kugou("酷狗", MusicPlatform.kugou),
    Kuwo("酷我", MusicPlatform.kuwo),
    Qobuz("Qobuz", MusicPlatform.qobuz),
    Kkbox("KKBOX", MusicPlatform.kkbox),
    Lrclib("LRCLIB", null, lrclib = true),
}

/** 搜索页里的一行：普通平台结果，或 LRCLIB 的歌词候选（后者只在歌词模式出现） */
sealed interface SearchRow {
    val key: String

    data class Platform(val song: Song) : SearchRow {
        override val key: String get() = "p:${song.platform.name}:${song.id}"
    }

    data class Lyric(val hit: LrclibHit) : SearchRow {
        override val key: String get() = "l:${hit.id}"
    }
}

/**
 * 一个平台这次的搜索状态。
 *
 * [loaded] 与 [error] 是**两件事**：
 * - 请求失败 → `loaded = true, error = 原因`（这一轮不用再试）；
 * - 缺凭据（没登录 / 没 token）→ `loaded = false, error = 提示`（用户去补完，切回来还能重试）。
 */
data class PlatformSearch(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** LRCLIB 的状态（与平台分开：它不提供可播放的歌曲，只有歌词） */
data class LrclibSearch(
    val hits: List<LrclibHit> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 搜索页的状态。
 *
 * ## 唯一的模型：`platforms` 是每个平台的状态表
 *
 * Tab 列表、推荐结果、错误文案**全部从 [platforms] 派生**，不再有"另一份结果列表"。
 * 这解决了一类真实 bug：以前「推荐结果」是一份单独算出来的缓存，而「渠道 Tab」直接读平台结果，
 * 两条路径不同步 —— 点搜索时缓存没更新，推荐空着、渠道却有。
 *
 * ## 「点搜索」= 对**一组**平台发请求
 *
 * 目标平台由 [searchTargets] 决定：五家 + **已经搜过的懒加载平台** + **当前所在 Tab 的懒加载平台**。
 * 后两条是关键：以前搜索只打五家，而 `results` 被整体清空 —— 于是正看着 Qobuz/KKBOX 的人
 * 点搜索会看到它变空（因为没人重新请求），只有切走再切回来（`selectTab` 触发）才有数据。
 */
@Stable
class MetadataSearchState(
    private val scope: CoroutineScope,
    /** 相关度基准：本地文件当前的标题/艺术家/专辑 */
    private val rankQuery: RankQuery,
    initialKeyword: String,
    /** 歌词模式：额外带上 LRCLIB */
    private val lyricMode: Boolean = false,
) {
    var keyword by mutableStateOf(initialKeyword)
    var tab by mutableStateOf(MetadataTab.Recommended)
        private set

    /** 是否搜过（区分"还没搜"和"搜了没结果"） */
    var searched by mutableStateOf(false)
        private set

    /** 每个平台这次搜索的状态（**唯一事实来源**） */
    var platforms by mutableStateOf<Map<MusicPlatform, PlatformSearch>>(emptyMap())
        private set

    var lrclib by mutableStateOf(LrclibSearch())
        private set

    /** 推荐结果：平台结果（AM 最匹配置顶 + 相关度）+ LRCLIB 命中（排在后面） */
    var recommended by mutableStateOf<List<SearchRow>>(emptyList())
        private set

    /** 每次 [search] 自增：旧轮次晚到的结果直接丢弃 */
    private var seq = 0

    /** 当前模式可见的 Tab（LRCLIB 只属于歌词模式） */
    val tabs: List<MetadataTab>
        get() = if (lyricMode) {
            MetadataTab.entries
        } else {
            MetadataTab.entries.filterNot { it.lrclib }
        }

    /** 还有请求在飞（顶栏按钮文案用：任何目标平台或 LRCLIB 没回来） */
    val busy: Boolean
        get() = platforms.values.any { it.loading } || lrclib.loading

    /** 当前 Tab 是否在加载（推荐 Tab = 五家里还有没回来的） */
    val currentLoading: Boolean
        get() = when {
            tab.lrclib -> lrclib.loading
            tab == MetadataTab.Recommended ->
                MetadataSearchPlatforms.any { platforms[it]?.loading == true }
            else -> platforms[tab.platform]?.loading == true
        }

    /** 当前 Tab 的失败原因 */
    val currentError: String?
        get() = when {
            tab.lrclib -> lrclib.error
            tab == MetadataTab.Recommended -> recommendedFallbackError
            else -> platforms[tab.platform]?.error
        }

    /**
     * 推荐为空时的原因：把五家里第一条非空错误顶上来。
     *
     * 否则五家全失败时推荐 Tab 只显示「没有匹配的结果」，看着像"这首歌搜不到"，
     * 其实是一次都没成功。
     */
    val recommendedFallbackError: String?
        get() = if (recommended.isNotEmpty()) {
            null
        } else {
            MetadataSearchPlatforms.firstNotNullOfOrNull { p ->
                platforms[p]?.error?.takeIf { it.isNotBlank() }
            }
        }

    fun rowsOf(t: MetadataTab): List<SearchRow> = when {
        t == MetadataTab.Recommended -> recommended
        t.lrclib -> lrclib.hits.map { SearchRow.Lyric(it) }
        else -> platforms[t.platform]?.songs.orEmpty().map { SearchRow.Platform(it) }
    }

    /** 切 Tab；懒加载平台第一次切到就在这里发请求 */
    fun selectTab(t: MetadataTab) {
        tab = t
        t.platform?.let { ensurePlatformLoaded(it) }
    }

    /**
     * 「搜索」按钮：重置所有平台状态，然后对 [searchTargets] 逐个并发请求。
     */
    fun search() {
        val q = keyword.trim()
        if (q.isEmpty()) return
        val mySeq = ++seq
        searched = true
        // 必须在清空之前算：目标是「五家 + 已搜过的懒加载 + 当前 Tab 的懒加载」
        val targets = searchTargets(tab, loadedLazyPlatforms())
        platforms = emptyMap()
        recommended = emptyList()
        lrclib = LrclibSearch(loading = lyricMode)
        targets.forEach { launchPlatformSearch(it, q, mySeq) }
        if (lyricMode) launchLrclib(mySeq)
    }

    /** 这一轮里已经真正请求过的懒加载平台（下一轮搜索要一起重搜） */
    private fun loadedLazyPlatforms(): Set<MusicPlatform> =
        platforms.filterKeys { it !in MetadataSearchPlatforms }
            .filterValues { it.loaded }
            .keys

    private fun launchPlatformSearch(platform: MusicPlatform, query: String, expectedSeq: Int) {
        platforms = platforms + (platform to PlatformSearch(loading = true))
        scope.launch {
            val result = fetchOne(platform, query)
            if (expectedSeq != seq) return@launch
            applyPlatformResult(platform, result)
        }
    }

    /** 落一个平台结果并刷新推荐（internal：单测直接喂结果） */
    internal fun applyPlatformResult(platform: MusicPlatform, result: MetadataPlatformResult) {
        platforms = platforms + (
            platform to PlatformSearch(
                songs = result.songs,
                error = result.error,
                loaded = true,
            )
            )
        refreshRecommended()
    }

    /** 懒加载平台：没凭据就不发请求，只落一条提示（`loaded = false`，补完凭据还能重试） */
    private fun ensurePlatformLoaded(platform: MusicPlatform) {
        if (platform in MetadataSearchPlatforms) return
        val current = platforms[platform]
        if (current?.loaded == true || current?.loading == true) return
        val q = keyword.trim()
        if (q.isEmpty() || !searched) return
        val missing = missingCredential(platform)
        if (missing != null) {
            platforms = platforms + (platform to PlatformSearch(error = missing))
            return
        }
        launchPlatformSearch(platform, q, seq)
    }

    private fun launchLrclib(expectedSeq: Int) {
        scope.launch {
            val result = runCatching { LrclibSource.search(rankQuery.title, rankQuery.artist) }
            if (expectedSeq != seq) return@launch
            lrclib = result.fold(
                onSuccess = { LrclibSearch(hits = it) },
                onFailure = { LrclibSearch(error = it.message ?: "LRCLIB 请求失败") },
            )
            refreshRecommended()
        }
    }

    /** 推荐 = 已回来的平台结果排序 + LRCLIB 命中（按匹配度，排在后面） */
    private fun refreshRecommended() {
        val ready = MetadataSearchPlatforms.mapNotNull { platform ->
            val s = platforms[platform] ?: return@mapNotNull null
            if (!s.loaded) return@mapNotNull null
            MetadataPlatformResult(platform, s.songs, s.error)
        }
        recommended = recommendedMetadata(rankQuery, ready).map { SearchRow.Platform(it) } +
            lrclib.hits.sortedByDescending { hitScore(it) }.map { SearchRow.Lyric(it) }
    }

    private fun missingCredential(platform: MusicPlatform): String? = when (platform) {
        MusicPlatform.kkbox ->
            if (kkboxSidProvider() == null) "KKBOX 未登录（设置 → KKBOX 账号）" else null
        MusicPlatform.qobuz ->
            if (qobuzTokenProvider().orEmpty().isEmpty()) "Qobuz 未登录（设置 → Qobuz Token）" else null
        else -> null
    }

    /** LRCLIB 命中的匹配度：标题完全相同 +2、艺术家串包含 +1 */
    private fun hitScore(hit: LrclibHit): Int {
        val title = rankQuery.title.trim().lowercase()
        val artist = rankQuery.artist.trim().lowercase()
        var score = 0
        if (title.isNotEmpty() && hit.title.trim().lowercase() == title) score += 2
        if (artist.isNotEmpty() && hit.artist.trim().lowercase().contains(artist)) score += 1
        return score
    }

    private suspend fun fetchOne(
        platform: MusicPlatform,
        query: String,
    ): MetadataPlatformResult = try {
        MetadataPlatformResult(
            platform = platform,
            songs = searchSongsByPlatform(
                platform = platform,
                query = query,
                page = 1,
                pageSize = MetadataSearchPageSize,
                appleLimit = MetadataSearchPageSize,
            ),
        )
    } catch (e: CancellationException) {
        // 本协程真的被取消才继续抛；否则（某平台内部取消了自己的请求）只算这一家失败，
        // 不能因为一家把另外几家的结果一起丢掉。
        currentCoroutineContext().ensureActive()
        MetadataPlatformResult(platform, emptyList(), e.message ?: "请求被取消")
    } catch (e: Throwable) {
        MetadataPlatformResult(platform, emptyList(), e.message ?: "搜索失败")
    }
}

/**
 * 「点搜索」要打哪些平台。
 *
 * = 五家（进推荐）+ 这一轮已经搜过的懒加载平台（否则它们的数据会被清空且不重搜）
 * + **当前所在 Tab 的懒加载平台**（否则你正看着 Qobuz 时点搜索，它反而变空，
 * 必须切走再切回来才有 —— 这就是之前那个 bug）。
 */
internal fun searchTargets(
    current: MetadataTab,
    alreadyLoadedLazy: Set<MusicPlatform>,
): List<MusicPlatform> {
    val lazy = buildSet {
        addAll(alreadyLoadedLazy.filterNot { it in MetadataSearchPlatforms })
        current.platform?.takeIf { it !in MetadataSearchPlatforms }?.let { add(it) }
    }
    return MetadataSearchPlatforms + lazy.sortedBy { it.ordinal }
}

/** 每个平台取多少条：够翻两屏，又不至于让首屏等更久 */
private const val MetadataSearchPageSize = 30
