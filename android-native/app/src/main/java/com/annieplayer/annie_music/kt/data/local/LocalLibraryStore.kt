package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.util.Log
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import com.annieplayer.annie_music.kt.data.local.tag.WritePolicy
import com.annieplayer.annie_music.kt.data.local.tag.WriteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地曲库的磁盘缓存（**唯一**碰文件系统的地方，除扫描与 ffmpeg 之外）。
 *
 * 三样东西，全部在 `filesDir/local_library/` 下：
 * - `index.json`：整份 [LocalTrackMeta] 列表（含 `mtime` / `size`）。下次启动先读它 →
 *   界面秒开；再扫目录比对 `mtime + size`，只有变过/新增的文件才重跑 ffprobe；
 * - `covers/<hash>.jpg`：ffmpeg 抽出来的内嵌封面（512px），一首一份；
 * - 歌词不落缓存：直接读同名 `.lrc` 或文件内嵌 `lyrics` tag（播放时才读，且量小）。
 *
 * 缓存损坏（写到一半被杀 / 手改坏了）一律当空处理，不抛异常 —— 曲库重新扫一遍就好，
 * 不该因为一个 json 打不开就让整页报错。
 */
internal object LocalLibraryStore {

    private const val TAG = "LocalLibraryStore"

    private const val DIR_NAME = "local_library"
    private const val INDEX_NAME = "index.json"
    private const val COVERS_NAME = "covers"

    /**
     * 缓存结构版本。
     *
     * 换读取引擎（ffprobe → MediaMetadataRetriever → TagLib）或字段语义变化时**必须 +1**：
     * 老缓存会整体作废，下次进入曲库自动全量重扫，避免拿旧引擎的残缺结果当有效缓存。
     *
     * v2：改用 TagLib 主路径（位深多数为 0，靠 enrich 补）
     * v3：加 `LocalHeaderProbe` 头解析，位深/采样率/声道在扫描阶段就齐了（v2 缓存里位深是 0，必须作废）
     * v4：修正 TagLib 时长单位（ms → 秒）；v3 缓存里的 duration 大了 1000 倍，必须重扫
     * v5：`.m4a` 改用头解析的 sample entry 判定真实编码（ALAC / AAC），v4 里全是 `m4a`
     * v6：DSD 改由头解析强制接管（真实 1bit 率 + `dsd64/128…` 档位名）；v5 里存的是 ffprobe 的
     *     解码器中间格式（`dsd_lsbf_planar` / 8bit / 源率÷8），必须重扫
     * v7：修 DSF 的 `fmt ` 内容起点（少算 4 字节 → 采样率读成了声道数，v6 里是 1Hz）
     * v8：`.m4a` 的 AAC 采样率改从 `esds → AudioSpecificConfig` 的 samplingFrequencyIndex 读。
     *     `mp4a` sample entry 的 samplerate 是 16.16 定点，整数部分只有 16 位（≤65535），
     *     96k/192k 根本装不下 —— 容器只能写 core rate（48k），所以 v7 里 96k AAC 全被记成 48000。
     * v9：`LocalHeaderProbe.fill` 的位深/采样率/声道改成**头解析优先**（原来只补空，
     *     TagLib 读 sample entry 得到的 48000/16 一直压着真值）。v8 里 96k AAC 仍是 48000、
     *     24bit ALAC 仍是 16bit，所以 v9 必须再重扫一次。
     * v10：ALAC magic cookie 跳过 full-box 的 4 字节 version/flags。
     *     v9 里 ALAC 一律读不到 cookie（bitDepth 读成 0 被判非法），退回 sample entry ——
     *     sample entry 写 24 的碰巧对，写 16 的 24bit ALAC 全被记成 16bit。
     * v11：字段不再兜底。头解析读不出就存 0（未知），不再回退 TagLib 的值；
     *     另外有损编码（AAC/MP3/AC3…）的位深强制 0 —— 它们没有位深概念，
     *     TagLib 给的 16/32 是容器默认值，v10 里会出现"AC3 32 Bit"这种假信息。
     * v12：头解析补齐 mp3 / ogg / wv / tta / ac3 / wma 六种容器，覆盖率达到 54/54。
     *     ffprobe 从此只在头解析不认识的罕见格式上触发，扫描不再被 100ms/首 拖住。
     * v13：字段集与下载打标（`FfmpegTagger`）对齐 —— 新增 `composer` / `isrc` / `version` /
     *     `copyright`。v12 缓存里这四项全空（老引擎没读），不 +1 会让「自己打标写进去的
     *     作曲 / ISRC / 版权」在详情页永远显示不出来。
     * v14：码率改用**文件实际平均码率**（`字节数×8÷时长`）修正「未压缩速率冒充码率」。
     *     v13 里 ALAC 24/48 全被记成 2304 kbps（= 48000×24×2），实际只有约 1937 kbps。
     *     详见 `LocalBitrate.kt`。
     * v15：封面缓存文件名带上音频文件的 `mtime + size`（原来是纯路径哈希）。
     *     v14 里「改了内嵌封面 / 用别的软件重打标签」之后，同名旧 jpg 会被当成有效封面
     *     一直复用（`coverPath` 只记路径），而且 Coil 的缓存键就是那个路径 ——
     *     两个问题一起导致「重扫了封面还是旧的」。名字里带戳后，旧图自然失效。
     * v16：DSD（dff / dsf）的文本字段与歌词改由 `AnnieTag` 读（TagLib 不解析它们的 ID3 块）。
     *     v15 里这些记录的标题全是文件名回落值、歌词是空，写入器改完也回流不了。
     */
    private const val INDEX_VERSION = 16

