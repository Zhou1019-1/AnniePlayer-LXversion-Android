package com.annieplayer.annie_music.kt.features.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.data.favorites.FavoriteStore
import com.annieplayer.annie_music.kt.data.local.LocalFastProbe
import com.annieplayer.annie_music.kt.data.local.LocalHeaderProbe
import com.annieplayer.annie_music.kt.data.local.LocalGroupSets
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalMediaScanner
import com.annieplayer.annie_music.kt.data.local.LocalScanSettings
import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LocalSort
import com.annieplayer.annie_music.kt.data.local.LocalTagLibProbe
import com.annieplayer.annie_music.kt.data.local.LocalTagProbe
import com.annieplayer.annie_music.kt.data.local.withTrueBitrate
import com.annieplayer.annie_music.kt.data.local.isCacheFresh
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.WriteResult
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.sortTracks
import com.annieplayer.annie_music.kt.data.local.groupTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地曲库的状态机（扫描 → 占位可见 → 后台补 tag → 落缓存）。
 *
 * 三层速度设计（曲库上千首时才看得出来）：
 *
 * 1. **枚举**（几毫秒~几百毫秒）：只列文件，不碰内容；立刻用「文件名 + 大小 + 文件夹」
 *    造出整张列表，用户马上看到东西。
 * 2. **基础 tag**（`MediaMetadataRetriever`，6 并发，单首 3~15ms）：标题/艺术家/专辑/时长/
 *    音轨号/采样率/码率。`ffprobe` 只在系统解析器返空时兜底（APE / DSD / 损坏文件），
 *    正常曲库几乎不会走到。
 * 3. **封面**（**按需**）：列表可见才抽，抽过就落盘复用。绝不在扫描阶段批量抽 ——
 *    3000 首要是每首都解一张内嵌图，扫描会从秒级变成分钟级。
 *
 * 二次启动靠 `index.json` 的 `mtime + size` 判定，没变过的文件 0 次解析。
 */
class LocalLibraryViewModel : ViewModel() {

    data class UiState(
        val tracks: List<LocalTrackMeta> = emptyList(),
        val scanning: Boolean = false,
        /** 还在补基础 tag 的数量（0 = 全部读完） */
        val pending: Int = 0,
        /** 扫描阶段遍历过的目录数（进度展示用） */
        val scannedFiles: Int = 0,
        val query: String = "",
        val sort: LocalSort = LocalSort.byTitle,
        /** 升降序（与 [sort] 一起持久化；换排序方式时回落到该方式的默认方向） */
        val sortAscending: Boolean = LocalSort.byTitle.defaultAscending,
        val section: LocalSection = LocalSection.songs,
        /** 进入某个艺术家 / 专辑 / 文件夹后的 key；null = 分组列表页 */
        val groupKey: String? = null,
        val permissionMissing: Boolean = false,
        val started: Boolean = false,
    ) {
        val probedCount: Int get() = tracks.count { it.probed }
        val totalSize: Long get() = tracks.sumOf { it.size }
        val busy: Boolean get() = scanning || pending > 0
    }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _groups = MutableStateFlow(LocalGroupSets())
    val groups: StateFlow<LocalGroupSets> = _groups.asStateFlow()

    private var groupJob: Job? = null

    private var _job: Job? = null

    /** 待抽封面的路径队列（滚动到可见时投递，串行消费） */
    private val _coverQueue = Channel<String>(Channel.UNLIMITED)

    /** 已投递/已尝试过的封面，避免重复读同一个文件 */
    private val _coverTouched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 封面上盘节流计数（每抽到 5 张写一次 index） */
    private val coverPersistTick = AtomicInteger(0)