    @Serializable
    private data class IndexFile(
        val version: Int = INDEX_VERSION,
        val tracks: List<LocalTrackMeta> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 同目录外挂封面的常见文件名（freedb / MusicBrainz / 各类抓轨工具的共识命名） */
    private val SIDECAR_COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "front.jpeg", "front.png",
        "album.jpg", "album.png",
        "AlbumArt.jpg", "AlbumArtSmall.jpg",
    )

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        LocalScanIndex.init(context)
        LocalScanExclusions.init(context)
        LocalScanSettings.init(context)
    }

    fun rootDir(): File = File(appContext?.filesDir ?: File("/data/local/tmp"), DIR_NAME)

    val coversDir: File get() = File(rootDir(), COVERS_NAME)

    private val indexFile: File get() = File(rootDir(), INDEX_NAME)

    /**
     * 某首歌的封面缓存文件（不管存不存在）。
     *
     * 文件名里带**音频文件的 `mtime + size`**（见 [coverFileName]），这是封面能失效的唯一依据：
     * 内嵌图 / 标签 / 整个文件被别的软件重写过，戳一定变 → 新名字 = 新图。
     * 同时 Coil 的缓存键就是那个 URI 字符串，名字变了它也不会再拿内存里的旧 Bitmap。
     *
     * 老格式（纯路径哈希）的文件在 [pruneCachedCovers] 里会被当孤儿清掉。
     */
    fun coverFileFor(path: String, mtime: Long, size: Long): File =
        File(coversDir, coverFileName(path, mtime, size))

    /** 按记录里的戳取封面文件（`mtime` / `size` 就是扫描时 stat 到的那两个值） */
    fun coverFileFor(t: LocalTrackMeta): File = coverFileFor(t.path, t.mtime, t.size)

    /**
     * 删掉某首歌**所有版本**的封面缓存（删歌时用）。
     *
     * 只按前缀删，不能按记录上的戳拼名字：改过标签又抽过封面就会在盘上留两张，
     * 删歌只删得掉恰好那一张，剩下的变成永远没人认领的孤儿。
     */
    fun deleteCoversFor(path: String) {
        val dir = coversDir
        if (!dir.isDirectory) return
        val prefix = coverNamePrefix(path) + "_"
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix)) runCatching { f.delete() }
        }
        invalidateCovers()
    }

    @Volatile
    private var coverNames: Set<String>? = null

    fun coversSnapshot(): Set<String> {
        coverNames?.let { return it }
        val dir = coversDir
        val names = if (dir.isDirectory) {
            dir.list()?.toHashSet() ?: HashSet()
        } else {
            HashSet()
        }
        coverNames = names
        return names
    }

    fun invalidateCovers() {
        coverNames = null
    }

    // ── index.json 读写 ──

    fun loadIndex(): Map<String, LocalTrackMeta> {
        val f = indexFile
        if (!f.isFile) return emptyMap()
        return try {
            val parsed = json.decodeFromString(IndexFile.serializer(), f.readText())
            // 版本不符 = 换过读取引擎 / 字段语义变了 → 当没有缓存，触发全量重扫
            if (parsed.version != INDEX_VERSION) return emptyMap()
            // 自愈：`covers/` 里明明有这张图，但记录里 `coverPath` 是 null
            // （历史版本的「按需抽封面」只更新了内存、没写回索引）→ 直接把路径补回。
            parsed.tracks.associateBy { it.path }.mapValues { (_, t) ->
                if (t.coverPath == null) {
                    val cover = coverFileFor(t)
                    if (cover.name in coversSnapshot()) t.copy(coverPath = cover.absolutePath) else t
                } else {
                    t
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[cache] read failed: ${t.message}")
            emptyMap()
        }
    }

    /**
     * 索引的**唯一读取入口**：有内存副本就直接给，没有才读盘解析一次。
     *
     * 为什么不能各处直接 `loadIndex()`：进入曲库时「先亮缓存」和「扫盘判定 mtime 命中」
     * 会各读一次，同一份几千首的 JSON（几百 KB~1MB）就被解析了两遍；曲库每次重进都白烧
     * 几十毫秒 CPU。内存副本由 [saveIndex] / [updateMemoryIndex] 维护，与磁盘一致。
     */
    fun indexOrLoad(): Map<String, LocalTrackMeta> {
        inMemoryIndex?.let { return it }
        // 加锁是因为进曲库时「先亮缓存」与「扫盘判定命中」是两个并发协程：
        // 不加锁它们会同时看到 null，然后把同一份 JSON 各解析一遍。
        synchronized(indexLock) {
            inMemoryIndex?.let { return it }
            val loaded = loadIndex()
            inMemoryIndex = loaded
            return loaded
        }
    }

    /** 整份覆盖写（先写临时文件再 rename，避免半份 json 留在盘上） */
    fun saveIndex(tracks: Collection<LocalTrackMeta>): Boolean {
        if (tracks.isEmpty()) return false
        return try {
            rootDir().mkdirs()
            val tmp = File(rootDir(), "$INDEX_NAME.tmp")
            val list = tracks.toList()
            tmp.writeText(json.encodeToString(IndexFile.serializer(), IndexFile(tracks = list)))
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) return false
            // 落盘成功就顺手刷新内存副本：[trackOf] 是详情面板 / 播放页的唯一数据源，
            // 不同步的话「enrich 补的位深、按需抽到的封面」要等下次读盘才可见，
            // 表现就是「刚点开是空的，退出去再进来才有」。
            inMemoryIndex = list.associateBy { it.path }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[cache] write failed: ${t.message}")
            false
        }
    }

    /**
     * 清掉**已不在曲库里 / 文件戳已经变了**的封面缓存。
     *
     * 关键：keep 集合按「路径 + 文件戳 → 封面文件名」反查（`coverFileFor`），**不能**依赖
     * [LocalTrackMeta.coverPath] —— 封面是按需抽的，扫描/重扫刚结束时大多数记录的
     * `coverPath` 还是 null，拿它当 keep 会把好好的封面全删掉（这正是「重扫后封面丢失」的原因）。
     *
     * 顺带把「改了标签之前抽的旧图」清掉：旧图的名字带的是旧戳，不在 keep 里。
     */
    fun pruneCachedCovers(tracks: Collection<LocalTrackMeta>) {
        val dir = coversDir
        if (!dir.isDirectory) return
        val keepNames = tracks.map { coverFileFor(it).name }.toHashSet()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in keepNames) runCatching { f.delete() }
        }
        invalidateCovers()
    }

    /**
     * 这个文件**在当前文件戳下**已经抽好的封面（没有则 null）；重扫时用它把封面路径捡回来。
     *
     * 必须传整条记录：戳不一样就不能复用 —— 否则「换了内嵌封面」会被上一张图盖住。
     */
    fun existingCover(t: LocalTrackMeta): String? {
        val f = coverFileFor(t)
        return if (f.name in coversSnapshot()) f.absolutePath else null
    }

    // ── 用户偏好（各列表的排序方式 + 升降序），与 index.json 分开存，删缓存不会丢偏好 ──

    private const val PREFS_NAME = "local_library_prefs"

    /** 排序偏好的分页键：主页歌曲 + 三个分组列表 */
    const val PAGE_SONGS = "songs"
    const val PAGE_ARTISTS = "artists"
    const val PAGE_ALBUMS = "albums"
    const val PAGE_FOLDERS = "folders"

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sortModeKey(page: String) = "sort_$page"

    private fun sortAscKey(page: String) = "sort_${page}_asc"

    /** 读某页的排序模式名（枚举项名或短标签），没有返回 null（调用方用自己的默认值） */
    fun loadSortMode(page: String): String? = prefs()?.getString(sortModeKey(page), null)

    fun saveSortMode(page: String, mode: String) {
        runCatching { prefs()?.edit()?.putString(sortModeKey(page), mode)?.apply() }
    }

    /** 读某页的升降序；没存过返回 [default] */
    fun loadSortAscending(page: String, default: Boolean): Boolean =
        prefs()?.getBoolean(sortAscKey(page), default) ?: default

    fun saveSortAscending(page: String, ascending: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(sortAscKey(page), ascending)?.apply() }
    }

    // ── 删除歌曲的选项（记住上次选择；存在偏好文件里，清曲库缓存不会重置） ──

    private const val KEY_DELETE_LOCAL_FILE = "delete_local_file"

    /**
     * 删除本地歌曲时是否**连音频文件一起删**。
     *
     * 默认 `true`（用户点了删除就是不想再看到这首歌），并记住上一次的选择 ——
     * 取消勾选后再删一首，下次弹出来仍是没勾的状态，不会跳回默认。
     */
    fun loadDeleteLocalFile(): Boolean = prefs()?.getBoolean(KEY_DELETE_LOCAL_FILE, true) ?: true

    fun saveDeleteLocalFile(value: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(KEY_DELETE_LOCAL_FILE, value)?.apply() }
    }

    /** 曲库主页的排序（枚举形态） */
    fun loadSortPreference(): LocalSort? = loadSortMode(PAGE_SONGS)?.let { name ->
        LocalSort.entries.firstOrNull { it.name == name }
    }

    fun saveSortPreference(sort: LocalSort) = saveSortMode(PAGE_SONGS, sort.name)

    // ── 最近播放（最多 100 条，持久化） ──

    /**
     * 一条播放记录。
     *
     * @param path 文件绝对路径
     * @param at 最后一次播放时间（毫秒；次数相同时用它排前后）
     * @param count 累计播放次数（列表行首显示的就是它）
     */
    @Serializable
    data class RecentPlay(val path: String, val at: Long, val count: Int = 1)

    @Serializable
    private data class RecentFile(
        val version: Int = RECENT_VERSION,
        val items: List<RecentPlay> = emptyList(),
    )

    private const val RECENT_NAME = "recent.json"
    private const val RECENT_VERSION = 2

    /** 最近播放上限：超过就丢最旧的 */
    const val RECENT_LIMIT = 100

    private val recentFile: File get() = File(rootDir(), RECENT_NAME)

    private val recentLock = Any()

    /** 内存副本（列表页每帧都要按顺序映射，不能反复读盘） */
    @Volatile
    private var recentCache: List<RecentPlay>? = null

    /** 读最近播放：**按播放次数从高到低**（次数相同按最近播放时间倒序），最多 [RECENT_LIMIT] 条 */
    fun loadRecentPlays(): List<RecentPlay> {
        recentCache?.let { return it }
        val f = recentFile
        if (!f.isFile) {
            recentCache = emptyList()
            return emptyList()
        }
        val parsed = try {
            val file = json.decodeFromString(RecentFile.serializer(), f.readText())
            // 只拒绝「比当前更新」的格式；旧格式照读（count 有默认值）
            if (file.version > RECENT_VERSION) emptyList() else file.items
        } catch (_: Throwable) {
            emptyList()
        }
        val sorted = parsed
            .sortedWith(compareByDescending<RecentPlay> { it.count }.thenByDescending { it.at })
            .take(RECENT_LIMIT)
        recentCache = sorted
        return sorted
    }

    /**
     * 记录一次播放：已有记录 **次数 +1** 并刷新时间；新记录 count = 1。
     * 超过 [RECENT_LIMIT] 条时淘汰**次数最少、最久没播**的那条。
     *
     * 写入很小（≤100 条 ≈ 10KB），直接同步落盘 —— 调用点在播放器的 setSource 之后，
     * 异步反而会出现「刚播完就杀进程 → 记录丢失」。
     */
    fun recordPlay(path: String) {
        if (path.isBlank()) return
        synchronized(recentLock) {
            val now = System.currentTimeMillis()
            val current = loadRecentPlays()
            val existing = current.firstOrNull { it.path == path }
            val updated = if (existing != null) {
                current.map {
                    if (it.path == path) it.copy(at = now, count = it.count + 1) else it
                }
            } else {
                current + RecentPlay(path, now, 1)
            }.sortedWith(compareByDescending<RecentPlay> { it.count }.thenByDescending { it.at })
                .take(RECENT_LIMIT)
            recentCache = updated
            _writeRecent(updated)
        }
    }

    /**
     * 删掉一条最近播放记录（删歌时同步调用）。
     *
     * 不清的话，「最近播放」与详情面板的播放次数里会留着一条指向已删文件的死记录 ——
     * 点进去就是一首放不出来的歌。
     */
    fun removeRecentPlay(path: String) {
        if (path.isBlank()) return
        synchronized(recentLock) {
            val current = loadRecentPlays()
            val updated = current.filterNot { it.path == path }
            if (updated.size == current.size) return
            recentCache = updated
            _writeRecent(updated)
        }
    }

    /** 最近播放写盘（先写临时文件再 rename，避免半份 json） */
    private fun _writeRecent(items: List<RecentPlay>) {
        try {
            rootDir().mkdirs()
            val tmp = File(rootDir(), "$RECENT_NAME.tmp")
            tmp.writeText(
                json.encodeToString(RecentFile.serializer(), RecentFile(items = items)),
            )
            if (recentFile.exists()) recentFile.delete()
            tmp.renameTo(recentFile)
        } catch (t: Throwable) {
            Log.w(TAG, "[recent] write failed: ${t.message}")
        }
    }

    /** 清空最近播放（目前没有 UI 入口，留给以后） */
    fun clearRecentPlays() {
        synchronized(recentLock) {
            recentCache = emptyList()
            runCatching { recentFile.delete() }
        }
    }

    fun readLyric(path: String): String? {
        val audio = File(path)
        val base = audio.absolutePath.substringBeforeLast('.')
        var sidecar: Pair<String, Long>? = null
        for (ext in listOf("lrc", "LRC", "Lrc", "txt")) {
            val f = File("$base.$ext")
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                sidecar = text.trim() to f.lastModified()
                break
            }
        }
        var embedded = runCatching { AnnieTag.readLyric(audio) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (embedded == null) {
            embedded = runCatching { LocalTagLibProbe.probe(path)?.lyric }
                .getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (embedded == null) {
            embedded = LocalTagProbe.embeddedLyric(path)?.takeIf { it.isNotBlank() }
        }
        if (sidecar != null && embedded != null) {
            return if (audio.lastModified() > sidecar.second) embedded else sidecar.first
        }
        return sidecar?.first ?: embedded
    }

    @Volatile
    private var inMemoryIndex: Map<String, LocalTrackMeta>? = null

    private val indexLock = Any()

    fun trackOf(path: String): LocalTrackMeta? {
        val index = indexOrLoad()
        return index[path]
    }

    /** 曲库索引更新后同步内存副本（VM 扫完会调） */
    fun updateMemoryIndex(tracks: Collection<LocalTrackMeta>) {
        inMemoryIndex = tracks.associateBy { it.path }
    }

    /**
     * 把标签写进文件（本地曲库「编辑标签 / 写歌词」的唯一入口）。
     *
     * 写用 [AnnieTag]：能原地就原地（FLAC 复用 PADDING、MP3 复用旧 ID3 区、MP4 只碰 moov），
     * **音频数据不搬**。写完立刻用 TagLib 重读一遍落回索引 —— 文件里的值才是唯一事实来源，
     * 不能拿「我写进去的」当真相（第三方播放器、其他工具都可能改过）。
     *
     * @return 写入结果；成功时索引已同步。
     */
    fun writeTags(path: String, bag: TagBag): WriteResult {
        val file = File(path)
        if (!file.isFile) return WriteResult.fail("", "文件不存在")
        val result = AnnieTag.write(file, bag, WritePolicy.PreferInPlace)
        if (!result.ok) return result

        // 写完必须补齐「TagLib 读不到」的那几项：位深/真实编码/采样率/声道来自文件头
        // （`LocalHeaderProbe`），只跑 TagLib 会把 24bit 记成 0 —— 详情页的音质行会在
        // 「编辑一次元数据」之后立刻退化，而文件本身没问题。
        val probed = runCatching { LocalTagLibProbe.probe(path) }.getOrNull()
            ?.let { runCatching { LocalHeaderProbe.fill(it) }.getOrDefault(it) }
        // 写标签必然改文件戳，而封面缓存名里带着戳 → 旧图对新戳已经无效，直接清掉。
        // 不能像以前那样把旧 coverPath 传下去：`TagBag.cover` 能写新的内嵌图，
        // 保留旧路径就会出现「换了封面、界面上还是旧图」。
        deleteCoversFor(path)
        val current = indexOrLoad().toMutableMap()
        val old = current[path]
        // TagLib 完全读不出这个容器时（实测 DFF / DSF：`getMetadata` 直接失败返回 null）
        // **绝不能跳过索引更新** —— 否则写完标签索引还是旧值，播放页 / 列表一个字都不变，
        // 而且 `isCacheFresh` 会认为这条记录仍然新鲜，重扫也不会修。
        // 骨架用「TagLib 结果 → 旧记录 → 文件名」，文本字段随后由 AnnieTag 覆盖成文件真值。
        val skeleton = probed ?: old ?: run {
            val (fileNameTitle, fileNameArtist) = LocalTrackMeta.parseFileName(file.nameWithoutExtension)
            LocalTrackMeta(
                path = file.absolutePath,
                title = fileNameTitle,
                artist = fileNameArtist,
                album = file.parentFile?.name.orEmpty(),
                codec = file.extension.lowercase(),
            )
        }
        val base = skeleton.copy(
            // 文件戳与体积必须显式刷新：封面缓存文件名、`isCacheFresh` 都靠这两个值
            mtime = file.lastModified(),
            size = file.length(),
            coverPath = null,
            // 技术参数以「新解析为准、旧记录兜底」：宁可沿用一个已知正确的旧值，
            // 也不要把 0 写进索引（0 在界面上就是空白，而且会被当成缓存的有效值长期留着）
            codec = skeleton.codec.ifEmpty { old?.codec.orEmpty() },
            sampleRate = skeleton.sampleRate.takeIf { it > 0 } ?: old?.sampleRate ?: 0,
            bitDepth = skeleton.bitDepth.takeIf { it > 0 } ?: old?.bitDepth ?: 0,
            channels = skeleton.channels.takeIf { it > 0 } ?: old?.channels ?: 0,
            bitrate = skeleton.bitrate.takeIf { it > 0 } ?: old?.bitrate ?: 0,
            duration = skeleton.duration.takeIf { it > 0 } ?: old?.duration ?: 0,
            lossless = skeleton.lossless || (old?.lossless == true),
            probed = true,
            // withTrueBitrate：打标后重读也不能把 ALAC 的假码率（未压缩速率）写回去
        ).withTrueBitrate()
        // 文本字段反过来以**文件**为准：TagLib 解析不了 dff / dsf 的 ID3 块，重读回来是空的，
        // 直接落进索引就会出现「元数据改成功了，播放页 / 列表一个字都不变」。AnnieTag 就是
        // 写入器本身，它读得回来（清空歌词时返回 null，正好对上 cleared 的语义）。
        current[path] = withAnnieTagFields(base)
        updateMemoryIndex(current.values)
        saveIndex(current.values)
        return result
    }

    /** 该文件是否有可用歌词（列表上打「有歌词」标记用；只查 .lrc，不跑 ffprobe） */
    fun hasSidecarLyric(path: String): Boolean {
        val base = path.substringBeforeLast('.')
        return listOf("lrc", "LRC", "Lrc").any { File("$base.$it").isFile }
    }

    /**
     * 用 `AnnieTag` 把**文本字段**覆盖到一条记录上（文件里真有的才覆盖，读不到保留原值）。
     *
     * 为什么需要：TagLib 解析不了 DSDIFF / DSF 的 `ID3 ` 块，读回来全是空，于是
     * `LocalTagLibProbe.probe` 只能拿文件名当标题、父目录名当专辑 —— 写完标签，
     * 索引里仍是这些回落值，表现就是「改了元数据，播放页 / 列表一个字都不变」。
     * `AnnieTag` 就是写入器本身，它读得回来。
     */
    fun withAnnieTagFields(meta: LocalTrackMeta): LocalTrackMeta {
        val bag = runCatching { AnnieTag.read(File(meta.path)) }.getOrNull() ?: return meta
        if (bag.isEmpty()) return meta
        fun v(key: TagKey): String? = bag[key]?.takeIf { it.isNotBlank() }
        fun n(key: TagKey): Int? =
            v(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        return meta.copy(
            title = v(TagKey.Title) ?: meta.title,
            artist = v(TagKey.Artist) ?: meta.artist,
            album = v(TagKey.Album) ?: meta.album,
            albumArtist = v(TagKey.AlbumArtist) ?: meta.albumArtist,
            year = v(TagKey.Year) ?: meta.year,
            track = n(TagKey.Track) ?: meta.track,
            disc = n(TagKey.Disc) ?: meta.disc,
            genre = v(TagKey.Genre) ?: meta.genre,
            composer = v(TagKey.Composer) ?: meta.composer,
            isrc = v(TagKey.Isrc) ?: meta.isrc,
            version = v(TagKey.Comment) ?: meta.version,
            copyright = v(TagKey.Copyright) ?: meta.copyright,
            lyric = v(TagKey.Lyric) ?: meta.lyric,
        )
    }

    /**
     * 用 `AnnieTag` 把内嵌封面写成文件（[TagLib 的 `getPictures`][LocalTagLibProbe.cover]
     * 认不出 DSD 的 APIC，DSD 只有这一条路）。
     *
     * 不缩放：DSD 专辑通常一张图几 MB，但这类文件本来就少，缩放的收益抵不上多一条路径。
     */
    fun coverFromTags(path: String, out: File): String? {
        val cover = runCatching { AnnieTag.read(File(path)) }.getOrNull()?.cover ?: return null
        if (cover.bytes.isEmpty()) return null
        return runCatching {
            out.parentFile?.mkdirs()
            out.writeBytes(cover.bytes)
            out.absolutePath
        }.getOrNull()
    }

    /**
     * 把封面导出成同目录的 `cover.jpg` / `cover.png`（编辑页的「保存封面」按钮）。
     *
     * 来源优先级：[cover]（编辑页当次新选的那张）→ 文件内嵌图（`AnnieTag`）。
     * 命名固定 `cover.*`：`sidecarCover()` 的查找列表里它就是第一个，
     * 导出来之后本 App 在「内嵌图缺失」时也能直接拿它当封面。
     *
     * @return 写成功返回 true；没有可用封面 / 目录不可写返回 false
     */
    fun writeSidecarCover(path: String, cover: CoverBytes?): Boolean {
        if (path.isBlank()) return false
        val audio = File(path)
        val dir = audio.parentFile ?: return false
        val bytes = cover?.bytes?.takeIf { it.isNotEmpty() }
            ?: runCatching { AnnieTag.read(audio)?.cover?.bytes }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return false
        val png = bytes.size > 3 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() == 0x50 &&
            bytes[2].toInt() == 0x4E &&
            bytes[3].toInt() == 0x47
        val out = File(dir, if (png) "cover.png" else "cover.jpg")
        return runCatching {
            out.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    /**
     * 把歌词写成同目录的同名 `.lrc`（编辑页的「保存 .lrc」按钮）。
     *
     * 文本为空时**删掉**已有的 `.lrc`：用户清空歌词再点保存，意图是"别留旧歌词"。
     * 只写 `.lrc` 这一种大小写 —— `readLyric` 的查找顺序里它排第一，不必也不该去动
     * 用户自己放的 `.LRC` / `.txt`。
     */
    fun writeSidecarLrc(audioPath: String, lyric: String): Boolean {
        if (audioPath.isBlank()) return false
        val base = audioPath.substringBeforeLast('.')
        if (base == audioPath) return false
        val file = File("$base.lrc")
        return runCatching {
            if (lyric.isBlank()) {
                file.delete()
                true
            } else {
                file.writeText(lyric)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * 同目录的**外挂封面**（播放方案阶段 2 第 3 项：内嵌优先 → 否则同目录 `cover.jpg`）。
     *
     * 返回原图路径**不复制**：同一张专辑的十几首歌共用一张 `cover.jpg`，
     * 每首复制一份到 `covers/` 只会白占几百 KB × N。UI 侧统一用 `file://` 加载。
     */
    fun sidecarCover(audioPath: String): File? {
        val dir = File(audioPath).parentFile ?: return null
        // 先按常见名精确找（大小写敏感的设备上最快）
        for (name in SIDECAR_COVER_NAMES) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0) return f
        }
        // 再按前缀不区分大小写兜一遍（Cover.JPG / FOLDER.PNG…）
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (!f.isFile || f.length() <= 0) continue
            val n = f.name.lowercase()
            val hit = n.startsWith("cover.") || n.startsWith("folder.") ||
                n.startsWith("front.") || n.startsWith("albumart.")
            if (hit && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                return f
            }
        }
        return null
    }

    /** 调试用：缓存目录体积 */
    fun cacheSizeBytes(): Long {
        var total = 0L
        val dir = rootDir()
        if (!dir.isDirectory) return 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    /** 清空曲库缓存（index + covers） */
    fun clearAll() {
        runCatching { indexFile.delete() }
        runCatching { coversDir.deleteRecursively() }
        // 内存副本必须一起清：清完缓存后 [indexOrLoad] 若还拿着旧副本，
        // 曲库会显示一批「磁盘上已经不存在」的记录。
        inMemoryIndex = null
    }
}