    init {
        // 恢复上次选的排序 + 升降序（重启后不回退到「标题 · 升序」）
        val savedSort = LocalLibraryStore.loadSortPreference()
        val savedAsc = LocalLibraryStore.loadSortAscending(
            LocalLibraryStore.PAGE_SONGS,
            (savedSort ?: _state.value.sort).defaultAscending,
        )
        _state.value = _state.value.copy(
            sort = savedSort ?: _state.value.sort,
            sortAscending = savedAsc,
        )
        // 2 个封面消费者：抽图是纯 I/O + 少量解码，2 条足够且不会抢扫描的 CPU
        repeat(COVER_CONCURRENCY) {
            viewModelScope.launch(Dispatchers.IO) {
                for (path in _coverQueue) {
                    _loadCover(path)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 退场前把「按需抽到的封面路径」补写一次（节流写盘可能还差几张没落盘）
        val tracks = _state.value.tracks
        if (tracks.isNotEmpty()) {
            runCatching { LocalLibraryStore.saveIndex(tracks) }
        }
    }

    // ── 对外动作 ──

    /** 首次进入时调用；重复调用无效（Activity 作用域 VM 会跨重组存活） */
    fun start(context: Context) {
        if (_state.value.started) return
        _state.value = _state.value.copy(started = true)
        val appContext = context.applicationContext
        // 1) 先亮缓存，界面立刻有内容
        viewModelScope.launch(Dispatchers.IO) {
            val cached = LocalLibraryStore.indexOrLoad()
            if (cached.isEmpty() || _state.value.tracks.isNotEmpty()) return@launch
            val alive = cached.values.filter { t -> t.probed && File(t.path).isFile }
            if (alive.isEmpty()) return@launch
            LocalLibraryStore.updateMemoryIndex(alive)
            _state.value = _state.value.copy(
                tracks = sortTracks(alive, _state.value.sort, _state.value.sortAscending),
                scannedFiles = alive.size,
            )
            scheduleGroups(_state.value.tracks, debounceMs = 0)
        }
        // 2) 再扫盘 + 补 tag
        refresh(appContext)
    }

    /**
     * 扫描 + 补 tag。
     *
     * @param force true = **忽略缓存全部重新解析**（「重扫」按钮走这条）。默认 false 时按
     *   `mtime + size` 命中缓存 —— 这也意味着「改了解析逻辑但文件没变」的情况不会自动生效，
     *   所以按钮必须传 true，否则用户点了重扫看起来什么也没发生。
     */
    fun refresh(context: Context, force: Boolean = false) {
        if (_job?.isActive == true) return
        val appContext = context.applicationContext
        _job = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(scanning = true, permissionMissing = false, pending = 0)
            // 重扫允许重新抽封面：清掉「已尝试」记录，否则封面文件被清过之后再也补不回来
            _coverTouched.clear()
            val files = try {
                LocalMediaScanner.scan(appContext, force = force) { visited ->
                    if (visited % 50 == 0) {
                        _state.value = _state.value.copy(scannedFiles = visited)
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }

            if (files.isEmpty()) {
                _state.value = _state.value.copy(
                    scanning = false,
                    tracks = emptyList(),
                    permissionMissing = !LocalMediaScanner.hasAllFilesAccess(),
                )
                return@launch
            }

            val cached = LocalLibraryStore.indexOrLoad()
            val metas = ConcurrentHashMap<String, LocalTrackMeta>(files.size * 2)
            val order = ArrayList<String>(files.size)
            val todo = ArrayList<File>()

            for (f in files) {
                val path = f.absolutePath
                order += path
                // 每首只 stat 两次，并把结果复用给 placeholder。
                // 原来「缓存命中判定」各 stat 一次、placeholder 内部再各一次 —— 首次全量扫描时
                // 3000 首会白跑 6000 次 `stat`（FUSE 上 0.1~0.2ms/次，合计 0.6~1s）。
                val mtime = f.lastModified()
                val size = f.length()
                // 命中判据只在这里算一次（`isCacheFresh` 也是单测的对象 —— 它决定了
                // 「改了标签 / 换了封面，重扫会不会更新」）
                val reuse = if (force) null else cached[path]?.takeIf { isCacheFresh(it, mtime, size) }
                if (reuse != null) {
                    metas[path] = reuse
                } else {
                    metas[path] = LocalTrackMeta.placeholder(f, mtime, size)
                    todo += f
                }
            }

            _publish(metas, order, scanning = true, pending = todo.size)

            if (todo.isNotEmpty()) {
                _probeAll(todo, metas, order)
            }

            val all = order.mapNotNull { metas[it] }
            val minSeconds = LocalScanSettings.minDurationSecondsNow
            val kept = if (minSeconds <= 0) {
                all
            } else {
                all.filter { it.duration <= 0 || it.duration >= minSeconds }
            }
            val finalOrder = sortTracks(
                kept,
                _state.value.sort,
                _state.value.sortAscending,
            )
            _state.value = _state.value.copy(tracks = finalOrder, scanning = false, pending = 0)
            scheduleGroups(finalOrder, debounceMs = 0)
            LocalLibraryStore.saveIndex(all)
            LocalLibraryStore.updateMemoryIndex(all)
            LocalLibraryStore.pruneCachedCovers(all)
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun cycleSort() {
        val sort = _state.value.sort.next()
        // 换排序方式时回落到该方式的默认方向（标题→升序、时长/添加时间→降序）
        val ascending = sort.defaultAscending
        _state.value = _state.value.copy(
            sort = sort,
            sortAscending = ascending,
            tracks = sortTracks(_state.value.tracks, sort, ascending),
        )
        // 记住这次选择：下次启动 / 重启后仍按这个排序
        LocalLibraryStore.saveSortPreference(sort)
        LocalLibraryStore.saveSortAscending(LocalLibraryStore.PAGE_SONGS, ascending)
    }

    /** 升降序切换（对当前排序方式生效，并持久化） */
    fun toggleSortOrder() {
        val ascending = !_state.value.sortAscending
        val sort = _state.value.sort
        _state.value = _state.value.copy(
            sortAscending = ascending,
            tracks = sortTracks(_state.value.tracks, sort, ascending),
        )
        LocalLibraryStore.saveSortAscending(LocalLibraryStore.PAGE_SONGS, ascending)
    }

    fun openSection(section: LocalSection) {
        _state.value = _state.value.copy(
            section = section,
            groupKey = null,
            query = if (section == LocalSection.songs) "" else _state.value.query,
        )
    }

    fun openGroup(key: String) {
        _state.value = _state.value.copy(groupKey = key)
    }

    fun closeGroup() {
        _state.value = _state.value.copy(groupKey = null)
    }

    /**
     * 请求抽某一首的封面（列表行滚动到可见时调用）。
     *
     * 幂等：同一路径只入队一次；已抽过的（`coverPath != null` 或已尝试过）直接返回。
     */
    fun requestCover(path: String) {
        if (_coverTouched.contains(path)) return
        val meta = _state.value.tracks.firstOrNull { it.path == path } ?: return
        if (meta.coverPath != null) return
        if (!_coverTouched.add(path)) return
        _coverQueue.trySend(path)
    }

    /**
     * 补「快读拿不到」的字段（位深 / 精确编码 / 声道），只在打开文件详情时调用。
     *
     * 走 ffprobe（慢一点但准），结果写回 state 与缓存 —— 同一首只补一次。
     */
    fun enrich(path: String) {
        val target = _state.value.tracks.firstOrNull { it.path == path } ?: return
        if (target.bitDepth > 0) return
        viewModelScope.launch(Dispatchers.IO) {
            // 封面输出名要带**当前文件戳**：ffprobe 抽图写的是这张封面，名字错了就会
            // 1) 和按需抽取写到两个文件里，2) 之后被 prune 当孤儿删掉。
            val cover = LocalLibraryStore.coverFileFor(target)
            val deep = LocalTagProbe.probe(path, coverOut = cover) ?: return@launch
            val tracks = _state.value.tracks.map { t ->
                if (t.path != path) {
                    t
                } else {
                    t.copy(
                        bitDepth = deep.bitDepth,
                        channels = deep.channels,
                        codec = deep.codec.ifEmpty { t.codec },
                        sampleRate = if (deep.sampleRate > 0) deep.sampleRate else t.sampleRate,
                        bitrate = if (deep.bitrate > 0) deep.bitrate else t.bitrate,
                        coverPath = t.coverPath ?: deep.coverPath,
                        // ffprobe 的码率可能是"未压缩速率"（见 `LocalBitrate.kt`），这里再修一次
                    ).withTrueBitrate()
                }
            }
            _state.value = _state.value.copy(tracks = tracks)
            LocalLibraryStore.saveIndex(tracks)
        }
    }

    /**
     * 删除一首本地歌。
     *
     * @param deleteFile `true`（默认）= 连音频文件一起删；`false` = 只从曲库移除、文件留在盘上。
     *   这个选择由确认弹窗给出并持久化（[LocalLibraryStore.loadDeleteLocalFile]）。
     *
     * 顺序有讲究：**先删 MediaStore 记录 → 再删文件**。MediaStore 那一行是靠 `_data` 路径
     * 定位的，文件先没了就查不到该行，系统媒体库里会永久留一条打不开的条目。
     * 之后才轮到封面缓存与索引（磁盘 index.json + 内存副本，二者由 store 同步）。
     */
    fun deleteTrack(path: String, deleteFile: Boolean) {
        if (path.isBlank()) return
        deleteTracks(listOf(path), deleteFile)
    }

    fun deleteTracks(paths: List<String>, deleteFile: Boolean) {
        val targets = paths.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (path in targets) {
                if (deleteFile) {
                    runCatching { MediaStoreScan.deleteFromMediaStore(path) }
                    runCatching { File(path).delete() }
                    runCatching { FavoriteStore.removeLocalSong(path) }
                }
                runCatching { LocalLibraryStore.deleteCoversFor(path) }
                LocalLibraryStore.removeRecentPlay(path)
                _coverTouched.remove(path)
            }
            val gone = targets.toHashSet()
            val tracks = _state.value.tracks.filterNot { it.path in gone }
            _state.value = _state.value.copy(tracks = tracks)
            if (tracks.isEmpty()) {
                LocalLibraryStore.clearAll()
            } else {
                LocalLibraryStore.saveIndex(tracks)
            }
        }
    }

    /**
     * 把编辑页提交的标签写进文件，并把结果同步回内存列表。
     *
     * 与 [deleteTrack] 一样是「磁盘 + 内存 + 缓存」三处一起改；额外要清的是
     * [_coverTouched] —— 写完封面缓存已经被删（文件戳变了），不清这个集合的话
     * 可见行不会重新请求抽图，封面会永久空着。
     *
     * @return 写入结果；失败时**不动内存**（文件没变，列表也不该变）。
     */
    fun applyTagEdit(path: String, bag: TagBag, sidecarLrc: String? = null): WriteResult {
        if (path.isBlank()) return WriteResult.fail("", "路径为空")
        // 同步写：编辑页要拿 result 决定「关页面」还是「留在页内显示错误」，
        // 异步就得再引入一个事件通道，得不偿失（一次原地写是几十毫秒）
        return try {
            val result = LocalLibraryStore.writeTags(path, bag)
            if (!result.ok) return result
            // `.lrc` 现在是独立按钮（[exportSidecarLyric]），整页保存不再顺带写它；
            // 这个参数留给宿主兼容，正常恒为 null。
            if (sidecarLrc != null) {
                runCatching { LocalLibraryStore.writeSidecarLrc(path, sidecarLrc) }
            }
            val fresh = LocalLibraryStore.trackOf(path)
            if (fresh != null) {
                _coverTouched.remove(path)
                _state.value = _state.value.copy(
                    tracks = sortTracks(
                        _state.value.tracks.map { if (it.path == path) fresh else it },
                        _state.value.sort,
                        _state.value.sortAscending,
                    ),
                )
                // 写完标签封面缓存已被删（文件戳变了），而列表的「可见项变化」不会因为
                // 数据更新而重新发射 —— 不主动补一次，封面会一直空着，直到用户重扫。
                if (fresh.coverPath == null) requestCover(path)
            }
            result
        } catch (t: Throwable) {
            WriteResult.fail("", t.message ?: t.javaClass.simpleName)
        }
    }

    // ── 内部 ──

    /**
     * 编辑页「保存 .lrc」按钮：把**当前这版歌词**立刻写成同目录的 `{文件名}.lrc`。
     *
     * 与「保存」互不依赖：不写文件标签，也不改索引（歌词本身还没落进文件）。
     */
    fun exportSidecarLyric(path: String, lyric: String): Boolean =
        LocalLibraryStore.writeSidecarLrc(path, lyric)

    /**
     * 编辑页「保存封面」按钮：把封面导出成同目录的 `cover.jpg` / `cover.png`。
     *
     * [cover] 是编辑页当次新选的那张（可能还没保存进文件）；null 则用文件里现有的内嵌图。
     */
    fun exportSidecarCover(path: String, cover: CoverBytes?): Boolean =
        LocalLibraryStore.writeSidecarCover(path, cover)

    /** DSD 容器：TagLib 读不了它们的 `ID3 ` / ID3v2 块，歌词只能由 [AnnieTag] 补 */
    private fun isDsdPath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in DSD_EXTS

    /** 并发补基础 tag；每读若干条刷一次界面 */
    private suspend fun _probeAll(
        todo: List<File>,
        metas: ConcurrentHashMap<String, LocalTrackMeta>,
        order: List<String>,
    ) {
        val sem = Semaphore(PROBE_CONCURRENCY)
        val done = AtomicInteger(0)
        val lastEmit = AtomicLong(0L)
        val total = todo.size

        coroutineScope {
            todo.map { file ->
                async {
                    sem.withPermit {
                        val path = file.absolutePath
                        // 三层降级：TagLib（μs~ms）→ MediaMetadataRetriever（~20ms）→ ffprobe（~100ms+）
                        var meta = LocalTagLibProbe.probe(path)
                        if (meta == null) meta = LocalFastProbe.probe(path)
                        if (meta == null) meta = LocalTagProbe.probe(path)
                        // TagLib/系统解析器都不给位深 → 纯 Kotlin 读文件头补齐（1~2ms，不引 native）
                        if (meta != null) meta = LocalHeaderProbe.fill(meta)
                        // DSD 容器（dff / dsf）的 ID3 块 TagLib 不解析：只靠上面那三层降级，
                        // 标题会回落成文件名、歌词永远是空（症状：写进去了、重扫完又没了）。
                        // 整条记录用我们自己的 AnnieTag 补一次 —— 只对 DSD 做，不给全库加开销。
                        if (meta != null && isDsdPath(path)) {
                            meta = LocalLibraryStore.withAnnieTagFields(meta)
                        }
                        // 封面是按需抽的，probe 拿不到路径：already-抽过的直接从 covers/ 捡回来，
                        // 否则重扫会把「有封面」变成「没封面」，还会被 prune 当成孤儿删掉。
                        // 只认**当前文件戳**下的那张图（文件名带 mtime+size）—— 换了内嵌封面
                        // 之后旧图名字对不上，这里自然拿不到，接着按需重抽。
                        if (meta != null && meta.coverPath == null) {
                            val existing = LocalLibraryStore.existingCover(meta)
                            if (existing != null) meta = meta.copy(coverPath = existing)
                        }
                        if (meta != null) {
                            metas[path] = meta
                        } else {
                            // 三层解析全失败 = 文件已经不存在 / 彻底损坏 → 丢掉这条占位记录，
                            // 否则列表里会留一个点不开的幽灵条目（枚举阶段为了速度不再 stat）
                            metas.remove(path)
                        }

                        val n = done.incrementAndGet()
                        val now = System.currentTimeMillis()
                        // 节流：每 12 条 或 每 200ms 刷一次。刷得太勤会让 LazyColumn 反复做
                        // 全量 diff（几千项），扫描期间滑动就会明显掉帧。
                        if (n == total || n % 12 == 0 || now - lastEmit.get() >= 200) {
                            lastEmit.set(now)
                            _publish(metas, order, scanning = true, pending = total - n)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** 抽一张封面并写回 state（无封面也标记，避免下次再读） */
    private fun _loadCover(path: String) {
        val current = _state.value.tracks.firstOrNull { it.path == path } ?: return
        if (current.coverPath != null) return
        val out = LocalLibraryStore.coverFileFor(current)
        // 五级降级：AnnieTag（DSD 专用，TagLib 认不出 dff/dsf 的 APIC）→ TagLib 图片字节
        // （最快）→ 系统 retriever → ffmpeg 抽帧 → 同目录 cover.jpg
        val cover = (if (isDsdPath(path)) LocalLibraryStore.coverFromTags(path, out) else null)
            ?: LocalTagLibProbe.cover(path, out)
            ?: LocalFastProbe.extractCover(path, out)
            ?: LocalTagProbe.extractCover(path, out).takeIf { it }?.let { out.absolutePath }
            ?: LocalLibraryStore.sidecarCover(path)?.absolutePath
        if (cover == null) return
        _state.value = _state.value.copy(
            tracks = _state.value.tracks.map { t ->
                if (t.path == path) t.copy(coverPath = cover) else t
            },
        )
        scheduleGroups(_state.value.tracks, debounceMs = 800)
        // 抽到就落盘索引（节流：每 5 张写一次）。不写回的话，重启后封面路径全丢，
        // 表现就是「这次有封面、下次进来又没了」。
        if (coverPersistTick.incrementAndGet() % 5 == 0) {
            LocalLibraryStore.saveIndex(_state.value.tracks)
        }
    }

    /** 按固定顺序把 [metas] 刷进 state（顺序不变 → 列表不重排） */
    private fun _publish(
        metas: Map<String, LocalTrackMeta>,
        order: List<String>,
        scanning: Boolean,
        pending: Int,
    ) {
        val list = order.mapNotNull { metas[it] }
        _state.value = _state.value.copy(
            tracks = list,
            scanning = scanning,
            pending = pending,
        )
        scheduleGroups(list)
    }

    private fun scheduleGroups(tracks: List<LocalTrackMeta>, debounceMs: Long = 400) {
        groupJob?.cancel()
        if (tracks.isEmpty()) {
            if (_groups.value.tracks != -1) _groups.value = LocalGroupSets()
            return
        }
        val snapshot = tracks
        groupJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) delay(debounceMs)
            val artists = groupTracks(snapshot, LocalSection.artists)
            val albums = groupTracks(snapshot, LocalSection.albums)
            val folders = groupTracks(snapshot, LocalSection.folders)
            _groups.value = LocalGroupSets(artists, albums, folders, snapshot.size)
        }
    }

    companion object {
        /**
         * tag 读取并发。
         *
         * 这类解析是「小随机读 + 少量 CPU」，I/O 密集，并发高一点收益明显；6 条在中端机上
         * 基本能吃满顺序读带宽，再高开始抢 UI 线程的 CPU（列表会掉帧）。
         */
        private const val PROBE_CONCURRENCY = 6

        /** 封面并发（抽图要解码，压低一些） */
        private const val COVER_CONCURRENCY = 2

        /** DSD 容器扩展名（TagLib 不解析它们的标签块，歌词与文本标签只能走 [AnnieTag]） */
        private val DSD_EXTS = setOf("dff", "dsf", "dsd")
    }
}
