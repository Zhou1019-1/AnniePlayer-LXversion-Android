package com.annieplayer.annie_music.kt.core.audio
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.annieplayer.annie_music.kt.MainActivity
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
import com.annieplayer.annie_music.kt.core.audio.AudioOutputStatus
import com.annieplayer.annie_music.kt.core.audio.AudioRoute
import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.TuningConfig
import com.annieplayer.annie_music.kt.core.audio.dap.RELOAD_BURST_WINDOW_MS
import com.annieplayer.annie_music.kt.core.audio.dap.ReloadBurst
import com.annieplayer.annie_music.kt.core.audio.dap.reloadBurstReset
import com.annieplayer.annie_music.kt.core.audio.dap.reloadBurstStep
import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport
import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransportDecision
import com.annieplayer.annie_music.kt.core.audio.dsd.dopFrameRate
import com.annieplayer.annie_music.kt.core.audio.dsd.dsdSinkFor
import com.annieplayer.annie_music.kt.core.audio.dsd.resolveDsdChain
import com.annieplayer.annie_music.kt.core.audio.dsdFullChainRate
import com.annieplayer.annie_music.kt.core.audio.dsdMaxPcmRate
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegLocalExtractor
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.PositionOnlyDataSource
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegLocalRoute
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegOutputStream
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.PlayerBackend
import com.annieplayer.annie_music.kt.core.state.indexAfterMove
import com.annieplayer.annie_music.kt.core.state.indexAfterRemoval
import com.annieplayer.annie_music.kt.core.audio.apple.ApplePlaybackServer
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.fetchPlayUrlForBackend
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.effectivePlayQualityOf
import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.sources.qobuz.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzHiresFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/*
     *
     * 音频引擎（1:1 移植 Flutter 版 `lib/core/audio/audio_backend.dart`（该实现已随 flutter_app 从本分支移除），
     * 对齐其 `AudioPlayerBackend`）。
     *
     * 映射总览：
     * - just_audio `AudioPlayer`            -> Media3 `ExoPlayer`
     * - `LockCachingAudioSource`           -> Media3 `CacheDataSource` + `SimpleCache`
     * - `AudioPlayerHandler`（audio_service）-> `MusicSessionService`（MediaSessionService）
     * - `_setSourceIfCurrent`（串行 + 令牌）-> **删除**：ExoPlayer 天然串行，见下方说明
     * - `positionStream` / `durationStream` -> 固定 200ms 的进度协程（ExoPlayer 无 stream）
     *
     * ## 串行化说明（对应 Dart `_setSourceChain` / `_setSourceIfCurrent`）
     * Dart 需要显式串行链，是因为多次 `setUrl` 可能乱序完成而互相覆盖音源。Media3 的
     * `ExoPlayer.setMediaSource` / `prepare` 都在应用主线程顺序执行、天然串行，因此这里
     * **不再需要*
     * `_setSourceChain`。保留的 `_playRequestId` 仅用于保护**异步 URL 解析*
     *
     * 期间的过期请求（解析是网络调用，仍可能乱序返回），这与 Dart 的令牌校验语义一致。
     *
     * ## 事件 key（与 `PlayerState.kt` 的 `PlayerViewModel._subscribe` 期望完全一致）
     * `switchTo` (Int) / `isPlaying` (Boolean) / `position` (Double, 秒) / `duration` (Double, 秒)。
     * Dart 的事件 map 只有后三个；`switchTo` 是**解析直链之前**发的切歌意图（Dart 版靠 UI
     * 自己乐观更新，通知栏 / 锁屏那条路没有乐观更新，只能由引擎告知）。
     * 「ended」不单独发事件，而是在内部触发自动跳下一首，与 Dart `_handleEnded` 相同。
     * 「buffered」在 Dart 里只进通知的 PlaybackState（`setPosition`
     * 的 bufferedPosition），不进事件流；Media3 由 MediaSession 自动同步缓冲进度。 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaPlayerBackend(
    private val appContext: Context,
) : PlayerBackend {
    private val _context: Context = appContext.applicationContext

    /**
     * 引擎协程域。
     *
     * 必须带 [CoroutineExceptionHandler]：否则 `launch` 里漏网的异常会走默认未捕获处理器
     * **直接崩掉进程**（`SupervisorJob` 只隔离兄弟任务，并不吞异常）。
     */
    private val _scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(_tag, "[audio] uncaught in engine scope", e) },
    )

    private val _events = MutableSharedFlow<Map<String, Any?>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 引擎事件流（keyed map，key 见类 KDoc） */
    override val events: Flow<Map<String, Any?>> = _events.asSharedFlow()

    // ── 引擎对象（全部可空，不用 lateinit / 不用强制解包） ──

    private var _player: ExoPlayer? = null

    /**
     * 播放意图的**跨线程副本**（`Player.playWhenReady` 的最新值）。
     *
     * 只由主线程的 `AnniePlayerListener` 写、由独占 sink 的播放线程读，所以必须 volatile。
     * 不能让 sink 直接读 `_player.playWhenReady`：那个字段只在播放器自己的线程里更新，
     * 播放线程读到的可能是旧值 —— 表现是"点播放后第一块数据被判成已暂停、立刻又停流"。
     */
    @Volatile private var _playWhenReady: Boolean = false

    @Volatile private var _playWhenReadyAtMs: Long = 0L

    private var _switchingSources: Int = 0

    private val _switchingSource: Boolean get() = _switchingSources > 0

    private suspend fun <T> _inSwitchWindow(block: suspend () -> T): T {
        _switchingSources++
        return try {
            block()
        } finally {
            _switchingSources--
        }
    }

    private var _resumeSeekMs: Long = 0L

    private var _resumeSeekSettled: Boolean = true

    private var _resumeSeekAttempts: Int = 0

    private fun _cancelResumeSeek() {
        _resumeSeekMs = 0L
        _resumeSeekSettled = true
        _resumeSeekAttempts = 0
    }

    private fun _armResumeSeek(ms: Long) {
        _resumeSeekMs = ms
        _resumeSeekSettled = ms <= 0L
        _resumeSeekAttempts = 0
        if (!_resumeSeekSettled) _verifyResumeSeekSoon()
    }

    private fun _verifyResumeSeekSoon() {
        val target = _resumeSeekMs
        if (target <= 0L || _resumeSeekSettled) return
        val attempt = _resumeSeekAttempts
        _scope.launch { _verifyResumeSeek(target, attempt) }
    }

    private fun _resumeSeekTargetMs(pendingOnly: Boolean = true): Long {
        if (pendingOnly && _resumeSeekSettled) return 0L
        return _resumeSeekMs.coerceAtLeast(0L)
    }

    private fun _applyResumeSeek() {
        val p = _player ?: return
        val target = _resumeSeekMs
        if (target <= 0L) {
            _resumeSeekSettled = true
            return
        }
        p.seekTo(target)
        _resumeSeekAttempts++
        _resumeSeekSettled = false
        val attempt = _resumeSeekAttempts
        _scope.launch { _verifyResumeSeek(target, attempt) }
    }

    private suspend fun _verifyResumeSeek(targetMs: Long, attempt: Int) {
        delay(400L)
        if (_resumeSeekSettled) return
        val p = _player ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(-1L)
        if (pos >= 0L && kotlin.math.abs(pos - targetMs) <= RESUME_SEEK_TOLERANCE_MS) {
            _resumeSeekSettled = true
            return
        }
        if (attempt >= RESUME_SEEK_MAX_ATTEMPTS) {
            _resumeSeekSettled = true
            AudioDirectProbe.recordPlaybackFailure(
                "续播 seek 未落地：target=${targetMs}ms pos=${pos}ms attempts=$attempt",
            )
            return
        }
        _applyResumeSeek()
    }

    private var _session: MediaSession? = null
    private var _cache: Cache? = null
    private var _cachedSourceFactory: DefaultMediaSourceFactory? = null
    private var _plainSourceFactory: DefaultMediaSourceFactory? = null

    /**
     * 整首缓存任务。换歌即取消 —— 否则「听到一半切走」时旧任务还在拉剩下的几十 MB，
     * 新歌的首包会被挤到超时（与 KKBOX 会话的前后台规则同一个理由）。
     */
    private var _fullCacheJob: Job? = null

    /**
     * 整首缓存的代际号。每换一首 +1，旧任务在进度回调里发现代际变了就自己抛出去 ——
     * 这一版 `CacheWriter` 没有 `isCanceled` 参数，`Job.cancel()` 打断不了阻塞读。
     */
    @Volatile private var _warmGeneration: Int = 0

    /**
     * 等待起跑的整首缓存地址（见 [startPendingWarmIfReady]）。
     *
     * 不在 `setSource` 里直接开跑：预热和播放抢同一条带宽，Hi-Res 一首 345 MB、
     * 播放只要 1.15 MB/s，预热全速拉会把播放挤到 BUFFERING，表现为「大文件断断续续」。
     * 等播放真正稳住（READY + 在播 + 缓冲领先 5 秒）再开始。
     */
    @Volatile private var _pendingWarmUrl: String? = null

    /** 本机 Apple HLS 失败不重试，见 [LocalFailFastPolicy] */
    private val _loadErrorPolicy: LoadErrorHandlingPolicy = LocalFailFastPolicy()
    private var _audioOutput: AudioOutputController? = null
    private var _renderersFactory: AnnieRenderersFactory? = null
    private var _progressJob: Job? = null
    private var _serviceStarted: Boolean = false
    private var _lastAudioInfo: AudioInfo? = null

    /**
     * 当前这一首走"我方全链路"（本地 ffmpeg 解封装）的**路径**；空 = 不是全链路源。
     *
     * 存在的唯一理由：sink 要拿它去问 `FfmpegOutputStream` "这条源声明的传输方式是什么"。
     * 以前 sink 回读全局 `_dsdTransport`，与建源那一侧各读一次 —— 翻面就是 DoP→PCM 没声。
     */
    private var _fullChainPath: String = ""

    /**
     * 上一次下发过的「会改变 AudioSink configure」的计划指纹；变了就重建一次音源。
     *
     * **只由 `_setSource`（建源完成处）写，只在设置/蓝牙事件里比。**
     * 绝不能在 `onSinkRouting` 里比 —— 指纹含实测量，而实测量正是 configure 的产物，
     * 拿它比就是"自己跟自己比"，见那里的注释。
     */
    private var _lastPlanKey: String? = null

    /**
     * **实际生效的通路**（`AaudioSink` 上报的唯一事实源）。`null` = 还没上报过。
     *
     * 与 `MoreSettings.outputPath`（意图）分开：降级只改这里，设置里的选择永远不动 ——
     * 换回支持的设备就自动恢复。
     *
     * 通路 / 降级原因 / 端点位深是**同一件事的三个字段**，所以它们是不可分的一个值。
     * 以前是三个可变字段加一个 `_routingKnown` 布尔，四处各自更新：任意一处漏写就是
     * "界面说独占、实际在 mixer"，或者反过来的假 Bit Perfect。
     *
     * 没上报时 `_effectivePath` 是 `systemMix`，**不能拿它定计划**；上报之后必须用它 ——
     * 否则"意图 USB 独占、实际 AudioTrack"会按独占算计划（`sampleRate = 0`，我方不转），
     * 那次 88.2→48 就落回系统 mixer，而用户看到的是一个没有「我方 SRC」的输出胶囊。
     */
    private data class SinkRouting(
        val path: SinkKind,
        val notice: DowngradeNotice?,
        val depth: Int,
    )

    private var _routing: SinkRouting? = null

    /**
     * 上一次上报是否已经**作废**（用户刚改了通路）。
     *
     * 注意它不是"通路"的第四个副本 —— 它记的是**可信度**，不是值：通路本身只有
     * `_routing` 一份。作废时保留旧值用于展示，但决策一律回到意图
     * （`if (_routingKnown) _effectivePath else 意图`）。
     */
    private var _routingStale = false

    private val _effectivePath: SinkKind get() = _routing?.path ?: SinkKind.audioTrack

    @Volatile
    private var _plannedSinkPath: SinkKind = SinkKind.audioTrack
    private val _downgradeNotice: DowngradeNotice? get() = _routing?.notice

    /** 独占端点上真正跑的位深（USB 的 subslot / AAudio 的 HAL 格式）；0 = 未知 */
    private val _streamDepth: Int get() = _routing?.depth ?: 0

    /** 实测通路是否**可信**（上报过、且没被"用户改了通路"作废） */
    private val _routingKnown: Boolean get() = _routing != null && !_routingStale

    /** 用户改了通路 → 上一次实测结论作废，下一次上报即便数值相同也要重新走一遍 */
    private fun _invalidateRouting() {
        if (_routing != null) _routingStale = true
    }

    /** 排查用：上一次写进 `audio-status.txt` 的那一行（相同就不重复写，除非换了曲目） */
    private var _lastStatusLine: String? = null

    /** 上一次写状态行时的 `AudioInfo`；换实例 = 换曲目 → 无论内容相同都留一行 */
    private var _lastStatusInfo: AudioInfo? = null

    /** 状态行的时间戳格式（复用同一个实例，避免每曲新建） */
    private val _statusClock = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    /** 是否已经关掉音频焦点处理（独占生效时为 true） */
    private var _focusSuppressed: Boolean = false

    /**
     * 本曲累计的输出真峰值（线性，1.0 = 满刻度）与超满刻度样本数。
     *
     * 来源是 native 侧「取走即清零」的统计，这里做累加 —— 所以**切歌时要清**，
     * 否则上一首的峰值会挂在下一首头上（界面会指错人）。
     */
    private var _sessionPeak: Double = 0.0
    private var _sessionClips: Long = 0L

    /**
     * 把本曲累计的真峰值写进「日志诊断」，然后清零。
     *
     * 为什么不放常驻 UI：绝大多数曲目峰值都 ≤ 1.0，常驻一行等于永远显示"正常"，
     * 白白占位置。真出问题时它是**事件**，而「日志诊断」这页的定位正是"刚才发生过什么"。
     * 文案里带**需要的余量 dB**，因为 `1.003` 这种线性值没人读得出来。
     */
    private fun _flushPeakLog() {
        if (_sessionPeak <= 0.0) return
        val db = 20.0 * log10(_sessionPeak.coerceAtLeast(1e-12))
        val text = if (_sessionClips > 0) {
            "输出峰值 %.3f（%+.2f dB）· 削波 %d 样本".format(_sessionPeak, db, _sessionClips)
        } else {
            "输出峰值 %.3f（%+.2f dB）· 未削波".format(_sessionPeak, db)
        }
        RuntimeLog.record("峰值", text)
        _sessionPeak = 0.0
        _sessionClips = 0L
    }

    /**
     * 上一次 bit-perfect 请求的结果（`canAttemptBitPerfect()` 为 false 时恒 false）。
     *
     * 为什么必须**先问再定计划**：拒绝时的退路是"由我方转到系统实率"，而那个目标率
     * 决定 AudioTrack 的采样率 —— 建轨之后才知道就被动了（改不了，只能等下一首）。
     */
    private var _bitPerfectGranted: Boolean = false

    /** 上一次请求用的「设备:率:位深」。相同就不重复请求（避免每次策略刷新都过一遍 binder）。 */
    private var _bitPerfectKey: String = ""

    /** 是否已经下发过 mixer 属性（用于"只在失去资格时清理一次"，不每帧调用 clear） */
    private var _bitPerfectRequested: Boolean = false
// ── 应用层队列状态（对齐 Dart 字段） ──
    private var _queue: List<Map<String, String>> = emptyList()
// 已解析直链缓存，按「歌曲身份」（`平台:id:音质`）而非队列下标——

// 重进列表/来回切歌时不必重复解析（原按下标实现会被 setQueue 清空）
    private val _urlCache: MutableMap<String, String> = mutableMapOf()
    // 解析出的直链 -> 稳定缓存 key（`平台:id:音质`）。Media3 默认拿 URL 当 key，
    // 插件直链一变就 miss；按歌曲身份映射后，同一首歌无论直链怎么变都命中同一份缓存
    private val _cacheKeyByUrl: MutableMap<String, String> = ConcurrentHashMap()
    private val _pendingPrefetch: MutableSet<Int> = mutableSetOf()
    private var _appLoopMode: LoopMode = LoopMode.sequential

    /**
     * 随机模式下**提前定好的下一首**（-1 = 还没定）。
     *
     * 之前 `skipNext()` 和 `_handleEnded()` 各自现掷一次随机，于是：① 预取按顺序盲猜、
     * 命中率 1/(n-1)；② 界面点「下一首」时 `PlayerState` 又掷一次，可能和实际播的不是同一首。
     * 现在每次起播成功就定好下一首并存下来，预取 / 自动切 / 下一首按钮统一认它。
     */
    private var _shuffleNextIndex: Int = -1

    private var _preloadBaseIndex: Int = 0
    private var _currentAbsIndex: Int = 0
    private var _generation: Int = 0
    private var _playRequestId: Int = 0
    private var _consecutiveFailures: Int = 0

    /** 当前这次 `_playAbsIndex` 是否已做过一次「清缓存 / 强刷解析」重试（对齐 Dart catch 分支） */
    private var _retriedCurrent: Boolean = false

    /**
     * 本实例是否仍是「当前活跃引擎」。Activity 重建时，新实例会接管进程内既有 ExoPlayer；
     * 此时旧实例被置为 false，其监听/协程全部短路，避免两个 backend 争抢同一个 player。
     */
    @Volatile
    private var _active: Boolean = true

    // ── 生命周期 ──

    /**
     * 初始化引擎（对齐 Dart `setup()`）：
     * 1. 建缓存 + 建 ExoPlayer + 监听
     * 2. 建 MediaSession（通知 / 锁屏 / 线控）并注册进进程级 holder 供服务使用
     * 3. 建采样率/位深直通控制器（bit-perfect groundwork）
     * 4. 启动进度事件循环、推送初始 isPlaying=false
     * 5. Android 13+ 通知权限检查（运行时申请归 Activity，见 [_requestNotificationPermission]）
     */
    override suspend fun setup() {
        withContext(Dispatchers.Main.immediate) {
            if (_player != null) return@withContext

            // 诊断落点：整条 DSD 链的时间序列（定位完删掉）
            com.annieplayer.annie_music.kt.core.audio.dap.DapTrace.init(_context.filesDir)
            // 诊断：native DSD 线格式覆盖（`files/dsd-pack.txt`，定位完删掉）
            com.annieplayer.annie_music.kt.core.audio.dap.DapPackOverride.load(
                java.io.File(_context.filesDir, "dsd-pack.txt"),
            )

            // ⓪ 进程级复用：Activity 重建（旋转 / 深色模式 / 分屏 / 从通知栏返回被回收的界面）
            //    会让本方法在**同一进程**里被再次调用。`SimpleCache` 对同一目录在进程内只允许一个
            //    实例（第二个实例直接抛 IllegalStateException 崩溃），且旧 ExoPlayer 可能仍在播放。
            //    因此这里直接接管既有引擎，避免「二次 SimpleCache 崩溃」与「双引擎 / 控制失效」。
            val existingPlayer = AnnieAudioHolder.player
            val existingSession = AnnieAudioHolder.session
            if (existingPlayer != null && existingSession != null) {
                adoptEngine(existingPlayer, existingSession)
                return@withContext
            }

            try {
            // 1) 磁盘缓存：进程级单例（等价 Dart `getTemporaryDirectory`/audio_cache，交给 Media3 自管）
            val cache = sharedCache(_context)
            _cache = cache

            // 共享 OkHttp（连接池 + HTTP/2）：Apple 每首歌的 CDN 主机都可能不同，
            // 用 DefaultHttpDataSource 每次都要重开 TLS；走 globalOkHttp 直接复用连接。
            val httpFactory = OkHttpDataSource.Factory(globalOkHttp)
                .setUserAgent(_userAgent)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(::_cacheKeyOfSpec)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            _cachedSourceFactory = DefaultMediaSourceFactory(cacheFactory)
                .setLoadErrorHandlingPolicy(_loadErrorPolicy)
            _plainSourceFactory = DefaultMediaSourceFactory(upstream)
                .setLoadErrorHandlingPolicy(_loadErrorPolicy)
            // 2) ExoPlayer（音频焦点 + 拔耳机暂停，等价 just_audio + audio_session 行为）
            //    RenderersFactory 负责 float 输出 + 调音/重采样处理器链（方案阶段 1）
            val renderersFactory = AnnieRenderersFactory(_context)
            _renderersFactory = renderersFactory
            _bindRendererCallbacks(renderersFactory)
            val player = _buildPlayer(renderersFactory)
            player.addListener(AnniePlayerListener())
            _player = player

            // 3) MediaSession：服务 / 通知 / 锁屏 / 耳机按钮的宿主
            val sessionActivity = PendingIntent.getActivity(
                _context,
                0,
                Intent(_context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 会话拿到的是「补声明了上一首/下一首」的包装播放器：通知栏 / 锁屏 / 蓝牙
            // 才会显示切歌键；应用内其余逻辑仍使用真实 player（应用侧只用 _player）。
            val session = MediaSession.Builder(_context, CommandAdvertisedPlayer(player))
                .setSessionActivity(sessionActivity)
                .setCallback(AnnieSessionCallback())
                .build()
            _session = session
            AnnieAudioHolder.player = player
            AnnieAudioHolder.session = session
            AnnieAudioHolder.cache = cache
            AnnieAudioHolder.cachedSourceFactory = _cachedSourceFactory
            AnnieAudioHolder.plainSourceFactory = _plainSourceFactory
            AnnieAudioHolder.renderersFactory = renderersFactory
            // 服务实例可能被系统回收（例如划掉任务且未在播放时 stopSelf）；一旦销毁需复位
            // _serviceStarted，否则后续播放不会再拉起前台服务/媒体通知。
            AnnieAudioHolder.onServiceStopped = { _serviceStarted = false }
            // 供后续接管的新实例把本实例「下线」，避免两个 backend 争抢同一个 player
            AnnieAudioHolder.onDeactivate = { deactivate() }

            // 4) bit-perfect groundwork
            _audioOutput = AudioOutputController(_context)
            _audioOutput?.onDevicesChanged = change@{
                // USB 独占 claim/release 接口会让内核驱动回绑/摘除，系统因此看到设备增删。
                // 那是我方动作的回声，不是我方要响应的事件 —— 不挡住就会
                // 「重建 → 设备事件 → 再重建」自激，换率切歌时正好踩两次。
                // 但**不能整条丢弃**：拔插很快时真事件会落在窗口内，吞掉它就得等切歌才恢复。
                if (UsbAudioDirect.selfEcho()) {
                    RuntimeLog.record("路由", "自方 USB 动作引起的设备变化，稍后重新评估")
                    _mainHandler.postDelayed({
                        runCatching {
                            if (MoreSettings.usbExclusive) {
                                _retryUsbAttach()
                            } else {
                                _applyOutputPolicy(_lastAudioInfo)
                                _reloadCurrentSource()
                            }
                        }
                    }, DEVICE_REEVALUATE_MS)
                    return@change
                }
                // **内置 DAC 的 DIRECT 轨开关同样是自方回声。**
                //
                // 它一开一关，系统就看到一个输出设备增删，于是这里会把
                // `AudioDirectProbe` / `SystemSinkProbe` 全部作废、跑一遍二十来条
                // 试探轨（实测 logcat 里那一刻有一批 `PlayerBase::~PlayerBase`），
                // 再 `_reloadCurrentSource()` 两次 —— 正好把用户刚点的那首歌拆掉。
                // 症状：DSD 放完切普通歌，界面切过去了但一个字节都不出声。
                if (dapSelfEcho()) {
                    RuntimeLog.record("路由", "自方内置 DAC 直通流引起的设备变化，忽略")
                    return@change
                }
                // **统一的自方回声护栏（探测矩阵）。**
                //
                // `_warmDirectCapabilities` 一次会开关二十来条试探轨，每一条都让系统看到
                // 设备增删 → 又回调到这里 → 再重探 → 再产生设备事件。实测表现是
                // **启动即卡死**：logcat 里两个线程不停 `PlayerBase::PlayerBase` / `~PlayerBase`。
                // 判据在 `AudioSelfAction`，所有“我方动了设备”的地方都留了时间戳。
                if (AudioSelfAction.recent()) {
                    RuntimeLog.record("路由", "自方探测动作引起的设备变化，忽略")
                    return@change
                }
                BluetoothCodec.refresh()
                // 拔插会换设备：DAC 能力（raw alt / 支持的帧率）作废，下次配置重读。
                // 不清的话，换一台没有 raw 通道的 DAC 之后 Native 仍按旧的 caps 判定。
                _usbDacCaps = null
                _usbDacCapsDeviceId = -1
                // 换设备 = 换能力：上一台接不住的直出方式，这一台可能接得住
                _failedTransports.clear()
                RuntimeLog.record(
                    "路由",
                    "输出设备变化 → ${_audioOutput?.currentRoute()}，" +
                        "输出能力作废，700ms 后重探",
                )
                // 路由变了 → 输出能力要重新探（蓝牙占用时的结果不可信）。
                // 延迟一点再探：设备切换瞬间音频路由还没稳定，立刻建轨容易失败。
                AudioDirectProbe.invalidate()
                // "这个率建不建得出来"同样按设备算：换了 DAC 之后旧结论全部作废。
                // 不清的话会出现「插着 384k 小尾巴，却沿用上一台设备"384k 建不出来"的缓存」。
                SystemSinkProbe.invalidate()
                _scope.launch {
                    kotlinx.coroutines.delay(700)
                    _warmDirectCapabilities()
                }
                _applyOutputPolicy(_lastAudioInfo)
                // 设备/采样率能力变了，输出格式需要重新 configure 才生效
                _reloadCurrentSource()
                // USB 插上的第一拍**不能立即重试**：`AudioManager.getDevices()`（本回调的触发源）
                // 与 `UsbManager.deviceList`（`UacEngine.open` 的数据源）是两个服务，更新有先后差。
                // 立刻重建会读到"没有 USB 音频设备"然后落回系统通路 —— 表现就是
                // 「插上了、进度保留了，但要等下一首才进独占」。
                if (MoreSettings.usbExclusive) {
                    _mainHandler.postDelayed({ runCatching { _retryUsbAttach() } }, USB_ATTACH_RETRY_MS)
                }
                // AAudio 独占的可用性是**按路由**探的（插上 3.5 才可能为 true），
                // 而上面那次 `_applyOutputPolicy` 跑在探测之前 —— 探完必须重新定计划，
                // 否则插上耳机的第一首永远赶不上 Bit Perfect 那一级。
                if (MoreSettings.aaudioExclusive) {
                    AudioDirectProbe.warmUpAaudioAsync {
                        _applyOutputPolicy(_lastAudioInfo)
                        _reloadCurrentSource()
                    }
                }
            }
            // codec 协商结果（率 / 位深）是蓝牙这条链的唯一真相来源，但它**比路由事件来得晚**：
            // `onDevicesChanged` 当场 `_applyOutputPolicy` + `_reloadCurrentSource`，而那时
            // `BluetoothCodec.refresh()` 还没回来（它带 300/900/2000ms 的重试）。协商结果落地时
            // 若不重建音源，AudioTrack 仍是旧规格 —— `OutputPlan` 的蓝牙目标率明明已经从 0 变成
            // 48000，「接管 SRC」却没发生，于是那一次转换落回系统 mixer。
            BluetoothCodec.onInfoChanged = { _onBluetoothCodecChanged() }
            _bindOutputStream()
            AnnieAudioHolder.audioOutput = _audioOutput
            // 冷启动走的是这个分支（新建引擎）——探测也必须在这里起，
            // 之前只加在"复用旧引擎"分支，导致能力表永远停在"未探测"。
            _warmDirectCapabilities()
            _observeAudioSettings()
            _applyOutputPolicy(null)

            // 5) 进度事件循环 + 初始状态
            _startProgressLoop()
            _emit(mapOf("isPlaying" to false))

            // 6) Android 13+ POST_NOTIFICATIONS
            _requestNotificationPermission()
            } catch (t: Throwable) {
                // 兜底：setup 阶段任何异常都不应让 App 闪退；记日志后由后续操作重试
                Log.e(_tag, "[audio] setup failed", t)
            }
        }
    }

    /**
     * 把本实例的状态回调挂到 `renderersFactory` 上。
     *
     * **`setup()` 与 `adoptEngine()` 都必须调**：`adoptEngine` 复用进程里既有的 factory，
     * 而这些回调是它身上的可变字段。不重绑的话，回调仍指向**已经 deactivate 的旧实例**——
     * 新的 backend 永远收不到 `onSinkRouting`，`_effectivePath` 停在 `systemMix`，
     * 于是「USB 流明明在响、DSD 页却说需要 USB 直连」。旋转一次屏幕就能触发。
     */
    private fun _bindRendererCallbacks(renderersFactory: AnnieRenderersFactory) {
        // 输出通路读取器：只用于状态展示（哪条通路在响）。
        renderersFactory.routeProvider = { _audioOutput?.currentRoute() }
        renderersFactory.outputDeviceIdProvider = { _audioOutput?.currentOutputDevice()?.id ?: 0 }
        renderersFactory.policyProvider = { MoreSettings.playbackPolicy() }
        // AAudio 独占中途失败 → 回到主线程重建音源。
        // 那时该采样率已被标成不可独占，所以下一次 configure 会选系统引擎（不会死循环）。
        renderersFactory.onAaudioFailure = { reason, tag ->
            AudioDirectProbe.recordPlaybackFailure("AAudio 独占中断：$reason")
            // 记住这次失败：设备接不住某个档位的直接通路时，不记就会「每首歌都白试一次」，
            // 表现为每首开头先静音/爆一下再降级。换设备时清空（见 onDevicesChanged）。
            //
            // **键必须用 sink 在开流时快照的标签**，不是这里现读 `_dsdTransport`：
            // 失败常常发生在用户已经切过模式之后，现读会把"Native 失败"记成"dop 失败"，
            // 于是用户自己选的 DoP 下次被当成已失败直接降级 → 表现为"切完没声音"。
            if (tag.isNotEmpty()) {
                _failedTransports.add(tag)
            } else if (_dsdTransport.isRaw) {
                _failedTransports.add(_transportKey(_dsdTransport, _activeDsdLevel))
            }
            _mainHandler.post { runCatching { _reloadCurrentSource() } }
        }
        // **本次 configure 的全部意图，一个值。** sink 在 `configure()` 开头取一次快照，
        // 之后整条 configure 只读那份快照。
        //
        // 以前这里是三个独立 lambda（传输标签 / 是否直出 / 是否 Native），各读各的全局量，
        // 而 sink 又在不同时刻读了好几次 —— 两次读之间主线程翻面，同一首歌的两半就会走
        // 不同的 alt：PCM 字节进 raw alt 时发得出去、进度照走、**完全没有声音**。
        renderersFactory.outputRequest = {
            // **首选源自己声明的传输方式**，而不是回读全局 `_dsdTransport`。
            //
            // extractor 的 `init()` 必然发生在渲染器 `configure()` 之前（否则连 Format 都没有），
            // 所以走到这里时声明已经在 `FfmpegOutputStream` 里 —— 建源与配流读的是**同一个值**，
            // 那个"两读两写"的时间窗从结构上消失。流媒体源不是全链路，返回 null，退回全局量
            // （那时它恒为 pcm，`_setSource` 开头已归零）。
            val declared = FfmpegOutputStream.transportOf(_fullChainPath)
            val transport = declared ?: _dsdTransport
            val plan = _currentPlan()
            OutputRequest(
                transport = transport,
                // Native 才走 raw alt；DoP 的帧本身是 24 bit PCM，必须留在 PCM alt
                rawAlt = transport == DsdTransport.native,
                // DoP 与 Native 送的都是帧，不能被 DSP 碰、也不能过 mixer
                rawFrames = transport.isRaw,
                transportTag = _transportKey(transport, _activeDsdLevel),
                deliveredDepth = plan.deliveredDepth,
                aaudioExclusive = plan.aaudioExclusive,
                dopFrames = transport == DsdTransport.dop,
                aaudioDopExclusive = _aaudioDopExclusive(_activeDsdLevel),
            )
        }
        // "用户到底按没按暂停"。`AaudioSink` 那条自愈（数据还在来就 resume）只看 sink 自己的
        // `playRequested`，而那个标志会与播放器失步；这里给它一票否决，否则手动暂停后
        // 管线里剩下的那几块数据会在约一秒后把声音重新拉起来。
        //
        // 读的是**本实例的 volatile 副本**，不是 `_player.playWhenReady`：那个字段跨线程读不可靠
        // （见 `_playWhenReady`）。重绑时先同步一次，`adoptEngine` 复用旧 factory 也不会漏。
        _playWhenReady = _player?.playWhenReady == true
        _playWhenReadyAtMs = android.os.SystemClock.elapsedRealtime()
        renderersFactory.playIntent = { _playWhenReady }
        renderersFactory.playIntentAt = { _playWhenReadyAtMs }
        // DAP 内置 DAC 直通：普通 PCM / DoP 走 DIRECT PCM 端口，raw native 走私有 DSD 容器，
        // 分流依据是**源自己声明的字节形状**（见 `_openDapDirect`）。
        renderersFactory.dapOpener = { format -> _openDapDirect(format) }
        renderersFactory.dapNativeReady = { _dapWorkingNow() != null }
        // 内置 DAC 没开成 → 记这一档失败 + 按 PCM 重建。三种触发（dapOpener 返回 null /
        // open 失败 / raw 帧无消费者）殊途同归：这一首的 native 走不了，
        // 而让 raw 帧留在原地只会变成噪声。
        renderersFactory.onDapOpenFailed = {
            // **按这一首实际用的传输方式记**，不能恒记 `native`：内置 DAC 上 DoP 也走这条路
            // （它要的是 DIRECT **PCM** 端口，HAL 一样可能不认 176.4k）。记错方式会把
            // native 一起否掉，之后连能播的组合都不敢试。
            val failedTransport = FfmpegOutputStream.transportOf(_fullChainPath) ?: _dsdTransport
            val key = _transportKey(failedTransport, _activeDsdLevel)
            // **去重**：`Set.add` 只在"新记一条"时返回 true。
            // 不去重的话，一次失败会换来"重建 → 再 configure → 再失败"的无限循环
            // （实测 `cfg` 冲到 1185、进度条钉在 0）。
            if (_failedTransports.add(key)) {
                _mainHandler.post { runCatching { _reloadCurrentSource() } }
            }
        }
        // raw DSD 帧一律不进处理器链（系统通路也一样）：重采样会把它当 PCM 算
        renderersFactory.rawFrames = { _dsdTransport.isRaw }
        // 实际生效通路：与设置里的「意图」分开记。降级是事实，不能写回设置。
        // 三个字段一次性换成一个值 —— 不存在"改了 path 没改 notice"这种中间态。
        renderersFactory.onSinkRouting = { path, notice, depth ->
            val next = SinkRouting(path, notice, depth)
            // 作废后即便数值与上次相同也要重新走一遍：那时"可信"本身就是新信息，
            // 通路第一次落到 USB 直连要借这一次去读 DAC 能力、重建音源。
            if (next != _routing || _routingStale) {
                _routing = next
                _routingStale = false
                _mainHandler.post {
                    runCatching {
                        // 通路第一次落到 USB 直连时读一次 DAC 能力：DoP / Native 的成立与否
                        // 取决于「有没有 raw alt」「这台设备收不收这个帧率」，不读就只能一律降级。
                        // 读到（或换了设备）就算一次"计划变化"，必须重建 —— transport 是在
                        // `_setSource` 里算死的，不重建的话这一首仍按 PCM 送。
                        val capsChanged = if (path == SinkKind.usbUac) {
                            _refreshUsbDacCaps()
                        } else {
                            false
                        }
                        // **这里不许比「计划指纹」。**
                        //
                        // 指纹里有一项是 DSD 传输方式的判定结论，而那个判定读的
                        // `_pathForDsd()` 又建立在 `_routing`（**本回调正在写的值**）之上：
                        //   - `_setSource` 记录指纹时，这一首还没 configure → `_routing` 还是上一条通路
                        //     → 记下 `…:pcm`；
                        //   - configure 之后本回调算出 `…:native` → 判定"计划变了" → 把**刚刚起播**的源换掉。
                        // 于是每次起播都会自杀一次（实测：起播 400 ms 后必定发生），
                        // 而那次自杀又因为在非 IDLE 上调 `prepare()` 而把播放器永久钉在 BUFFERING。
                        //
                        // 指纹只留给设置 / 蓝牙那两处比 —— 它们由**外部事件**触发，没有这层自指。
                        _applyOutputPolicy(_lastAudioInfo)
                        if (capsChanged) _reloadCurrentSource()
                    }
                }
            }
        }
    }

    /**
     * 读一次 DAC 能力（有没有 raw DSD 通道、两侧各声明了哪些率）。
     *
     * 必须真的去看描述符：`UsbDacCaps` 之前从未被赋值，`decideDsdTransport` 永远拿到
     * `null`，于是 Native 恒降 DoP —— 用户看到的就是「开了独占也选不了 Native」。
     *
     * 按 (输出设备 id) 缓存：`UsbAudioDirect.capabilities` 要开一次 USB 连接再关，
     * 每首歌都做是浪费；设备换了（拔插）由 `onDevicesChanged` 清空缓存。
     */
    private fun _refreshUsbDacCaps(): Boolean {
        val out = _audioOutput ?: return false
        if (_effectivePath != SinkKind.usbUac) {
            // 通路暂时不是 USB **不代表**设备能力变了：DAC 还插着，raw alt 还在。
            //
            // 以前这里把 `_usbDacCaps` 清成 null 并把"清掉了什么"当返回值交给调用方当
            // `capsChanged`——那是两个错叠在一起：
            //
            // 1. `decideDsdTransport` 对 `caps == null` 的处理是"Native 一律降 DoP"
            //    （`DsdDowngradeReason.DeviceUnknown`）。于是任何一次落回系统通路的
            //    configure 之后，**用户再选 Native 也永远只会得到 DoP** —— 表现就是
            //    「暂停一会儿自动跳 DoP」「切 Native 没反应」。
            // 2. 返回值 true 会强制 `_reloadCurrentSource()`，而这次重建又会再 configure
            //    一次，把通路再翻一次 —— 自激环的第二条腿。
            //
            // 清空只该发生在**设备真的换了**（`onDevicesChanged` / 拔插）时，那两处已经做了。
            return false
        }
        val deviceId = out.currentOutputDevice()?.id ?: 0
        if (deviceId == _usbDacCapsDeviceId && _usbDacCaps != null) return false
        val dev = UsbAudioDirect.audioDevices(_context)
            .firstOrNull { UsbAudioDirect.hasPermission(_context, it) } ?: return false
        val caps = UsbAudioDirect.capabilities(_context, dev)
        val changed = caps != _usbDacCaps
        _usbDacCaps = caps
        _usbDacCapsDeviceId = deviceId
        return changed
    }

    /**
     * 建一条 ExoPlayer。
     *
     * **冷启动与引擎重建必须共用这一处**：实测里 100% 成功的起播路径就是
     * 「新实例 → items=0 → state=IDLE → `setMediaSource` + `prepare` → `O open` / `C configure`」，
     * 而任何"复用同一个实例"的换源都出现过"源换了、`prepare` 被吞、永远 state=2"。
     *
     * 缓冲/起播门槛见 [_loadControlMinBufferMs] 那一组常量；wake lock 的理由见 `setup()`。
     */
    private fun _buildPlayer(renderersFactory: AnnieRenderersFactory): ExoPlayer {
        val sourceFactory = _cachedSourceFactory
            ?: _plainSourceFactory
            ?: DefaultMediaSourceFactory(_context)
        return ExoPlayer.Builder(_context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(_buildLoadControl())
            .setAudioAttributes(_mediaAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    // ── 换通路 / 换计划：唯一入口决定的"落地动作" ──

    /** 最近一次建源记录的「用户选的通路」；用来判"这次变更是不是通路变更" */
    private var _lastAppliedPathKey: String? = null

    /** 引擎重建的次数（诊断；`dapTrace` 的 `E` 行带它） */
    private var _engineRebuilds: Int = 0

    /** 这个时刻之前不允许再重建（防"重建 → 仍无 configure → 再重建"的自激） */
    private var _rebuildBlockedUntil: Long = 0L

    /** 等在途的出口校验任务（换源后 1.2 s 内没看到 configure 就升级） */
    private var _configureVerifyTask: Runnable? = null

    /** 等在途的数据流校验任务（`play()` 之后 1.5 s 内一个样本都没进出口就升级） */
    private var _dataFlowVerifyTask: Runnable? = null

    /**
     * 引擎是否**不可信**：sink 侧报过"控制调用超时"。
     *
     * 那种情况下播放线程很可能已经卡在 native `AudioTrack` 调用里，`setMediaSource` /
     * `prepare` / `playWhenReady` 全部排在这条线程后面 —— 原地换源不是"慢"，是**永远不会生效**。
     */
    private fun _engineSuspect(): Boolean = AnnieAudioSink.controlStallRecent()

    /**
     * 换源之后**验证出口真的重配了**。
     *
     * 为什么必须验证：`setMediaSource` + `prepare` 不抛异常只证明"调用进去了"。
     * 实测有两条路径是"调用成功、出口从没重配"：播放器停在 BUFFERING、`durMs=UNSET`、
     * 进度条不动、界面上一切正常就是没声。`configureCount` 是唯一能把这两件事分开的证据。
     */
    private fun _armConfigureVerify(reason: String) {
        _configureVerifyTask?.let { _mainHandler.removeCallbacks(it) }
        val before = AnnieAudioSink.configureCount
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var waitExtensions = 0
        var task: Runnable? = null
        task = Runnable {
            if (_configureVerifyTask !== task) return@Runnable
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            when (
                configureVerdict(
                    elapsedMs = elapsed,
                    timeoutMs = EngineHealth.CONFIGURE_VERIFY_MS,
                    configureCountBefore = before,
                    configureCountNow = AnnieAudioSink.configureCount,
                )
            ) {
                ConfigureVerdict.confirmed -> {
                    _configureVerifyTask = null
                    com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("E") {
                        "verify-ok $reason ${elapsed}ms cfg=$before→${AnnieAudioSink.configureCount}"
                    }
                }
                ConfigureVerdict.pending -> {
                    // 每 100 ms 复查一次，直到超时
                    _mainHandler.postDelayed(task!!, 100L)
                }
                ConfigureVerdict.escalate -> {
                    // **加载还在进行时不算卡死**：慢网下首包超过 1.2 s 是常态，
                    // 那种情况重配本来就要等数据。给它两次续期（共 +2.4 s），
                    // 只有"没 configure、也不在加载"才升级成换引擎。
                    if (_player?.isLoading == true && waitExtensions < 2) {
                        waitExtensions++
                        _mainHandler.postDelayed(task!!, EngineHealth.CONFIGURE_VERIFY_MS)
                        return@Runnable
                    }
                    _configureVerifyTask = null
                    _escalateToRebuild("$reason：${elapsed}ms 内没有新的 configure")
                }
            }
        }
        _configureVerifyTask = task
        _mainHandler.postDelayed(task, EngineHealth.CONFIGURE_VERIFY_MS)
    }

    /**
     * `play()` 之后验证**真的有样本进出口**。
     *
     * `configureCount` 只能证明出口被配置过；能不能出声要看 `handleBuffer` 有没有被调。
     * 暂停 → 恢复这条路上实测有"configure 早就发生过、恢复时一个字节都不来"的形态。
     */
    private fun _armDataFlowVerify(reason: String, confirmRound: Int = 0) {
        _dataFlowVerifyTask?.let { _mainHandler.removeCallbacks(it) }
        val before = AnnieAudioSink.bufferCalls
        var task: Runnable? = null
        task = Runnable {
            if (_dataFlowVerifyTask !== task) return@Runnable
            _dataFlowVerifyTask = null
            val p = _player ?: return@Runnable
            if (!p.playWhenReady || p.playbackState == Player.STATE_ENDED) return@Runnable
            if (p.isLoading) return@Runnable
            if (dataFlowStalled(before, AnnieAudioSink.bufferCalls, AnnieAudioSink.outletAlive)) {
                _escalateToRebuild(
                    if (!AnnieAudioSink.outletAlive) {
                        "$reason：出口已不存在"
                    } else {
                        "$reason：${EngineHealth.DATA_FLOW_VERIFY_MS}ms 内没有样本到达出口"
                    },
                )
                return@Runnable
            }
            // 一次"涨了"证明不了还在流：实测有过"写十几个缓冲后彻底停住"的形态，
            // 那一瞬间 `bufferCalls` 确实涨了，于是旧的单段校验判成健康、再没人管。
            if (confirmRound < 1) _armDataFlowVerify(reason, confirmRound + 1)
        }
        _dataFlowVerifyTask = task
        _mainHandler.postDelayed(task, EngineHealth.DATA_FLOW_VERIFY_MS)
    }

    private fun _cancelVerifyTasks() {
        _configureVerifyTask?.let { _mainHandler.removeCallbacks(it) }
        _configureVerifyTask = null
        _dataFlowVerifyTask?.let { _mainHandler.removeCallbacks(it) }
        _dataFlowVerifyTask = null
    }

    /**
     * 校验失败 → 升级为换引擎（有保护窗口：同一轮里不会连续重建）。
     */
    private fun _escalateToRebuild(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < _rebuildBlockedUntil) {
            RuntimeLog.record("音频", "换源仍未生效（$reason），但刚重建过引擎，先不再重建")
            return
        }
        RuntimeLog.record("音频", "换源未生效：$reason → 重建播放引擎")
        _rebuildEngine(reason)
    }

    /**
     * 换一条新引擎 —— 与 App 冷启动完全相同的构建路径。
     *
     * 这是**唯一**能救回"播放线程已经卡在 native 控制调用里"的手段：卡住的线程杀不掉，
     * 它持有的 ExoPlayer 也就再不会处理任何消息（实测 trace：`L pause` 之后 100 秒，
     * resume 的 `L play`、换源的 `O`/`C` 一条都没有）。新引擎有自己的播放线程、渲染器与
     * `AnnieAudioSink`，所以下一次 `setMediaSource` + `prepare` 一定走得到 `configure`。
     *
     * 复用/不重建的东西：磁盘缓存（`SimpleCache` 进程内只允许一个实例）、SourceFactory、
     * RenderersFactory（调音/重采样参数在它身上）、MediaSession（`setPlayer` 重绑）。
     *
     * @param playAfter 新引擎建好之后要不要播；`null` = 沿用旧引擎的 `playWhenReady`。
     *   "用户在暂停状态下点了播放"这条路上，旧引擎的 `playWhenReady` 还是 false，
     *   沿用它会变成"点一次播放只重建、要再点一次才出声"。
     */
    private fun _rebuildEngine(reason: String, playAfter: Boolean? = null) {
        val old = _player ?: return
        val factory = _renderersFactory ?: return
        val item = _queue.getOrNull(_currentAbsIndex)
        val url = item?.let { _urlCache[_cacheKeyOf(it)] }
        // 位置与意图必须在丢弃旧引擎**之前**读：IDLE 上 `currentPosition` 恒 0
        val position = runCatching { old.currentPosition }.getOrDefault(0L)
        val wasPlaying = runCatching { old.playWhenReady }.getOrDefault(false)
        val oldState = runCatching { old.playbackState }.getOrDefault(-1)
        // `releaseDapNow` 会在新引擎 `buildAudioSink` 时被覆盖成新 sink 的，所以先摘下旧的
        val oldReleaseDap = factory.releaseDapNow

        _engineRebuilds++
        _rebuildBlockedUntil = android.os.SystemClock.elapsedRealtime() + EngineHealth.REBUILD_MIN_INTERVAL_MS
        _cancelVerifyTasks()
        com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("E") {
            "engine-rebuild #$_engineRebuilds state=$oldState posMs=$position pwr=$wasPlaying " +
                "item=${item?.get("title")} reason=$reason"
        }

        // ① 先把旧引擎摘下来：`_setSource` / 校验任务 / 渲染回调全部立刻短路
        _player = null
        AnnieAudioHolder.player = null

        // ② 新引擎（与冷启动同一处构建）
        val player = _buildPlayer(factory)
        player.addListener(AnniePlayerListener())
        // ③ MediaSession 重绑（1.4.1 有 `setPlayer`）：通知栏 / 锁屏 / 蓝牙控制不断线。
        //    重绑失败时让会话继续指向旧 player 就等于控制器全哑，所以失败要留一条日志。
        val rebound = runCatching { _session?.setPlayer(CommandAdvertisedPlayer(player)) }.isSuccess
        if (!rebound) RuntimeLog.record("音频", "MediaSession 重绑失败：通知栏 / 线控可能失效")
        _player = player
        AnnieAudioHolder.player = player
        _applyRepeatMode()

        // ④ 旧引擎收尾：先让它自己的 sink 拆掉内置 DAC 直通流（否则厂商 HAL 停在 DSD 模式），
        //    再 `release()`。`release()` 只往它的播放线程投一条 MSG_RELEASE（不 join、不等待），
        //    所以即使那条线程已经卡死，这里也不会被它拖住 —— 代价是它不会再被回收。
        runCatching { oldReleaseDap?.invoke() }
        runCatching { old.release() }
        // 新引擎是新的播放线程，之前那次"控制调用超时"的怀疑到此为止
        AnnieAudioSink.clearControlStall()

        _applyOutputPolicy(_lastAudioInfo)
        _syncOutputVolume()

        if (item != null && url != null) {
            val resumeTarget = if (!_resumeSeekSettled) _resumeSeekMs else 0L
            if (_setSource(url, item, resumeTarget)) {
                val seekToMs = if (resumeTarget > 0L) 0L else position
                if (seekToMs > 0L) runCatching { player.seekTo(seekToMs) }
                val wantPlay = playAfter ?: wasPlaying
                if (wantPlay) {
                    runCatching { player.play() }
                    _armDataFlowVerify("重建引擎后恢复播放")
                }
            } else {
                // 建源同步失败：走一次完整起播，它会自己重新解析
                _scope.launch { _playAbsIndex(_currentAbsIndex) }
            }
        } else {
            // 没有可恢复的直链（或队列已经空了）：走一次完整起播，它会重新解析
            RuntimeLog.record("音频", "重建引擎后没有可用直链，改走完整起播：$reason")
            _scope.launch { _playAbsIndex(_currentAbsIndex) }
        }
    }


    /**
     * 接管进程内既有引擎（Activity 重建场景）：复用同一个 ExoPlayer / MediaSession / 磁盘缓存，
     * 并把上一实例「下线」。这样既不二次创建 `SimpleCache`（会抛异常崩溃），也不产生双引擎。
     */
    private fun adoptEngine(player: ExoPlayer, session: MediaSession) {
        Log.w(_tag, "[audio] adopt existing engine (activity recreated)")
        // 先让旧实例停止其回调与进度协程
        AnnieAudioHolder.onDeactivate?.invoke()
        _player = player
        _session = session
        _cache = AnnieAudioHolder.cache ?: sharedCache(_context)
        _renderersFactory = AnnieAudioHolder.renderersFactory
        _audioOutput = AnnieAudioHolder.audioOutput ?: AudioOutputController(_context)
        _audioOutput?.onDevicesChanged = {
            BluetoothCodec.refresh()
            // 拔插会换设备：DAC 能力作废，下次配置重新读
            _usbDacCaps = null
            _usbDacCapsDeviceId = -1
            _failedTransports.clear()
            // 不在这里 invalidate：能力探测的结论取决于 HAL / 设备，不随路由切换变。
            // 之前每次都清缓存 → 探测永远跑不完 → 一直显示"检测中"。
            _applyOutputPolicy(_lastAudioInfo)
            _reloadCurrentSource()
        }
        // 接管路径同样要接 codec 变化 —— `onInfoChanged` 是 `BluetoothCodec` 上的单槽位，
        // 不重挂就还指向已 `deactivate()` 的旧实例（表现为协商率变了、界面与音源都不动）
        BluetoothCodec.onInfoChanged = { _onBluetoothCodecChanged() }
        // **必须重绑**：factory 是复用的，那些回调还指向已 deactivate 的旧实例，
        // 不重绑新实例就永远收不到 onSinkRouting（`_effectivePath` 卡在 systemMix）
        _renderersFactory?.let { _bindRendererCallbacks(it) }
        _bindOutputStream()
        AnnieAudioHolder.audioOutput = _audioOutput
        _warmDirectCapabilities()

        // 复用旧实例的数据源工厂；若缺失（理论上不会）则以共享缓存重建
        if (AnnieAudioHolder.cachedSourceFactory == null) {
            val httpFactory = OkHttpDataSource.Factory(globalOkHttp)
                .setUserAgent(_userAgent)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            AnnieAudioHolder.cachedSourceFactory = DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(_cache ?: sharedCache(_context))
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(::_cacheKeyOfSpec)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            )
            AnnieAudioHolder.plainSourceFactory = DefaultMediaSourceFactory(upstream)
        }
        _cachedSourceFactory = AnnieAudioHolder.cachedSourceFactory
        _plainSourceFactory = AnnieAudioHolder.plainSourceFactory
        player.addListener(AnniePlayerListener())
        AnnieAudioHolder.onServiceStopped = { _serviceStarted = false }
        AnnieAudioHolder.onDeactivate = { deactivate() }
        _startProgressLoop()
        // 初始状态同样按播放意图推，避免刚起播时按钮先闪一下「播放」
        _emit(mapOf("isPlaying" to player.playWhenReady))
    }

    /** 把本实例下线：停掉进度协程并短路所有回调（player/session 归新实例所有，不在此释放）。 */
    private fun deactivate() {
        _active = false
        _progressJob?.cancel()
        // 【不要 stop ApplePlaybackServer】新实例接管的是**同一个** ExoPlayer，
        // Apple 那首歌还在从这个本机 HLS 端口拉分片；在这里关掉会话会直接把正在播的流掐断。
        // Apple 会话只由「换歌/换源」时的 start()/非 Apple 的 _setSource 负责关闭。
        _player = null
        _session = null
    }

    /**
     * 对齐 Dart `_requestNotificationPermission`：Dart 通过 `annie_music/media_store` 通道
     * 让原生 Activity 弹权限框。纯 Kotlin 引擎层没有 Activity，运行时申请由 MainActivity 完成；
     * 这里只做状态检查并记录（不阻塞前台播放）。
     */
    private fun _requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            _context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(_tag, "[audio] POST_NOTIFICATIONS 未授予：应由 MainActivity 发起运行时申请")
        }
    }

    /** 固定节拍的进度/时长推送（ExoPlayer 没有 positionStream / durationStream） */
    /**
     * 播放缓冲策略 —— 数值与理由见 [_loadControlForwardBudgetBytes] 那一组常量的注释。
     *
     * 用 [ByteBudgetLoadControl] 而不是 `DefaultLoadControl.Builder`：时间窗口对高码率
     * 是失控的（DSD512 后向 10 秒 = 226 MB），必须改成按字节预算。这里**不做任何设备判断**：
     * 预算对所有输出通路都成立 —— 它们属于 ExoPlayer 层，sink 只是消费者。
     */
    private fun _buildLoadControl(): ByteBudgetLoadControl =
        ByteBudgetLoadControl(
            allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, _allocatorChunkBytes),
            maxBufferMsBudget = _loadControlMaxBufferMs,
            bufferForPlaybackMs = _loadControlBufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = _loadControlBufferForPlaybackAfterRebufferMs,
            targetBufferBytes = _loadControlForwardBudgetBytes,
            backBufferCapMs = _loadControlBackBufferMs,
            forwardBudgetBytes = _loadControlForwardBudgetBytes,
            backBudgetBytes = _loadControlBackBudgetBytes,
        )

    private fun _startProgressLoop() {
        _progressJob?.cancel()
        _progressJob = _scope.launch {
            var lastPositionMs = -1L
            var lastDurationMs = -1L
            // 尾段冻结检测：上一次的原始位置与它最后一次变化的时刻
            var lastRawPos = -1L
            var lastRawChangeAt = 0L
            while (isActive) {
                val p = _player
                if (p != null) {
                    _startPendingWarmIfReady(p)
                    val durMs0 = p.duration
                    val pendingMs0 = (_renderersFactory?.pendingAudioUs?.invoke() ?: 0L) / 1000L
                    val raw0 = p.currentPosition
                    val now0 = android.os.SystemClock.elapsedRealtime()
                    if (raw0 != lastRawPos) {
                        lastRawPos = raw0
                        lastRawChangeAt = now0
                    }
                    // 尾段补偿：**只在"位置冻结 + 还在播"时才用「时长 − 未播」。**
                    //
                    // 为什么不能按"离结尾近"触发：`pending = 已写 − 已播`，而"已写"领先听感
                    // （环里那 2 s 就是它）。数据还没全交完时 `时长 − 未播 = 已播 + (时长 − 已写)`，
                    // 那个 `(时长 − 已写)` 是正的 ⇒ 高估。实测把 4:08 直接抬到 4:11 就是这个。
                    // 冻结 ⇒ 渲染器收尾 ⇒ 数据已全部交付，此式才等于真实剩余。
                    //
                    // **`p.isPlaying` 这一条是后补的救命条件（实测 USB 独占"暂停时进度条跑满"）。**
                    // 用户按暂停时 `currentPosition` 同样会停止变化，250 ms 后 `frozen` 成立，
                    // 于是这里把位置算成 `时长 − 环深 ≈ 结尾` 并 `maxOf` 上去 —— 界面进度条直接
                    // 顶满；一恢复播放 `raw` 又开始变，`frozen` 失效，位置又"恢复正常"。
                    // 这与 sink 一点关系都没有：位置被这里改写，sink 报什么都无所谓。
                    var pos = raw0
                    val frozen = now0 - lastRawChangeAt > 250L
                    if (frozen && p.isPlaying && durMs0 != C.TIME_UNSET && durMs0 > 0 && pendingMs0 > 0) {
                        pos = maxOf(raw0, durMs0 - pendingMs0).coerceAtMost(durMs0)
                    }
                    if (p.isPlaying || pos != lastPositionMs) {
                        lastPositionMs = pos
                        if (!_switchingSource) _emit(mapOf("position" to pos / 1000.0))
                    }
                    val dur = p.duration
                    if (dur != C.TIME_UNSET && dur > 0 && dur != lastDurationMs) {
                        lastDurationMs = dur
                        if (!_switchingSource) _emit(mapOf("duration" to dur / 1000.0))
                    }
                }
                delay(_progressIntervalMs)
            }
        }
    }

    private fun _emit(event: Map<String, Any?>) {        _events.tryEmit(event)
    }

    // ── PlayerBackend 实现 ──

    /**
     * 设置队列并从 [startIndex] 开播（对齐 Dart `setQueue`）。
     *
     * 注意：这里必须把 `loopMode` 同步落到播放器（见 [_applyRepeatMode]）——否则冷启动后
     * `_appLoopMode` 已是 single 而 `player.repeatMode` 仍是 OFF，单曲循环会「播完即停」。
     */
    override suspend fun setQueue(
        items: List<Map<String, String>>,
        startIndex: Int,
        loopMode: String,
        startPositionMs: Long,
    ) {
        withContext(Dispatchers.Main.immediate) {
            _generation++
            _playRequestId++
            _queue = items.toList()
            _urlCache.clear()
            _cacheKeyByUrl.clear()
            _pendingPrefetch.clear()
            _consecutiveFailures = 0
            _appLoopMode = LoopMode.fromName(loopMode)
            _applyRepeatMode()
            if (_queue.isEmpty()) return@withContext
            val safeIndex = if (startIndex in _queue.indices) startIndex else 0
            _playAbsIndex(safeIndex, startPositionMs)
        }
    }

    override suspend fun skipNext() {
        withContext(Dispatchers.Main.immediate) {
            if (_queue.isEmpty()) return@withContext
            val next = if (_appLoopMode == LoopMode.shuffle) {
                _consumeShuffleNext()
            } else {
                (_currentAbsIndex + 1) % _queue.size
            }
            _playAbsIndex(next)
        }
    }

    override suspend fun skipPrevious() {
        withContext(Dispatchers.Main.immediate) {
            if (_queue.isEmpty()) return@withContext
            val prev = if (_appLoopMode == LoopMode.shuffle) {
                _consumeShuffleNext()
            } else {
                if (_currentAbsIndex <= 0) _queue.size - 1 else _currentAbsIndex - 1
            }
            _playAbsIndex(prev)
        }
    }

    override suspend fun skipTo(index: Int) {
        withContext(Dispatchers.Main.immediate) {
            _playAbsIndex(index)
        }
    }

    /**
     * 拖拽排序：把 [fromIndex] 挪到 [toIndex]，**不重建队列**。
     *
     * 三件必须做的事：
     * 1. **不碰 player**。本后端的 timeline 恒为一条 MediaItem（[_setSource] 用
     *    `setMediaSource`，整条队列由 `_queue` 自己维护）。之前这里调的是
     *    `player.moveMediaItem(fromIndex, toIndex)` —— 在单条 timeline 上按队列下标移动
     *    必然越界（`fromIndex/toIndex` 只能是 0），异常被 `_backendCall` 吞掉，
     *    结果就是「App 里的顺序变了、引擎仍按老顺序播下一首」，拖拽看起来完全无效。
     * 2. `_currentAbsIndex` 跟着修正 —— 公式见 [indexAfterMove]，与 App 层**同一份**。
     * 3. `_pendingPrefetch` 是按**下标**记账的，顺序一变全部失效，必须清空；
     *    随机模式还要重掷「下一首」，否则下一首按钮会指向另一首歌。
     */
    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        withContext(Dispatchers.Main.immediate) {
            if (fromIndex == toIndex) return@withContext
            if (fromIndex !in _queue.indices || toIndex !in _queue.indices) return@withContext

            _queue = _queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            _currentAbsIndex = indexAfterMove(_currentAbsIndex, fromIndex, toIndex)
            _pendingPrefetch.clear()
            if (_appLoopMode == LoopMode.shuffle) {
                _shuffleNextIndex = _pickShuffleNext()
            }
        }
    }

    /**
     * 摘掉队列第 [index] 项（队列抽屉删除 / 删除本地文件）。
     *
     * **不动 player 的 timeline**：本后端的 timeline 始终只有一条 MediaItem
     * （[_setSource] 用 `setMediaSource`），整条队列是 `_queue` 自己维护的。
     * 也正因为如此，这里**不能**学 [moveQueueItem] 去调 `player.removeMediaItem(index)` ——
     * 单条 timeline 上按队列下标删只会越界。
     *
     * 正在播的那项若被摘掉，调用方（`PlayerViewModel.removeSongsFromQueue`）会先切歌，
     * 所以这里只做「扣掉一项 + 修正指针」。
     */
    override suspend fun removeQueueItem(index: Int) {
        withContext(Dispatchers.Main.immediate) {
            if (index !in _queue.indices) return@withContext
            _queue = _queue.toMutableList().apply { removeAt(index) }
            // 与 App 层同一份公式（见 indexAfterRemoval）；摘掉的正是当前项时靠它夹到有效范围
            _currentAbsIndex = indexAfterRemoval(_currentAbsIndex, index, _queue.size)
            // 预取是按**下标**记账的，摘一项之后全部错位
            _pendingPrefetch.clear()
            if (_appLoopMode == LoopMode.shuffle) {
                _shuffleNextIndex = _pickShuffleNext()
            }
        }
    }

    /** 设置 App 层循环模式；单曲 -> `REPEAT_MODE_ONE`，其余 -> `REPEAT_MODE_OFF`（对齐 Dart） */
    override suspend fun setAppLoopMode(loopMode: String) {        withContext(Dispatchers.Main.immediate) {
            _appLoopMode = LoopMode.fromName(loopMode)
            _applyRepeatMode()
            // 切模式不换歌，但要立刻让「预取目标」跟上新模式：
            // - 切到随机：现在就把「下一首」掷出来（否则预取会按顺序盲猜）
            // - 切回顺序/单曲：清掉随机结果
            if (_appLoopMode == LoopMode.shuffle) {
                _shuffleNextIndex = _pickShuffleNext()
            } else {
                _shuffleNextIndex = -1
            }
            // 按新模式的窗口预热。命中的都是 CacheService / 内存里的旧数据，不会重复打网络
            _startPrefetchUpcoming()
        }
    }

    /** 把 `_appLoopMode` 落到播放器（单曲 -> ONE，其余 -> OFF）；[setQueue] 与 [setAppLoopMode] 共用 */
    private fun _applyRepeatMode() {
        // **循环一律由我们自己处理**（`_handleEnded`），不用 `REPEAT_MODE_ONE`。
        //
        // 理由不是偏好：`REPEAT_MODE_ONE` 是 ExoPlayer 内部 seek 回 0，那条路会直接 `flush()`
        // 清掉独占的环形缓冲 —— 尾巴那 2 s 就没声了，而 `_handleEnded` 也不会被调用，
        // 我们连"先等尾巴播完"的机会都没有（实测就是这样又跑不满的）。
        _player?.repeatMode = Player.REPEAT_MODE_OFF
    }

    override suspend fun getQueueIndex(): Int = _currentAbsIndex

    override suspend fun play() {
        withContext(Dispatchers.Main.immediate) {
            val p = _player ?: return@withContext
            if (p.mediaItemCount == 0) return@withContext
            // **"暂停后再播不出声"的兜底。**
            //
            // 那条链断在 sink 里，不在这一层：`MediaCodecAudioRenderer.onStopped()` 调
            // `audioSink.pause()`，而 `AnnieAudioSink.pause()` 过去在渲染线程上直接进 native
            // 私有 ABI 的 `AudioTrack.pause()` —— 那次调用不返回时，播放线程从此不再处理任何
            // 消息，`onStarted()`（它才会调 `audioSink.play()`）也就永远不会被调用。
            // 实测 trace 就是这么断的：`L pause` 之后 100 秒，一条 `L play` 都没有。
            //
            // sink 侧已经把那些调用挪到控制线程上（`DapControlManager`）；如果**仍然**报过
            // 超时，说明这条引擎的播放线程可能已经卡住 —— 换一条引擎（新播放线程 + 新 sink），
            // 恢复播放并校验数据真的到了出口。
            if (_engineSuspect()) {
                RuntimeLog.record("音频", "恢复播放前发现引擎不可信（${AnnieAudioSink.lastControlStallNote()}）→ 重建引擎")
                _rebuildEngine("暂停后恢复：sink 控制调用超时", playAfter = true)
                return@withContext
            }
            // 播放器可能已退到 IDLE（服务被系统回收时会话被释放 → 底层 stop）：此时 play() 是
            // 空操作，表现为「暂停后切后台、回来点播放不播」，要点第二次或切歌（setQueue 里有
            // prepare）才恢复。这里兜底重新 prepare，保证一次点击就能续播。
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            _ensureServiceStarted()
            // 点了播放就要有样本流：1.5 s 内 `handleBuffer` 一次都没动 → 换引擎
            _armDataFlowVerify("恢复播放")
        }
    }

    override suspend fun pause() {
        withContext(Dispatchers.Main.immediate) {
            _player?.pause()
        }
    }

    override suspend fun seekTo(seconds: Double) {
        withContext(Dispatchers.Main.immediate) {
            _cancelResumeSeek()
            _player?.seekTo((seconds * 1000.0).roundToLong())
        }
    }

    /**
     * 用户设定的音量（0..1）。
     *
     * **只有调音模式会真的用它**：`player.volume` 是软件音量（每轨 float 缩放），
     * 在透明模式下那就是"我方动数据"，与位完美自相矛盾。透明模式一律交绝对音量
     * （蓝牙 AVRCP / 有线与 USB 的系统媒体音量），见 [_effectiveVolume]。
     */
    private var _userVolume: Float = 1f

    /** 主线程投递（`player.volume` 必须在播放器所属线程改） */
    private val _mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 真正下发给 ExoPlayer 的音量。
     *
     * - **透明**：恒 `1f`。数据路径上不做任何衰减，音量由系统/设备决定
     *   （这也是将来 AAudio 独占能直接用的前提：独占流本来就不经过 mixer 音量）。
     * - **调音**：用户的音量值。调音模式的契约就是"我方处理、用户说了算"，
     *   PEQ / 响度 / 音量都是他的工具，不能在调音模式里替他拆掉一个。
     */
    private fun _effectiveVolume(): Float =
        if (!_tuningActiveNow()) 1f else _userVolume

    /**
     * 这一首**实际**允许调音吗 —— 全应用唯一的运行时判据。
     *
     * 判据本身是纯函数 [com.annieplayer.annie_music.kt.core.audio.tuningEnabled]，
     * 但喂给它的必须是**实测生效**的通路 `_effectivePath`，不是设置里选的意图：
     * 意图 usbDirect + Bit Perfect 开着，而 USB 实际没起来 → 真跑的是系统 mixer，
     * 系统通路恒可调音。按意图算会把 DSP 关掉，出现「计划说调音、处理器却没启用」。
     *
     * 设置页显隐仍按意图算（它要展示"你选的东西意味着什么"），音频路径按生效算。
     *
     * **DSD 直出（DoP / Native）一律不算调音**：那条通路上没有 PCM 样本可处理，
     * 任何滤波/增益都会连 DoP 标记一起改掉。所以 DSD 模式下音量也随之交回设备与系统
     * （`_effectiveVolume` 返回 1.0），界面不会再显示「调音」。
     */
    private fun _tuningActiveNow(): Boolean =
        MoreSettings.tuningEnabledNow(
            _onBluetooth(),
            _effectivePath == SinkKind.oboe || _effectivePath == SinkKind.usbUac,
            _dsdTransport.isRaw,
        )

    /** 当前是否蓝牙。调音判据、音量策略、输出计划都要问它，所以只算一处。 */
    private fun _onBluetooth(): Boolean =
        _audioOutput?.currentRoute() == AudioRoute.bluetooth

    /**
     * 当前音量是否落在**独占通路的另一条链路**上（`ExclusiveVolume` → `PcmGain`）。
     *
     * 那条链路绕开 mixer；`player.volume` 是 mixer 的音量，对独占流没有接收方。
     * 两者都乘会得到两条 dB 曲线相乘（滑块走一半已是 −24 dB，界面数字也反推不出真实衰减），
     * 所以独占时 `player.volume` 恒 1f，衰减只在 [ExclusiveVolume] 一处发生。
     */
    private fun _exclusiveVolumeActive(): Boolean =
        _routingKnown &&
            (_effectivePath == SinkKind.usbUac || _effectivePath == SinkKind.oboe)

    /** 把当前该用的音量同步给播放器（模式切换后必须调一次，否则切回调音会静音或爆音） */
    private fun _syncOutputVolume() {
        // 独占音量的**归属与可用性**也在这里刷新：它与通路上报、DSD 传输方式同源，
        // 分散到各处写会让音量键在降级后仍然改一条不存在的链路。
        val exclusive = _exclusiveVolumeActive()
        ExclusiveVolume.setRoute(
            routeKey = _effectivePath.key,
            available = exclusive,
            raw = _dsdTransport.isRaw,
        )
        val player = _player ?: return
        val value = if (exclusive) 1f else _effectiveVolume()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            player.volume = value
        } else {
            _mainHandler.post { _player?.volume = value }
        }
    }

    override suspend fun setVolume(volume: Double) {
        withContext(Dispatchers.Main.immediate) {
            _userVolume = volume.toFloat().coerceIn(0f, 1f)
            // 独占通路：滑块的落点是 `ExclusiveVolume`，不是 `player.volume`
            if (_exclusiveVolumeActive()) {
                ExclusiveVolume.setLinear(_userVolume)
                _player?.volume = 1f
            } else {
                _player?.volume = _effectiveVolume()
            }
        }
    }

    /**
     * 清理播放缓存（「清除缓存」弹窗里的「播放缓存」项）。
     *
     * 用 Media3 的 [Cache] API 逐项 `removeResource`，**不直接删目录**
     * —— 缓存目录正被 `SimpleCache`（含其索引库）占用，直接 `deleteRecursively` 会让索引与文件不一致。
     */
    override suspend fun clearPlaybackCache(): Long = withContext(Dispatchers.IO) {
        val cache = _cache ?: return@withContext 0L
        var freed = 0L
        try {
            for (key in cache.keys.toList()) {
                freed += cache.getCachedBytes(key, 0L, Long.MAX_VALUE)
                cache.removeResource(key)
            }
        } catch (_: Throwable) {
            // 单个 key 失败不影响整体清理
        }
        freed
    }

    // ── 核心：解析 + 设置音源 + 自动前进 ──

    /**
     * 播放绝对下标 [index]（对齐 Dart `_playAbsIndex`）。
     *
     * 步骤与 Dart 一一对应：发切歌意图 -> 立即暂停旧曲 -> 解析 URL（先缓存后网络）->
     * 设置音源（失败清缓存重试一次）-> 清零失败计数、自动 play、预取后续。
     */
    private suspend fun _playAbsIndex(index: Int, resumeMs: Long = 0L) {
        val queue = _queue
        if (queue.isEmpty()) return
        if (index < 0 || index >= queue.size) return
        val requestId = ++_playRequestId
        _preloadBaseIndex = index
        _currentAbsIndex = index
        _retriedCurrent = false
        _cancelResumeSeek()
        // 切歌即结算峰值：它是**按曲累计**的，不清会把上一首的过冲挂到下一首头上
        _flushPeakLog()
        val item = queue[index]

        // 立即停掉上一首：解析直链可能耗时，否则旧歌会继续响（对齐 Dart unawaited(_player.pause())）
        _player?.pause()
        // 【故意不清空输出策略】之前这里先 `_applyOutputPolicy(null)` 回落一次、等 audioInfo
        // 到了再 apply 一次，同一采样率连续切歌时会多一次 AudioSink 重配（几十到上百 ms）。
        // 策略跟着上一首继续跑，新信息到达后按需再调整，重配次数减半。
        _emit(
            mapOf(
                PlayerBackend.EVENT_SWITCH_TO to index,
                PlayerBackend.EVENT_START_AT to (resumeMs / 1000.0).coerceAtLeast(0.0),
                "isPlaying" to true,
            ),
        )
        val url = _inSwitchWindow { _resolveUrl(index, item, false) }
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        // 解析期间用户可能已经切歌 -> 放弃本次设置，避免旧请求覆盖新音源
        if (requestId != _playRequestId) return

        _urlCache[_cacheKeyOf(item)] = url
        if (!_setSource(url, item, resumeMs)) {
            _handlePlaybackFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        if (resumeMs > 0L) _armResumeSeek(resumeMs)
        _player?.play()
        // 起播同样要验：`configure` 发生了但一个样本都不来，也是"界面正常、没声"
        _armDataFlowVerify("起播 index=$index")
        _ensureServiceStarted()
        // 随机模式：起播成功就立刻定下「下一首」，预取与自动切都认这一个
        if (_appLoopMode == LoopMode.shuffle) {
            _shuffleNextIndex = _pickShuffleNext()
        } else {
            _shuffleNextIndex = -1
        }
        _startPrefetchUpcoming()
        _startAudioInfoProbe(index, url)
    }

    /** 随机模式下掷一个「下一首」（排掉当前曲，避免原地重复） */
    private fun _pickShuffleNext(): Int {
        val candidates = _queue.indices.filter { it != _currentAbsIndex }
        if (candidates.isEmpty()) return _currentAbsIndex
        return candidates[(System.currentTimeMillis() % candidates.size).toInt()]
    }

    /** 取已定好的随机下一首；用掉后置空，等下一次起播再掷 */
    private fun _consumeShuffleNext(): Int {
        val picked = _shuffleNextIndex
            .takeIf { it in _queue.indices && it != _currentAbsIndex }
            ?: _pickShuffleNext()
        _shuffleNextIndex = -1
        return picked
    }

    /**
     * 命中缓存或解析 [index] 的播放直链；失败返回 null（不抛异常，对齐 Dart `_resolveUrl`）。
     * 预取入口 [fetchPreloadUrl] 的 Kotlin 移植见 [_fetchPreloadUrl]。
     */
    private suspend fun _resolveUrl(index: Int, item: Map<String, String>, forceRefresh: Boolean): String? {
        val songKey = _cacheKeyOf(item)
        if (!forceRefresh) {
            val cached = _urlCache[songKey]
            // Apple 的本机 HLS 地址是**一次性**的：每次 ApplePlaybackServer.start() 都会先
            // stop() 掉旧会话，再 listen 一个新的临时端口（ServerSocket(0)）。复用上一次的地址

            // 必然是死链——Media3 要先跑完 HLS 清单重试（约 3 秒）才报错，报错后
            // _handlePlaybackFailure 才会重新解析，表现就是「点切歌后 3 秒才出声」。
            // 所以 Apple 每次都真实走一遍 start()（master/media/init 都有缓存，20~60ms）。
            if (!cached.isNullOrEmpty() && item["platform"] != "apple") return cached
            if (item["platform"] != "apple") {
                _cachedOnlyUrl(songKey)?.let { return it }
            }
        }
        return try {
            val url = _fetchPreloadUrl(item)
            if (url.isNullOrEmpty()) {
                Log.w(_tag, "[audio] resolve empty (${item["platform"]} ${item["id"]})")
                null
            } else {
                url
            }
        } catch (e: Throwable) {
            Log.w(_tag, "[audio] resolve failed (${item["platform"]} ${item["id"]}): ${e.message}", e)
            null
        }
    }

    /**
     * 预取 URL。
     *
     * - 队列项自带 url（KKBOX 详情页 / qobuz 详情页 / 已解析）优先
     * - qobuz：无 url 时用活跃 token 取直链（搜索 / 跳转进来的歌靠这条）
     * - apple：本机 HLS 代理（FairPlay 解密）
     * - **其余平台（国内四平台 + KKBOX）统一走仓库层音源路由**（`fetchPlayUrlForBackend`）：
     *   四平台交给落雪插件；KKBOX 走官方票据接口。此前这里直连 `data/sources/` 会绕过插件，
     *   而 KKBOX 直接返回 null 会让「歌单里播 KKBOX 歌」永远没声。
     */
    private suspend fun _fetchPreloadUrl(item: Map<String, String>): String? {
        val provided = item["url"]
        // 本地文件原样播（全链路自己解封装），**不再转码**——没有中间产物、没有首播等待
        if (!provided.isNullOrEmpty()) return provided
        val id = item["id"].orEmpty()
        return when (item["platform"]) {
            "qobuz" -> {
                val token = qobuzTokenProvider().orEmpty()
                if (token.isEmpty()) null else {
                    getFileUrl(
                        id.removePrefix("qobuz:"),
                        token,
                        qualityId = qobuzHiresFormatId,
                    ).url
                }
            }
            "apple" -> {
                val song = _appleSongOf(item)
                // 未手动选档（「自动」）时走高解析档：没有超高解析的曲目由
                // `resolveAppleVariant(AlacHiRes)` 自己降级到最高 ALAC / AAC，不会失败
                val quality = MoreSettings.qualityFor(MusicPlatform.apple) ?: Quality.hires
                ApplePlaybackServer.start(song, quality)
            }
            else -> {
                fetchPlayUrlForBackend(item)
            }
        }
    }

    /**
     * 队列项 → Apple [Song]（`/parse/song`、`/key`、预热都只要 id，其它字段是元数据占位）。
     */
    private fun _appleSongOf(item: Map<String, String>): Song = Song(
        id = item["id"].orEmpty(),
        title = item["title"].orEmpty(),
        artist = item["artist"].orEmpty(),
        cover = item["cover"].orEmpty(),
        album = item["album"],
        duration = 0,
        platform = MusicPlatform.apple,
    )

    /**
     * 设置播放源（对齐 Dart `_setSource`）。返回是否成功（进程内同步错误如非法 URI 返回 false；
     * 网络/解码错误异步到达 [AnniePlayerListener.onPlayerError]）。
     *
     * 磁盘缓存规则：**所有平台**统一走 CacheDataSource + SimpleCache，缓存 key 用歌曲身份
     * `平台:id:音质`（见 [_cacheKeyOf]）而非直链 URL——这样插件换直链（带时间戳/签名）也命中同一份缓存。
     */
    private fun _setSource(
        url: String,
        item: Map<String, String>,
        startPositionMs: Long = 0L,
    ): Boolean {
        val p = _player ?: return false
        return try {
            // 传输方式是**每首**的属性，必须先归位：下面只有「本地 + 走全链路」那条分支
            // 会重设它，其余分支（流媒体、普通本地）不改就等于沿用上一首的 DSD 传输方式 ——
            // 于是放完 Native DSD 再放 FLAC，FLAC 会被塞进 raw alt，满幅噪声。
            _dsdTransport = DsdTransport.pcm
            _dsdTransportNotice = null
            _activeDsdLevel = null
            // 每首先归零；只有"走全链路"那条分支会把它填上（见下）
            _fullChainPath = ""
            // **切歌前必须拆掉上一首的内置 DAC 直通流。**
            //
            // 那条流只 `pause` 不 `release` 时，厂商 HAL 会留在 DSD 模式（`format` 被拨到
            // DSD、硬件率停在 hwRate=176400）。于是下一首 44.1k PCM 会被"按设备率"重采样到
            // 176.4 kHz —— 实测踩到。`release()` 里的 `onClosed` 才会下发 HAL 复位。
            //
            // 放在 `_setSource` 顶部而不是 `AnnieAudioSink.configure` 开头：后者要等 Media3
            // 真的 configure 才执行，而"上一首已经放完、下一首还没起来"这段正好是空窗期。
            // **不要在这里拆内置 DAC 流。**
            //
            // 收尾是"泄漏式异步 close"（`~AudioTrack` 在 PLQ110 上确认会卡死，见
            // `NativeDapSink.close`）。每切一首歌都触发一次，就会留下一串泄漏的
            // teardown 线程 + 未注销的服务端轨 —— 之后再建任何 AudioTrack 都可能被堵住，
            // 表现是"只有重启 App 才能再出声"。实测踩到。
            //
            // 该拆的时机只有两个：① `AnnieAudioSink.configure` 换后端时（Media3 的语义）；
            // ② 用户改通路时（见设置监听里那次强制清场）。切歌不拆。
            //
            // 但**厂商 HAL 的全局模式必须拨回 PCM**：那只是一次 binder 调用，不涉及析构。
            runCatching {
                com.annieplayer.annie_music.kt.core.audio.dap.DapVendorTuning.reset(
                    _context,
                    com.annieplayer.annie_music.kt.core.audio.dap.detectDapVendor(
                        manufacturer = android.os.Build.MANUFACTURER,
                        brand = android.os.Build.BRAND,
                        model = android.os.Build.MODEL,
                        product = android.os.Build.PRODUCT,
                        fileExists = { p -> runCatching { java.io.File(p).exists() }.getOrDefault(false) },
                        device = android.os.Build.DEVICE,
                    ),
                    48_000,
                )
            }
            // 诊断：切歌这一刻记一行（哪首、什么通路、走不走全链路）
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("T") {
                val item0 = item
                "setSource url=${url.takeLast(48)} platform=${item0["platform"]} " +
                    "usbExclusive=${MoreSettings.usbExclusive} dsdMode=${MoreSettings.dsdMode.value} " +
                    "dapWorking=${_dapWorkingNow()?.label ?: "null"}"
            }
            val apple = item["platform"] == "apple"
            // 本地文件（file:// / content://）：**不能**过 CacheDataSource —— 它会把整首歌
            // 再复制一份进播放缓存（本地上百首无损直接吃掉几个 G），且没有任何收益。
            val localFile = url.startsWith("file://") || url.startsWith("content://")
            // KKBOX 高音质交给播放器的是本机代理地址（`http://127.0.0.1:PORT/play`），
            // 而它的源文件已经落在 `cacheDir/kkbox_play` —— 那一份**本身就是**磁盘缓存。
            // 再过一遍 CacheDataSource 等于把同一首歌复制第二份：一首 Hi-Res FLAC 31MB，
            // 磁盘上占 62MB，每次播放还多一次 31MB 的本地拷贝（这是「听 KKBOX 下载量很大」
            // 的直接原因之一）。Apple 的本地分片要保留缓存：每片独立解密，代价高得多，
            // 且 `ApplePlaybackServer` 自带稳定 cache key。
            val localProxyNoCache = !apple &&
                (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))
            val bypassDiskCache = localFile || localProxyNoCache
            // 解码：本地与流媒体统一走 ffmpeg 软解（见 FfmpegAudioRenderer.supportsFormatInternal），
            // 不再按平台分流 —— 本地 FLAC 也拿 24bit，不落到 MediaCodec 的 16bit 截断。
            // 本地曲库「最近播放」：以队列项 id 为准（`local:<原始路径>`），
            // 不能用 url —— 冷门容器会被转成 cacheDir 里的 FLAC，那是临时产物。
            val localId = item["id"].orEmpty()
            if (localId.startsWith("local:")) {
                com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
                    .recordPlay(localId.removePrefix("local:"))
            }
            // Apple 的本机分片自带稳定 key（`apple:{id}:{tier}:seg:{n}`，见
            // ApplePlaybackServer.cacheKeyFor）；其余平台按「歌曲身份」登记，
            // 这样插件换直链（带时间戳/签名）也命中同一份缓存。
            if (!apple && !bypassDiskCache) _cacheKeyByUrl[url] = _cacheKeyOf(item)
            // 整首缓存：换歌就开一个后台任务把整首补进磁盘（播放照旧边读边播，不等它）。
            // 本地文件 / KKBOX 本机代理不走这条 —— 那两种本身就是磁盘上的一份，见
            // `_startFullCacheWarm` 的注释。Apple 走自己的版本：分片要先解密，见
            // `_startAppleFullCacheWarm`。
            if (bypassDiskCache) {
                _pendingWarmUrl = null
                _cancelFullCacheWarm()
            } else if (apple) {
                _pendingWarmUrl = null
                _startAppleFullCacheWarm()
            } else {
                _pendingWarmUrl = url
            }
            if (!apple && !localFile) ApplePlaybackServer.stop()
            // 切歌时的「谁该全速下载」：KKBOX 高音质由我们自己的代理预读，不走 ExoPlayer 的
            // LoadControl，所以要在这里显式指定前台会话 —— 否则「100MB 的歌听到一半切走，
            // 旧会话还在全速拉剩下的 60MB」，新歌的首包被挤到超时（界面表现「加载失败」）。
            if (item["platform"] == "kkbox") {
                com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxPlayServer
                    .markForegroundByUrl(url)
            } else {
                com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxPlayServer
                    .markAllBackground()
            }
            val factory = if (bypassDiskCache) {
                _plainSourceFactory ?: _cachedSourceFactory
            } else {
                _cachedSourceFactory ?: _plainSourceFactory
            }
            val source: MediaSource = run {
                val mediaItem = _buildMediaItem(url, item)
                // 本地全链路：ffmpeg 自己解封装 + 解码 + 重采样，Media3 只保留时间轴/缓冲/输出。
                // 命中条件见 FfmpegLocalRoute（冷门容器 / >192k / >24bit）。
                val localPath = if (localFile) {
                    runCatching { android.net.Uri.parse(url).path }.getOrNull().orEmpty()
                } else {
                    ""
                }
                if (localFile && localPath.isNotEmpty() && FfmpegLocalRoute.useFullChain(localPath)) {
                    // DSD 走**自己的轴**：native 只把 1-bit 解成 352800 Hz float（`keepSourceRate`），
                    // 2:1 抽取交给下游 r8brain —— 不是让 libswresample 用 32 抽头 / 线性相位去抽
                    // （那会把 DSD 噪声整形的超声噪声折回音频带，并重新长出前置振铃）。
                    // 所以这里**必须传 0**：传目标率就等于让 swr 重采样，整条改动白做。
                    val dsdTarget = _dsdFullChainRate(localPath)
                    val dsdLevel = _dsdLevelOf(localPath)
                    val targetRate = if (dsdTarget != null) 0 else _currentPlan().sampleRate
                    val bits = FfmpegLocalRoute.outputBits(localPath, BluetoothCodec.info.value == null)
                    // 这一首真正用哪种传输：PCM / DoP / Native。判定见 `decideDsdTransport`。
                    // 降级原因是**事实**，写进 `AudioOutputStatus` 让界面说清楚，绝不写回设置。
                    val dsdDecision = _resolveDsdTransport(dsdLevel)
                    val transport = dsdDecision.transport
                    _activeDsdLevel = dsdLevel
                    // 计划指纹用的快照：只在建源完成处更新（重建过程中保持稳定，防自激）
                    _planLevel = dsdLevel
                    _dsdTransport = transport
                    _dsdTransportNotice = dsdDecision.reason
                    // 记下"这条源是按哪种传输方式建的"：snapshot 要拿它去问 sink 那边对不对得上
                    _fullChainPath = localPath
                    androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                        // 数据由 native 按路径自己读，Media3 侧的 input 只用来推进位置
                        // （它是 `ProgressiveMediaPeriod` 唯一的流量节流信号），见 PositionOnlyDataSource
                        PositionOnlyDataSource.Factory(),
                        androidx.media3.extractor.ExtractorsFactory {
                            arrayOf(
                                FfmpegLocalExtractor(
                                    path = localPath,
                                    targetSampleRate = targetRate,
                                    outBits = bits,
                                    keepSourceRate = dsdTarget != null,
                                    // raw 模式不抽 PCM：非 0 会让界面以为还在做 DSD 抽取
                                    dsdTargetRate = if (transport.isRaw) 0 else dsdTarget ?: 0,
                                    dsdTransport = transport,
                                    // **Native raw DSD 的字序由「谁在收」决定，不是全局常量。**
                                    //
                                    // 两条路各写各的规范：
                                    // · USB 独占 → UAC 的 raw alt。`bmFormats` bit31 只标"raw data"、
                                    //   不标字节序，按 ALSA `DSD_U32_BE`（字节序 = 时间序）走大端。
                                    //   实测 KA13 换 LE 也一样静音，说明字序不是那条路的问题。
                                    // · 播放器内置 DAC → 容器 `audio_format_t` 自己写着字节序，
                                    //   `0x1A000002` 就是 `DSD_U32_**LE**`。按大端打不会报错、
                                    //   轨道照样排空，DAC 解出来是**噪声**（JM21 实测踩到）。
                                    nativeLittleEndian = _nativeDapWordsLittleEndian(transport),
                                ),
                            )
                        },
                    ).createMediaSource(mediaItem)
                } else {
                    // 非全链路（Media3 自己解容器）：这一首不是 DSD，指纹里的档位必须归零，
                    // 否则会把上一首 DSD 的传输方式留在计划指纹里
                    _planLevel = null
                    (factory ?: return false).createMediaSource(mediaItem)
                }
            }
            // **计划指纹的写点就在这里。** `_planLevel` / `_dsdTransport` 在上面两个分支里
            // 刚刚定稿，而 `configure()` 之后紧接着就会有一次路由上报（`onSinkRouting`），
            // 它要比对的就是"这一份源"的指纹。
            //
            // 以前唯一的写点在设置监听里，跑在 `_setSource` **之前**：于是第一次 DSD 起播时，
            // 上报算出来的指纹（`…:native`）与记录下来的（`…:pcm`，那时 `_planLevel` 还是 null）
            // 必然不同 → 判定"计划变了" → 正在播的这条流被换掉。实测：起播 400 ms 后
            // 换源，播放器此后永远停在 BUFFERING（`state=2 loading=true durMs=UNSET`），
            // 出一点声就停住、进度条不动。
            _lastPlanKey = _planKeyOf(_currentPlan())
            _lastAppliedPathKey = if (MoreSettings.usbExclusive) "usb" else "system"
            // 音源要重建 = 接下来必然有一次新的 `configure`。先清掉上一首的交付格式，
            // 免得它跨曲残留、被状态行误当成本首的交付率（那会得出完全相反的结论）。
            AnnieAudioSink.clearConfigureNote()
            // 诊断：换源前后各记一行。`stop()` 之后"什么都没发生"（没有 `R`/`O`/`C`）
            // 有两种可能——没走到这里，或走到了但 Media3 把 `prepare()` 吞了。
            val step = swapStepFor(
                runCatching { p.playbackState }.getOrDefault(Player.STATE_IDLE),
            )
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("Q") {
                "swap-begin step=$step state=${runCatching { p.playbackState }.getOrDefault(-1)} " +
                    "items=${runCatching { p.mediaItemCount }.getOrDefault(-1)} " +
                    "pwr=${runCatching { p.playWhenReady }.getOrDefault(false)}"
            }
            // **非 IDLE 必须先 `stop()`**：`prepare()` 在非 IDLE 时是彻底的空操作
            // （字节码第一段 `if (state != IDLE) return`），而 `setMediaSource()` 会把状态
            // 改成 BUFFERING —— 那正是 trace 里 `swap-begin state=3` → `swap-set state=2` →
            // `swap-prepared state=2` 之后再无 `C configure` 的成因（按"下一首"直接切歌踩的就是它）。
            //
            // `stop()` 的 IDLE 是**同步**写进 `playbackInfo` 的（`stopInternal` 里
            // `copyWithPlaybackState(1)` 紧接 `updatePlaybackInfo`，后者的第一段就是赋值），
            // 所以不需要等 `onPlaybackStateChanged(IDLE)` —— 那个回调在播放线程已经卡死时
            // 永远不会来，而卡死正是这条链上最常见的一种形态。
            if (step == SwapStep.stopThenSetAndPrepare) runCatching { p.stop() }
            _primeResamplerFor(url)
            p.setMediaSource(source)
            if (startPositionMs > 0L) runCatching { p.seekTo(startPositionMs) }
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("Q") {
                "swap-set state=${runCatching { p.playbackState }.getOrDefault(-1)}"
            }
            p.prepare()
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("Q") {
                "swap-prepared state=${runCatching { p.playbackState }.getOrDefault(-1)}"
            }
            // 出口校验：`prepare()` 不抛异常不代表 `configure` 会来，超时就换引擎
            _armConfigureVerify("setSource ${url.takeLast(24)}")
            true
        } catch (t: Throwable) {
            Log.w(_tag, "[audio] setSource failed url=$url", t)
            false
        }
    }

    /**
     * 缓存 key 解析：Apple 本机 HLS 的分片/init 由 [ApplePlaybackServer.cacheKeyFor] 给稳定 key
     * （播放列表不缓存，所以那边返回 null 时直接用带 nonce 的完整 URL），其余平台走登记表。
     */
    private fun _cacheKeyOfSpec(spec: DataSpec): String {
        val uri = spec.uri.toString()
        return ApplePlaybackServer.cacheKeyFor(uri) ?: _cacheKeyByUrl[uri] ?: uri
    }

    /** 稳定缓存 key：`平台:id:音质`（与直链无关，插件/内置、换链重解析都命中同一份） */
    private fun _cacheKeyOf(item: Map<String, String>): String =
        "${item["platform"].orEmpty()}:${item["id"].orEmpty()}:" +
            effectivePlayQualityOf(item).value
    /**
     * 整首已在本地时，返回一个**不联网**的占位 URI（并登记到该缓存 key）。
     *
     * 判定极严：必须已记录内容总长、且「已缓存字节 == 总长」才成立 —— 只要差一字节就返回 null，
     * 退回正常解析拿真直链（缓存前缀照读、缺的部分照下）。占位 URI 因请求区间全命中，不会打开 upstream。     */
    private fun _cachedOnlyUrl(songKey: String): String? {
        val cache = _cache ?: return null
        val length = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(songKey))
        }.getOrNull() ?: return null
        if (length <= 0) return null
        val cachedBytes = runCatching {
            cache.getCachedBytes(songKey, 0L, length)
        }.getOrNull() ?: return null
        if (cachedBytes != length) return null
        val url = Uri.parse("https://annie.local/cache/" + Uri.encode(songKey)).toString()
        _cacheKeyByUrl[url] = songKey
        return url
    }

    /**
     * 起跑整首预热 —— 但只在播放已经稳住之后。
     *
     * 由 `_startProgressLoop` 每 100 ms 调一次；条件不满足就原样挂着，下一次再看。
     * 三个条件缺一不可：
     * - `STATE_READY`：还在 BUFFERING 时预热只会让缓冲更难补满；
     * - `isPlaying`：暂停中开预热等于在用户没听的时候抢带宽；
     * - 缓冲领先 ≥ [_warmStartBufferedMs]：先让播放器自己把窗口拿满，
     *   否则两者从同一个 offset 起步，前几 MB 会被下载两遍。
     */
    private fun _startPendingWarmIfReady(p: Player) {
        val url = _pendingWarmUrl ?: return
        if (downloadActive) return
        val ready = shouldStartFullCacheWarm(
            playbackState = p.playbackState,
            isPlaying = p.isPlaying,
            bufferedPosition = p.bufferedPosition,
            currentPosition = p.currentPosition,
            minBufferedMs = _warmStartBufferedMs,
        )
        if (!ready) return
        _pendingWarmUrl = null
        _startFullCacheWarm(url)
    }

    /**
     * 后台把**整首**写进播放缓存（换歌即取消，见 [_fullCacheJob]）。
     *
     * 为什么不靠「把 LoadControl 的窗口调大」达到同样效果：那个窗口是内存里 SampleQueue
     * 的上限（30 秒 / 24 MB 是 DSD 2.1 MB/s 的安全阀），整首 100 MB 的 Hi-Res 会直接把堆吃掉。
     * [CacheWriter] 只往磁盘追加，内存里只占一个临时缓冲。
     *
     * 与播放**并行**：播放器读到哪算哪，这里从第一个未缓存的字节一路补到文件末尾；
     * 两者命中同一份缓存（key 由 [_cacheKeyOfSpec] 给出），所以是「边播边补」，
     * **不存在「全缓存完才出声」**。已经缓存过的区间 [CacheWriter] 自己会跳过。
     *
     * 只对「单条渐进式 URL」的平台生效：本地文件（本身就在磁盘）、KKBOX（本机代理文件
     * 就是缓存，由 `KkboxPlayServer` 自己补满）、Apple（HLS 分片要先解密，见
     * [_startAppleFullCacheWarm]）都不走这条。
     */
    private fun _startFullCacheWarm(url: String) {
        val cache = _cache ?: return
        val generation = _cancelFullCacheWarm()
        val spec = DataSpec(Uri.parse(url))
        _fullCacheJob = _scope.launch(Dispatchers.IO) {
            runCatching {
                val httpFactory = OkHttpDataSource.Factory(globalOkHttp).setUserAgent(_userAgent)
                val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
                // `CacheWriter` 只接受 CacheDataSource（不是裸 DataSource + Cache）：
                // 它要靠 CacheDataSource 自己的 key 工厂与「已缓存区间跳过」逻辑。
                // **不带 `FLAG_IGNORE_CACHE_ON_ERROR`**：那个 flag 的语义是「缓存出错就绕过缓存」，
                // 而绕过时写入也被一并跳过 —— 后台预热会静默变成「只下不存」，白费流量。
                // 这里宁可让缓存异常直接把这一轮结束掉，下次播放时再补。
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(::_cacheKeyOfSpec)
                    .createDataSource()
                // 这一版 `CacheWriter` 没有 isCanceled 参数：`cache()` 是阻塞读，
                // Job.cancel() 打断不了它。所以在进度回调里比对代际，过期就抛出去
                // —— 否则「切歌后旧任务继续拉完剩下的几十 MB」会把新歌带宽吃掉。
                //
                // 同时**主动让路**：每补满 [_warmYieldBytes] 就睡 [_warmYieldMs]。
                // 播放与预热共用同一条上行带宽，Hi-Res（1.15 MB/s）在境外 CDN 单连接
                // 只有 3~5 MB/s 时，全速预热会把播放压在需求线下面 —— 缓冲一路净消耗到
                // BUFFERING，听感就是「大文件周期性断一下」。让出 8 MB / 250 ms ≈ 降速 10%，
                // 补满整首只慢几秒，播放侧却不再挨饿。
                var sinceYield = 0L
                val progress = object : CacheWriter.ProgressListener {
                    override fun onProgress(
                        requestLength: Long,
                        bytesCached: Long,
                        newBytesCached: Long,
                    ) {
                        if (generation != _warmGeneration) {
                            throw CancellationException("full cache warm superseded")
                        }
                        if (downloadActive) {
                            throw CancellationException("download active")
                        }
                        sinceYield += newBytesCached
                        if (sinceYield >= _warmYieldBytes) {
                            sinceYield = 0L
                            runCatching { Thread.sleep(_warmYieldMs) }
                        }
                    }
                }
                CacheWriter(cacheDataSource, spec, null, progress).cache()
            }
        }
    }

    /**
     * Apple 的整曲预热：把当前会话的每个 HLS 分片都过一遍 [CacheWriter]。
     *
     * 与 [_startFullCacheWarm] 只差「数据从哪来」：Apple 的分片是 FairPlay 加密的 fMP4，
     * 由本机 HLS 代理（[ApplePlaybackServer]）逐片解密后吐明文，落盘仍走同一个
     * `CacheDataSource` + SimpleCache，key 由 `ApplePlaybackServer.segmentTargets()` 给定
     * （`apple:{adamId}:{tier}:seg:{n}`，与播放器请求同一份）。
     *
     * 播放器自己只会请求「听到的那几片」，所以不预热就等于「听完才有整曲」。这里替它补齐。
     * **串行**而不是并发：每片都要跑一次 Temari 解密，并发只会让播放请求在 CPU 上排队。
     *
     * key 用 [DataSpec.Builder.setKey] 写死：预热期间切歌会换掉 `ApplePlaybackServer.current`，
     * 那时再靠 `_cacheKeyOfSpec` 现算就映射不到旧地址了。
     */
    private fun _startAppleFullCacheWarm() {
        val cache = _cache ?: return
        val targets = ApplePlaybackServer.segmentTargets()
        val generation = _cancelFullCacheWarm()
        if (targets.isEmpty()) return
        _fullCacheJob = _scope.launch(Dispatchers.IO) {
            val httpFactory = OkHttpDataSource.Factory(globalOkHttp).setUserAgent(_userAgent)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            var sinceYield = 0L
            val progress = object : CacheWriter.ProgressListener {
                override fun onProgress(
                    requestLength: Long,
                    bytesCached: Long,
                    newBytesCached: Long,
                ) {
                    if (generation != _warmGeneration) {
                        throw CancellationException("apple full cache warm superseded")
                    }
                    if (downloadActive) {
                        throw CancellationException("download active")
                    }
                    sinceYield += newBytesCached
                    if (sinceYield >= _warmYieldBytes) {
                        sinceYield = 0L
                        runCatching { Thread.sleep(_warmYieldMs) }
                    }
                }
            }
            for ((url, key) in targets) {
                // 切歌 / 会话关闭后不再开新片：剩下的会在下次播这首时由播放器按需拉
                if (generation != _warmGeneration) break
                if (downloadActive) break
                runCatching {
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstream)
                        .setCacheKeyFactory(::_cacheKeyOfSpec)
                        .createDataSource()
                    val spec = DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setKey(key)
                        .build()
                    CacheWriter(cacheDataSource, spec, null, progress).cache()
                }
            }
        }
    }

    /**
     * 停掉正在进行的整首缓存并让它的进度回调立刻失效，返回**新的**代际号。
     *
     * 返回新代际而不是 void：调用方拿它当「我这一轮」的凭据，见 [_startFullCacheWarm]。
     */
    private fun _cancelFullCacheWarm(): Int {
        _warmGeneration += 1
        _fullCacheJob?.cancel()
        _fullCacheJob = null
        return _warmGeneration
    }

    /** 构造 MediaItem：把队列项 metadata 带入，供通知/锁屏展示（对齐 Dart handler.setMediaItem 的兜底文案） */
    private fun _buildMediaItem(url: String, item: Map<String, String>): MediaItem {
        val title = item["title"].orEmpty().ifEmpty { "未知歌曲" }
        val artist = item["artist"].orEmpty().ifEmpty { "未知歌手" }
        val album = item["album"].orEmpty().ifEmpty { null }
        val cover = item["cover"].orEmpty().ifEmpty { null }
        val builder = MediaMetadata.Builder().setTitle(title).setArtist(artist)
        if (album != null) builder.setAlbumTitle(album)
        if (cover != null) {
            val artUri = Uri.parse(cover)
            builder.setArtworkUri(artUri)
        }
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(item["id"].orEmpty())
            .setMediaMetadata(builder.build())
            .build()
    }

    /**
     * 播放失败处理（对齐 Dart `_playAbsIndex` 的 catch + 重试分支）：
     * 首次失败清缓存并强制重解析一次；仍失败（或已重试过）则自动跳下一首。
     *
     * Media3 的网络/解码失败是异步 `onPlayerError`，Dart 的 `setSource` 同步抛异常；
     * 两条路径都汇入这里，语义与 Dart「失败重试一次」一致。
     */
    private suspend fun _handlePlaybackFailure(index: Int) {
        if (!_active) return
        if (index != _currentAbsIndex) return
        val item = _queue.getOrNull(index)
        if (item == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        if (_retriedCurrent) {
            _autoAdvanceOnFailure(index)
            return
        }
        _retriedCurrent = true

        val requestId = _playRequestId
        _urlCache.remove(_cacheKeyOf(item))
        val url = _inSwitchWindow { _resolveUrl(index, item, true) }
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        _urlCache[_cacheKeyOf(item)] = url
        if (!_setSource(url, item, _resumeSeekTargetMs())) {
            _urlCache.remove(_cacheKeyOf(item))
            _autoAdvanceOnFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        _player?.play()
        _ensureServiceStarted()
        _startPrefetchUpcoming()
        _startAudioInfoProbe(index, url)
    }

    /**
     * 当前曲目播放失败。
     *
     * **不再自动切下一首**：静默跳歌会把失败原因瞬间冲掉（错误只剩下一首的正常状态），
     * 而且这台 ROM 会丢弃 App 日志，`Log.w` 根本抓不到 —— 现场等于不可见。
     * 现在改为停下来，原因留在 [AudioDirectProbe.lastPlaybackFailure]（设置页面板可读）。
     */
    private suspend fun _autoAdvanceOnFailure(failedIndex: Int) {
        if (!_active) return
        _consecutiveFailures++
        val label = _queue.getOrNull(failedIndex)
            ?.let { it["title"] ?: it["name"] ?: it["id"] }
            ?: "#$failedIndex"
        AudioDirectProbe.recordPlaybackFailure(
            "[$label] ${AudioDirectProbe.lastPlaybackFailure} → 已停止（连续失败 $_consecutiveFailures）",
        )
        _player?.pause()
        _emit(mapOf("isPlaying" to false))
    }

    /** 播放自然结束（对齐 Dart `_handleEnded`）；单曲循环由 `REPEAT_MODE_ONE` 处理不会走到这里 */
    private suspend fun _handleEnded() {
        if (!_active) return
        if (_queue.isEmpty()) return
        // 自然播完：先等独占通路把环里的尾巴放完，再决定下一首/重播。
        // 放在这里（挂起，不在播放线程上）而不是 sink 的 `configure()` —— 后者会阻塞播放线程，
        // 那 2 秒里 Media3 报不出位置，进度条会停在"结尾前两秒"。
        _awaitTailDrain()
        // 记一笔：如果曲目时长是 0/未知，这里会立刻触发，表现为"连续跳歌"（不是失败跳）
        AudioDirectProbe.recordPlaybackFailure(
            "自然结束 index=$_currentAbsIndex 时长=${_player?.duration ?: -1}ms",
        )
        when {
            // 单曲循环：由我们自己重播（`REPEAT_MODE_ONE` 已被禁用，理由见 `_applyRepeatMode`）
            _appLoopMode == LoopMode.single -> _playAbsIndex(_currentAbsIndex)
            // 只有一首且不是单曲循环：播完即停（沿用原行为）
            _queue.size <= 1 -> return
            _appLoopMode == LoopMode.shuffle -> _playAbsIndex(_consumeShuffleNext())
            else -> _playAbsIndex((_currentAbsIndex + 1) % _queue.size)
        }
    }

    /**
     * 等独占通路把已交付的音频**真的播完**（最多 3 s，暂停中不等）。
     *
     * 换曲策略属于播放层：用户手动切歌不走这里（那时应该立刻切走），只有自然结束才等。
     */
    private suspend fun _awaitTailDrain() {
        val pendingUs = { _renderersFactory?.pendingAudioUs?.invoke() ?: 0L }
        if (pendingUs() <= 0L) return
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000L
        while (pendingUs() > 0L && android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(5L)
        }
    }

    /**
     * 后台预取当前曲目之后 N 首（对齐 Dart `_prefetchUpcoming`：代际校验 + 并发上限 + 去重）。
     *
     * 与 Dart 的 `unawaited(_prefetchUpcoming())` 一致：targets 与代际在调用点**同步**快照，
     * 真正抓取在 [_scope] 上并发进行（网络调用内部挂起，不阻塞主线程）。
     */
    private fun _startPrefetchUpcoming() {
        if (!_active) return
        val gen = _generation
        val count = _queue.size
        if (count <= 1) return
        // 单曲循环：下一首永远是它自己，没有可预热的对象
        if (_appLoopMode == LoopMode.single) return

        val targets = mutableListOf<Int>()
        if (_appLoopMode == LoopMode.shuffle) {
            // 随机模式：只预热已经定好的那一首（命中率 100%），不按顺序盲猜
            _shuffleNextIndex.takeIf { it in _queue.indices }?.let { targets.add(it) }
        } else {
            var step = 1
            while (step <= _prefetchAhead && step < count) {
                targets.add((_currentAbsIndex + step) % count)
                step++
            }
        }
        if (targets.isEmpty()) return

        // 只有「紧邻下一首」才预下首片；更远的只灌元数据/密钥（省流量，也不占内存）
        val segmentTarget = targets.first()

        val cursor = AtomicInteger(0)
        repeat(_prefetchConcurrency) {
            _scope.launch {
                while (true) {
                    val i = cursor.getAndIncrement()
                    if (i >= targets.size) return@launch
                    val idx = targets[i]
                    if (gen != _generation) return@launch
                    val item = _queue.getOrNull(idx) ?: continue
                    if (item["platform"] == "apple") {
                        // Apple 的「预取」不是解析直链（本机地址跟会话绑定、不能存），
                        // 而是把 masterUrl / master / media / init / key 灌进缓存，
                        // 顺手把首片段解密好留在内存里，切换时只剩起播放器。
                        if (_pendingPrefetch.contains(idx)) continue
                        _pendingPrefetch.add(idx)
                        try {
                            val quality = MoreSettings.qualityFor(MusicPlatform.apple) ?: Quality.hires
                            ApplePlaybackServer.warm(
                                song = _appleSongOf(item),
                                quality = quality,
                                warmFirstSegment = idx == segmentTarget,
                            )
                        } catch (_: Throwable) {
                            // 预热失败静默：切歌时会重新解析
                        } finally {
                            _pendingPrefetch.remove(idx)
                        }
                        continue
                    }
                    val songKey = _cacheKeyOf(item)
                    if (_urlCache.containsKey(songKey) || _pendingPrefetch.contains(idx)) continue
                    _pendingPrefetch.add(idx)
                    try {
                        val url = _fetchPreloadUrl(item)
                        if (gen == _generation && !url.isNullOrEmpty()) {
                            _urlCache[songKey] = url
                        }
                    } catch (_: Throwable) {
                        // 预取失败静默忽略，切歌时会再次尝试解析
                    } finally {
                        _pendingPrefetch.remove(idx)
                    }
                }
            }
        }
    }

    /**
     * 探测当前曲目的音频格式（编码 / 位深 / 采样率）并下发 `audioInfo` 事件。
     *
     * 优先读 Media3 磁盘缓存里**已落盘的本地文件**（零网络）；刚起播缓存还没写入、
     * 或该平台不走缓存（酷狗直链走 [_plainSourceFactory]）时退回直接探测 URL。
     *
     * Apple 例外：播放用的是本机 `127.0.0.1` 的明文 HLS 代理，ffprobe 探不到真实编码
     * （拿到的只有 m3u8 文本）。它的编码/位深/采样率在解析 master 时就已经确定，
     * 直接用 [ApplePlaybackServer.currentPlayback]，省一次 256KB 拉取也不会空胶囊。
     */
    private fun _startAudioInfoProbe(index: Int, url: String) {
        if (!_active || url.isEmpty()) return
        // Apple 专用提前通道：档位解析完（~1s）就推音质胶囊，不必等整个 open()（2~4s）。
        // 这里每首歌重设一次，lambda 无状态，重复赋值无害。
        ApplePlaybackServer.onInfoReady = { early ->
            if (early.songId == _queue.getOrNull(_currentAbsIndex)?.get("id")) {
                _lastAudioInfo = early.info
                _applyOutputPolicy(early.info)
                _emit(mapOf("audioInfo" to early.info))
            }
        }
        val gen = _generation
        val item = _queue.getOrNull(index)
        if (item?.get("platform") == "apple") {
            val known = ApplePlaybackServer.currentPlayback
                ?.takeIf { it.songId == item["id"] }?.info
            if (known != null) {
                _lastAudioInfo = known
                _applyOutputPolicy(known)
                _emit(mapOf("audioInfo" to known))
                return
            }
        }
        _scope.launch {
            val info = _probeAudioInfo(url)
            if (gen != _generation || index != _currentAbsIndex) return@launch
            _lastAudioInfo = info
            _applyOutputPolicy(info)
            _emit(mapOf("audioInfo" to (info ?: AudioInfo())))
        }
    }

    /**
     * 蓝牙 codec 协商结果变化（换 codec / 改采样率档位 / 首次读到）时的唯一处理入口。
     *
     * 为什么不能只 `_applyOutputPolicy()`：蓝牙的目标采样率取自 `BluetoothCodec.info`，
     * 它一变，[OutputPlan] 的 `sampleRate` 就跟着变 —— 而采样率是**建 AudioTrack 时**定的，
     * 不重建音源就不会生效。于是会退化成「界面写 48 kHz、实际还是 44.1 kHz」，
     * 那一次 SRC 悄悄落回系统 mixer。
     *
     * 判定复用设置观察那一套指纹 [_planKeyOf]（`decode:path:sampleRate:bitDepth`），
     * 只有真的影响 `AudioSink.configure` 时才重建，避免协商窗口内多次重读触发多次重建。
     */
    private fun _onBluetoothCodecChanged() {
        val plan = _currentPlan()
        _lastPlanKey = _planKeyOf(plan)
        _applyOutputPolicy(_lastAudioInfo)
        if (outletNeedsReload(plan.deliveredRate, plan.bitDepth, AnnieAudioSink.lastConfigureNote)) {
            _reloadCurrentSource()
        }
    }

    /**
     * 把「模式 / EQ 预设 / 响度 / 采样率 / 通路」实时下发到处理器链。
     *
     * EQ 系数是整体替换的，播放中改预设立即生效；**会改变 AudioSink configure 的参数**
     * （采样率 / 位深 / 通路）必须重建一次音源才会生效，所以这里只在计划指纹变化时
     * 才 `_reloadCurrentSource()`，避免同一次设置抖动重建两次。
     */
    private fun _observeAudioSettings() {
        _scope.launch {
            kotlinx.coroutines.flow.combine(
                MoreSettings.usbExclusiveFlow,
                MoreSettings.aaudioExclusiveFlow,
                MoreSettings.outputToggles,
                MoreSettings.tuningEngine,
                MoreSettings.loudnessNormalization,
            ) { _, _, _, _, _ -> Unit }
                .collect {
                    // 用户主动改了通路：实测结论作废，计划先按**意图**算。
                    //
                    // 不这么做会连着重建两次：第一次按旧通路算（例如仍按 systemMix 定 48k），
                    // 新 configure 起来后才报回 usbDirect，于是计划又变一次、再重建一次。
                    // 播放中切换通路的代价就是一次断音，多付一次等于断两次。
                    if (MoreSettings.usbExclusive != (_effectivePath == SinkKind.usbUac) ||
                        MoreSettings.aaudioExclusive != (_effectivePath == SinkKind.oboe)
                    ) {
                        _invalidateRouting()
                    }
                    if (MoreSettings.aaudioExclusive) {
                        AudioDirectProbe.warmUpAaudioAsync {
                            _applyOutputPolicy(_lastAudioInfo)
                        }
                    }
                    // 用户改了通路 = 新意图，旧路的失败记忆不该继续拖住它。
                    // 不清的话：在「播放器内置 DAC」上失败过一次，之后无论怎么切回来都
                    // 直接判 `TransportFailed`，用户会以为这个模式坏了。
                    _failedTransports.clear()
                    // **重建风暴护栏也一起清**：用户操作是新意图，不该被"上一条通路那几次
                    // 失败重建"吃掉额度。不清的话，切通路时那次**唯一**的重建会被护栏拦下
                    // —— 界面已经变了、音源没换，表现成"切完没反应、只有重启才生效"。
                    _reloadBurst = reloadBurstReset(android.os.SystemClock.elapsedRealtime())
                    // **切模式必须把厂商 HAL 拨回 PCM 并拆掉直通流。**
                    // 那条 DIRECT 流会把 HAL 的 `format` / `sampling_rate` 停在 DSD 上
                    // （硬件率也停在 hwRate）。不清的话切回「系统 · AudioTrack」之后，
                    // 系统通路建出来的轨仍带着 DSD 语义 —— 实测"切回来也不能播"。
                    runCatching { _renderersFactory?.releaseDapNow?.invoke() }
                    runCatching {
                        com.annieplayer.annie_music.kt.core.audio.dap.DapVendorTuning.reset(
                            _context,
                            com.annieplayer.annie_music.kt.core.audio.dap.detectDapVendor(
                                manufacturer = android.os.Build.MANUFACTURER,
                                brand = android.os.Build.BRAND,
                                model = android.os.Build.MODEL,
                                product = android.os.Build.PRODUCT,
                                fileExists = { p -> runCatching { java.io.File(p).exists() }.getOrDefault(false) },
                                device = android.os.Build.DEVICE,
                            ),
                            48_000,
                        )
                    }
                    val plan = _currentPlan()
                    val key = _planKeyOf(plan)
                    val needReload = outletNeedsReload(
                        plan.deliveredRate,
                        plan.bitDepth,
                        AnnieAudioSink.lastConfigureNote,
                    )
                    // **这一次变更是不是"用户改了通路"** —— 它与"计划变了"是两件事：
                    // 通路变更要换引擎（旧引擎可能已经卡在拆/停直出流的那次 native 调用里），
                    // 计划变更只要原地换源。判据用建源时记下的那份意图，不用实测通路
                    // （实测值每次 configure 都会变，拿它比就是自激）。
                    val pathChanged = _lastAppliedPathKey != null &&
                        _lastAppliedPathKey != (if (MoreSettings.usbExclusive) "usb" else "system")
                    _lastPlanKey = key
                    _applyOutputPolicy(_lastAudioInfo)
                    // **改通路后必须强制一次"停 → 重开"。**
                    //
                    // Media3 只在"输出格式变化"时调 `AudioSink.configure`。而换通路时
                    // 新计划的格式常常和旧的一模一样（尤其残留 176400 那种），于是
                    // `configure` 不会来，`port` 还指着上一条路的死对象 —— 播放器卡在
                    // BUFFERING、`loading=false`、时长未知，只有重启 App 才恢复。实测踩到。
                    //
                    // `stop()` 会让渲染器 disable → `AudioSink.reset()` → 下次播放重新
                    // configure，这是唯一能绕开"格式判等"的正规手段。**它已经在
                    // `_setSource()` 里**（所有换源共用同一处），这里不再单独 stop/prepare。
                    if (needReload || pathChanged) {
                        _reloadCurrentSource(pathChanged = pathChanged)
                    }
                }
        }
        _scope.launch {
            // DSD 模式 / 目标率改了就**立刻**重建音源。
            //
            // 为什么必须单独接一条：`_dsdTransport` 与 `plan.sampleRate` 都是在 `_setSource`
            // 里算死的，重建之前它们不会变。少了这一条，用户在「DSD 输出」页选了 DoP，
            // 正在播的这一首仍然按旧的 PCM 送数据 —— 界面显示 DoP、声音还是 PCM，
            // 直到手动切歌才生效。
            //
            // `drop(1)` 跳过订阅时的当前值：那一次已经由首次 `setSource` 用过了。
            kotlinx.coroutines.flow.combine(
                MoreSettings.dsdMode,
                MoreSettings.dsdPcmRates,
            ) { mode, rates -> mode to rates }
                .drop(1)
                .collect {
                    // 合并 + **保证最后一次一定生效**：具体见 `_scheduleDsdSwitch()`。
                    _scheduleDsdSwitch()
                }
        }
        _scope.launch {
            // ⚠️ **这个列表必须是"所有会影响 TuningConfig 的流"的全集。**
            // 漏掉一个的症状非常隐蔽：滑块改了、状态变了、磁盘也写了，但
            // `setConfig` 从不被调用，native 一直用旧参数 —— 直到下次切歌/重启
            // 走 `_applyOutputPolicy` 时才"突然生效"。加新的调音参数时别忘这里。
            kotlinx.coroutines.flow.combine(
                MoreSettings.graphicGains,
                MoreSettings.graphicAmp,
                MoreSettings.peqBands,
                MoreSettings.peqAmp,
                MoreSettings.mseb,
            ) { _, _, _, _, _ -> Unit }
                .collect { _applyTuningParams() }
        }
    }

    /** 用当前直链重建音源（保留播放位置），让处理器链按新设置重新 configure */
    /**
     * 按**本曲**摆正重采样器。必须在建源之前调用（见调用点的注释）。
     *
     * 只做两件事，且都以本曲自己的信息为准：
     *
     * 1. 本曲是 DSD → 目标率 = `dsdFullChainRate()`（档位来自「DSD 输出」页），
     *    低通按素材档位取，`sourceBits` 固定 16（DSD 没有位深，136 dB 足够）；
     * 2. 本曲不是 DSD → **显式关闭**并清零目标率。
     *
     * 第 2 条是关键：不清的话上一首 DSD 的 176 400 会留在处理器里，把 44.1k 的普通曲目
     * 转成 176.4k（实测就是这个现象：切回普通曲目后输出显示 176.4k、时钟继续慢）。
     */
    private fun _primeResamplerFor(url: String) {
        val rs = _renderersFactory?.resampler ?: return
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull().orEmpty()
        val level = if (path.isNotEmpty()) _dsdLevelOf(path) else null
        val target = if (level != null) _dsdFullChainRate(path) ?: 0 else 0
        if (level == null || target <= 0) {
            val plan = _currentPlan()
            rs.configureFor(
                enabled = plan.sampleRate > 0,
                targetRate = plan.sampleRate,
                targetDepth = 0,
                lowpassHz = 0,
                sourceBits = resampleAttenuationBits(_lastAudioInfo?.codec, _lastAudioInfo?.bitDepth ?: 0),
                transBand = resampleTransitionBandPercent(_lastAudioInfo?.codec),
            )
            return
        }
        rs.configureFor(
            enabled = true,
            targetRate = target,
            targetDepth = PLAN_FLOAT_DEPTH,
            lowpassHz = dsdPcmLowpassHz(level, target),
            sourceBits = 16,
            transBand = resampleTransitionBandPercent(null),
        )
    }

    /** 重建风暴护栏的当前窗口状态（见 [_reloadCurrentSource] 与 `ReloadBurst`） */
    private var _reloadBurst = ReloadBurst.EMPTY

    /**
     * **「应用通路 / 计划变更」的唯一入口。**
     *
     * 落地动作由纯函数 [reloadActionFor] 决定，只有两种：
     * - [ReloadAction.rebuildEngine]：通路变更，或 sink 报过控制调用超时（引擎不可信）；
     * - [ReloadAction.swapInPlace]：只是计划变（率 / 位深 / 传输方式 / 蓝牙协商），原地换源。
     *
     * 两条路后面都有 [ConfigureVerdict] 校验：换源之后 `AnnieAudioSink.configure` 没再来，
     * 就升级成换引擎。所以这个入口**不会再出现"调用成功但没声"**这种静默失败 ——
     * 它要么真的 configure 并出声，要么留下一条 `换源未生效` 的日志。
     */
    private fun _reloadCurrentSource(pathChanged: Boolean = false) {
        // **重建风暴护栏。**
        //
        // 这一类 bug 已经出现过三次（`cfg` 涨到 1185、`_setSource` 被调 110 次），
        // 症状都是"进度条不动、一个字节都不出声"，而且从日志上看起来像音频问题。
        // 根因永远是同一类：某个判定读了**重建过程中会瞬变的状态**，于是"重建 → 判定变 → 再重建"。
        //
        // 与其每次都靠观察发现，不如在这里设上限：3 秒内超过 N 次就停手并如实记一条，
        // 让"风暴"以一条明确的日志出现，而不是变成一堆看不出因果的现象。
        //
        // 判定本身是纯函数（`reloadBurstStep`）：窗口到期就重新计数，**用户改通路时由
        // 调用方先清**（`reloadBurstReset`）—— 用户操作是新意图，不该被上一条通路
        // 失败重建的次数吃掉，否则"切一次通路"在风暴窗口里就是一次静默的空操作。
        run {
            val step = reloadBurstStep(_reloadBurst, android.os.SystemClock.elapsedRealtime())
            _reloadBurst = step.state
            if (!step.allowed) {
                RuntimeLog.record(
                    "音频",
                    "重建风暴：${RELOAD_BURST_WINDOW_MS}ms 内第 ${step.state.count} 次重建，已拦下。" +
                        "请查是否有判定读了重建过程中会变的状态（计划指纹 / 失败记忆 / 路由）",
                )
                return
            }
        }
        val p = _player ?: return
        val item = _queue.getOrNull(_currentAbsIndex) ?: return
        val url = _urlCache[_cacheKeyOf(item)]
        // **动作选择在唯一一处。** 通路变了 / 引擎不可信 → 换引擎；只是计划变 → 原地换源。
        val action = reloadActionFor(
            pathChanged = pathChanged,
            planChanged = true,
            engineSuspect = _engineSuspect(),
            hasSource = item.isNotEmpty() && url != null,
        )
        com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("R") {
            "reload action=$action pathChanged=$pathChanged suspect=${_engineSuspect()} " +
                "state=${runCatching { p.playbackState }.getOrDefault(-1)} " +
                "usbExclusive=${MoreSettings.usbExclusive} url=${url?.takeLast(24)}"
        }
        if (action == ReloadAction.none) {
            // 没有可换的源（直链还没解析出来，或缓存键因档位变化换了一版）：**不能静默 return**。
            // 那样这次重建就是彻底的空操作 —— 切模式/换设备都白做，而界面已经变了，
            // 表现成"切完没声音、切歌才恢复"。走一次完整起播，它会重新解析。
            // `_playAbsIndex` 是 suspend：这里从主线程发起，它内部自己切到 IO 解析。
            val keepMs = if (!_resumeSeekSettled) {
                _resumeSeekMs
            } else {
                runCatching { p.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
            }
            _scope.launch { _playAbsIndex(_currentAbsIndex, keepMs) }
            return
        }
        if (action == ReloadAction.rebuildEngine) {
            // 通路变更 / 引擎不可信：原地换源在这里**不可达**（播放线程可能已经停了），
            // 换一条引擎是与冷启动完全相同的路径，`configure` 必然发生。
            _rebuildEngine(
                if (pathChanged) {
                    "通路变更 → usb=${MoreSettings.usbExclusive}"
                } else {
                    "引擎不可信（sink 控制调用超时）"
                },
            )
            return
        }
        // **位置与意图必须在 `stop()` 之前读**：`stop()` 会把 `currentPosition` 归零，
        // 之后取到的一律是 0，续播就退化成"从头开始"。
        //
        // 换源与续播之间有竞态：切歌/reload 挤在一起时，`p.currentPosition` 报的可能是
        // 上一首的尾巴（实测 8.7 s 跳到 226.8 s —— 环被清空、进度条顶满、立刻又切歌）。
        // 判据用 `mediaId`（`_buildMediaItem` 里就是队列项 id），比"位置大小"可靠。
        val sameItem = p.currentMediaItem?.mediaId == item["id"].orEmpty()
        val rawPosition = if (sameItem) p.currentPosition else 0L
        // 再用时长兜一层：任何脏位置都不许把歌顶到结尾（超了就从头）
        val durMs = item["duration"].orEmpty().toLongOrNull()?.times(1000L) ?: 0L
        val position = if (durMs > 0) rawPosition.coerceIn(0L, (durMs - 1_000L).coerceAtLeast(0L)) else rawPosition
        // **按意图续播，不按 `isPlaying`。** 后者要 `playWhenReady && READY && 未被抑制` 才成立，
        // 缓冲中断 / 刚 configure 完都会短暂为 false；而它一旦为 true 就说明用户没按暂停。
        // 反过来用 `isPlaying` 判"该不该续播"，暂停中任何一次重建都可能把声音拉起来。
        // 「下一首 configure」的清理点统一在 `_setSource` 里（两条路都会经过它）
        val src = url ?: return
        val resumeTarget = if (!_resumeSeekSettled) _resumeSeekMs else 0L
        if (_setSource(src, item, resumeTarget)) {
            val seekToMs = if (resumeTarget > 0L) 0L else position
            if (seekToMs > 0) p.seekTo(seekToMs)
            if (p.playWhenReady) p.play()
        }
    }

    /**
     * 这一首要按**哪个路由**规划。
     *
     * USB 独占生效时，声音是从 USB 端点直接出去的，蓝牙 / 有线只是"插着"，不属于输出链路。
     * `currentRoute()` 不知道这一点（它只回答"现在插着什么"），照它规划会出两个错：
     * 1. 蓝牙被当成输出 → `resolveOutputPlan` 把通路强制成 systemMix → 界面显示蓝牙，
     *    而实际还在 USB 上响；同时可能为蓝牙起一条 AudioTrack（双链路同时输出 = 噪音）。
     * 2. `systemMixRateFor(bluetooth)` 去问 codec 协商率，那个率与 USB 独占毫无关系。
     *
     * 所以独占生效时统一按 `AudioRoute.usb` 规划，独占没生效才看真实插着什么。
     */
    private fun _outputRoute(out: AudioOutputController): AudioRoute =
        if (_routingKnown && _effectivePath == SinkKind.usbUac) {
            AudioRoute.usb
        } else {
            out.currentRoute()
        }

    /**
     * USB 插上后的补一次：`UsbManager` 比 `AudioManager` 慢一拍，而且**拔插后系统不保留授权**。
     *
     * 两件事必须一起做，否则表现为「插上了、进度保留了，但要等切歌才进独占」：
     * 1. 等设备出现在 `UsbManager.deviceList` 里；
     * 2. 没有授权就请求（系统框），拿到后立刻重建音源。
     */
    private fun _retryUsbAttach() {
        val device = UsbAudioDirect.audioDevices(_context).firstOrNull() ?: return
        if (!UsbAudioDirect.hasPermission(_context, device)) {
            UsbAudioDirect.requestPermission(_context, device) { granted ->
                if (granted) {
                    _applyOutputPolicy(_lastAudioInfo)
                    _reloadCurrentSource()
                }
            }
            return
        }
        _applyOutputPolicy(_lastAudioInfo)
        _reloadCurrentSource()
    }

    /** 只重建调音参数，不查设备/路由（拖动滑块时高频调用） */
    private fun _applyTuningParams(): Boolean {        val factory = _renderersFactory ?: return false
        val tuned = _tuningActiveNow()
        val loudnessOn = tuned && MoreSettings.loudnessNormalization.value
        val loudnessDb = if (loudnessOn) _lastAudioInfo?.replayGainDb ?: 0f else 0f
        val base = TuningConfig(
            engine = MoreSettings.tuningEngine.value,
            graphicGains = MoreSettings.graphicGains.value,
            graphicAmpDb = MoreSettings.graphicAmp.value,
            peqBands = MoreSettings.peqBands.value,
            peqAmpDb = MoreSettings.peqAmp.value,
            mseb = MoreSettings.mseb.value,
            loudnessDb = loudnessDb,
        )
        val eqOn = tuned && base.hasToneChange()
        // 限幅只在真的有正增益时才有意义：调音模式 + 引擎抬升或响度为正
        val limiterOn = tuned && (loudnessDb > 0f || base.hasBoost())
        factory.tuning.setConfig(if (tuned) base.copy(limiter = limiterOn) else TuningConfig(), tuned)
        return eqOn
    }
    /**
     * 把 [OutputPlan] 下发到处理器链 / 直通谓词 / mixer 请求。
     *
     * **本函数不做任何判断** —— 采样率、位深、通路、直通全部由 [resolveOutputPlan]
     * 决定，这里只负责"照着执行"和"把结果如实上报"。以前这些判断散在本函数里，
     * 于是出现「我方输出 88.2k、却请求系统按 44.1k 开 mixer」这种自相矛盾的组合。
     */
    private fun _applyOutputPolicy(info: AudioInfo?) {
        val out = _audioOutput ?: return
        val factory = _renderersFactory
        val route = _outputRoute(out)
        // 独占生效时**不请求音频焦点**：独占通路绕开 AudioFlinger，与别的 App 不共享输出，
        // 本来就没有抢占；继续持焦点只会让系统在别的 App 出声时把我们按停 ——
        // 用户明确要求「开启其他软件播放不要暂停本应用」。
        val exclusiveNow = _routingKnown && _effectivePath == SinkKind.usbUac
        if (exclusiveNow != _focusSuppressed) {
            _focusSuppressed = exclusiveNow
            _player?.setAudioAttributes(_mediaAudioAttributes, !exclusiveNow)
            RuntimeLog.record(
                "输出",
                if (exclusiveNow) "独占生效 → 不再请求音频焦点" else "恢复音频焦点处理",
            )
        }
        // ── ① 先问 bit-perfect，再定计划 ──
        // 顺序不能反：被拒时的退路是"我方转到系统实率"，而那个率决定 AudioTrack 的采样率。
        // 建完轨才知道被拒，就只能等下一首了。没有权限时**连请求都不发**
        // （见 `AudioOutputController.hasAudioRoutingPermission`）。
        val attemptable = out.canAttemptBitPerfect()
        val srcRate = info?.sampleRate ?: 0
        val srcDepth = (info?.bitDepth ?: 0).takeIf { it > 0 } ?: 16
        val requestKey = "${out.currentOutputDevice()?.id}:$srcRate:$srcDepth"
        if (attemptable && srcRate > 0) {
            if (requestKey != _bitPerfectKey) {
                _bitPerfectGranted = out.applyBitPerfectPassthrough(srcRate, srcDepth)
                _bitPerfectRequested = _bitPerfectGranted
                _bitPerfectKey = requestKey
                RuntimeLog.record(
                    "Bit Perfect",
                    "请求 mixer 保持源规格 ${srcRate}Hz/${srcDepth}bit → " +
                        if (_bitPerfectGranted) "已接受" else "被拒（将走我方 SRC 兜底）",
                )
            }
        } else {
            if (_bitPerfectRequested) {
                out.clearBitPerfectPassthrough()
                _bitPerfectRequested = false
            }
            _bitPerfectGranted = false
            _bitPerfectKey = ""
        }
        // 全链路：真实输出规格由 native（FfmpegLocalExtractor.init）回报，
        // 拿到了就以它为准 —— 采样率/位深不再拿源字段冒充输出。
        //
        // **必须在定计划之前**：位深要不要接管，取决于"我方是否真的会重采样"，
        // 而那个判断要拿**解码出来的**率（全链路时它不是容器源规格）。
        val pipeline = _currentFullChainSpec()
        val pipelineRate = pipeline?.rate?.takeIf { it > 0 } ?: 0
        // **DSD 目标率同步算，不等 pipeline 的 publish。**
        //
        // `FfmpegOutputStream.publish` 是提取器真的打开之后才回来的，而 `configureFor`
        // 在它之前就可能被调一次 —— 那一次拿到 `dsdTargetRate = 0`，于是
        // `enabled = willResample || …` 变成 false，把重采样器**关掉**，
        // 352 800 随即原样进了 AudioTrack（实测：DSD 那首 `cfg` 1→3，最后一次是
        // `cfglast=352800`，`inv=VIOLATION`）。
        //
        // `_fullChainPath` 在 `_setSource` 里就设好了，档位从本地头读，立刻可用 ——
        // 所以这里用同步值，publish 的值只作兜底。
        val dsdTargetNow = if (_fullChainPath.isNotEmpty()) {
            _dsdFullChainRate(_fullChainPath) ?: 0
        } else {
            0
        }
        val plan = _currentPlan(
            info,
            attemptable = attemptable,
            granted = _bitPerfectGranted,
            decodedRate = pipelineRate,
            dsdTargetRate = if (dsdTargetNow > 0) dsdTargetNow else pipeline?.dsdTargetRate ?: 0,
            rawPassthrough = _dsdTransport.isRaw,
        )
        // 模式可能刚切过：透明 → 锁 1.0（交给绝对音量），调音 → 恢复用户音量。
        // 不在这里同步的话，切模式的瞬间会沿用上一种模式的音量（表现为静音或爆音）。
        _syncOutputVolume()
        val tuned = plan.mode == AudioOutputMode.tuned
        val loudnessOn = tuned && MoreSettings.loudnessNormalization.value
        val loudnessDb = if (loudnessOn) info?.replayGainDb ?: 0f else 0f
        val loudnessApplied = loudnessOn && loudnessDb != 0f
        val eqOn = _applyTuningParams()
        val nativeRate = out.nativeSampleRate()
        val deviceRates = out.devicePcmRates()
        // 蓝牙下 autoRate = codec 协商率，所以「跟随设备」= SRC 到 codec 率（消掉 codec 侧那次 SRC）
        val btCodecRate = if (route == AudioRoute.bluetooth) {
            BluetoothCodec.info.value?.sampleRate ?: 0
        } else {
            0
        }
        val autoTargetRate = autoRateFor(route, nativeRate, deviceRates, btCodecRate)
        // DSD 不参与这条降级链：它的 PCM 目标率来自「DSD 输出」页，且必须是 DSD 基频的
        // 2 的幂分频。混进梯子会变成「352.8k → 48k」这种把 DSD 白转一遍的蠢事。
        //
        // 判据只看 `dsdTargetRate`（由 `dsdFullChainRate` 给出），**不看 `DsdLevel.of(sourceRate)`**：
        // 后者拿到的是 dsddec 的中间格式 352800，而 `DsdLevel` 表里存的是 1-bit 位率 2822400，
        // 永远匹配不上 —— 那个判据一直是 false，等于没有。
        val isDsd = plan.dsdTargetRate > 0
        // 我方是否真的要转：全链路时"解码出来的率"是 pipeline 报的，不是容器源规格。
        // **DSD 也在内**：352800 → 176400 这一级就是我们要的那次抽取，不能排除。
        val decodedRate = pipelineRate.takeIf { it > 0 } ?: plan.sourceRate
        val willResample = plan.sampleRate > 0 && decodedRate > 0 &&
            plan.sampleRate != decodedRate
        val needDepthConvert = plan.bitDepth != 0
        // **一次原子配置**（启用 + 目标率 + 目标位深 + 输出低通）。拆成两次调用会留窗口：
        // 音频线程的 `onConfigure` 若落在中间，看到的是"已启用 + 位深仍是旧值"，
        // 于是把 16 Bit 输出 + TPDF 抖动打开 —— 实测就是"需要 SRC 的曲目起播有底噪"。
        // 详见 `PcmResamplerAudioProcessor.configureFor` 的注释。
        //
        // 输出低通：**只有 DSD 链路给非 0**。DSD 的噪声整形把噪声整个推到 20 kHz 以上，
        // 我们的抽取只砍到目标 Nyquist（176.4k 目标 = 88.2k），再往上那一大片必须补一级。
        // 截止按**素材档位**取（40/60/80/100 kHz —— DSD 率翻倍，同频噪声降 30 dB），
        // 再按目标率夹取；策略全在 `DsdOutput.dsdPcmLowpassHz()`。
        //
        // 档位从**解码网格反推**，不需要把 `DsdLevel` 塞进 `OutputPlan`：
        // ffmpeg 的 dsddec 一个输入字节出一个 float（`frame->nb_samples = avpkt->size / ch`），
        // 所以解码率恒等于 `bitRate / 8` —— 352800 → DSD64、705600 → DSD128。
        //
        // 非 DSD 一律 0：PCM 没有超声噪声，白加一级只会多一次卷积。
        val dsdLevel = if (isDsd) DsdLevel.of(decodedRate * 8) else null
        val dsdLowpassHz = if (isDsd) dsdPcmLowpassHz(dsdLevel, plan.sampleRate) else 0
        factory?.resampler?.configureFor(
            enabled = plan.sampleRate > 0 || needDepthConvert || dsdLowpassHz > 0,
            targetRate = plan.sampleRate,
            // 位深 = 「谁做最后一次重量化，谁负责」。只要我方碰过数据（重采样 或 调音），
            // `plan.bitDepth` 恒为 `PLAN_FLOAT_DEPTH`（交出全精度），量化与抖动都留给下游
            // 那一次 —— 抖动同样由处理器在 `onConfigure` 里按「会不会舍入」自己判定。
            targetDepth = plan.bitDepth,
            lowpassHz = dsdLowpassHz,
            // 阻带按**源的实际精度**算，不按解码器出口的容器格式：
            // Apple 链路（ALAC/AAC 都走 ffmpeg）解出一律 float，若按 32 Bit 算会拿到
            // 206.91 dB / 1621 抽头，而有损源的噪声底根本用不到。推导与实测见
            // `core/audio/ResampleDepth.kt`；`0` = 不知道编码，交回处理器推断（旧行为）。
            //
            // **DSD 固定 16 Bit 档（136.32 dB）**：DSD 的 codec 名不在任何一张表里，
            // 落回 0 就等于按 float 算 → 206.91 dB。而 136 dB 的镜像已经落在任何 DAC
            // 本底（−120…−130 dB）之下，再往上加只对纯数字测量有意义。r8brain 的
            // FFT 分块会因此多吃 22% 的输入（`in_len` 2476 → 3016），这是唯一真正省 CPU
            // 的那一档（过渡带那条反而更慢，见 `ResampleDepth.kt` 的实测表）。
            // DSD256/512 的解码网格是 1.4 M/2.8 M Hz，每一分算力都要省。
            sourceBits = if (isDsd) 16 else resampleAttenuationBits(info?.codec, info?.bitDepth ?: 0),
            // 过渡带同理按源类型放宽：有损编码在 21 kHz 以上已经没有信号，
            // 2% 那条"保高频"的理由在它身上不成立，4.5% 换来一半的抽头。
            // 与 `sourceBits` 用的是**同一个** codec 判据（不能一个按有损算、一个按无损算）。
            transBand = resampleTransitionBandPercent(info?.codec),
        )
        // 蓝牙链路：**只认** codec 协商结果。读不到就留 0（界面不写这一项），
        // 绝不拿别的数字顶上 —— 这里曾经有两条"顺手"的兜底，两条都是编的：
        //   · 率退回 `nativeRate`：那是 **primary（扬声器）**输出率，不是 a2dp 输出线程率。
        //     实测 SBC/AAC 侧很常见协商 44.1k，而本机 primary 是 48k —— 顶上去会让
        //     「无需 SRC」标在一个错的率上，还把一次系统 SRC 说成没有。
        //   · 位深退回 `info.bitDepth`：那是**源**位深，与耳机侧协商值毫无关系。
        val btCodec = if (route == AudioRoute.bluetooth) BluetoothCodec.info.value else null
        val btRate = btCodec?.sampleRate?.takeIf { it > 0 } ?: 0
        val btDepth = btCodec?.bitsPerSample?.takeIf { it > 0 } ?: 0
        // 「非独占调音」的 mixer 请求在 plan 里已经算好（`sampleRate`，自洽）；
        // Bit-Perfect 的请求在第 ① 步已经发过（源率），这里不再重复发。
        val mixerTarget = plan.mixerTarget
        // ── 落在哪一级：意图 + 证据 ──
        // 纯函数只给意图（`plan.tier`），这里合上唯一的硬证据：
        // `ownSrc` 要等 native 侧的真实输出率确认。
        val tier = when {
            _effectivePath != _plannedSinkPath ->
                if (willResample) OutputTier.ownSrc else null
            // DSD 不再排除在 tier 之外：它现在**真的**由我方转（352800 → 176400），
            // 那就该如实报 `ownSrc`。以前这里写 `isDsd -> null`，是因为那时抽取在
            // swresample 里做、我方 r8brain 被排除，报什么都是假的。
            //
            // 全链路：`plan.sampleRate` 是我们要的目标率，`decodedRate` 是 native 真输出率。
            // 两者相等就说明这次不需要再转，别声称「我方 SRC」。
            plan.tier == OutputTier.ownSrc && decodedRate > 0 &&
                plan.sampleRate == decodedRate -> null
            else -> plan.tier
        }
        val bitPerfect = tier == OutputTier.bitPerfect
        // ── 候选项的完整集合 + 「是否生效」的判定基准 ──
        // 候选要**全**：平台可建（AudioDirectProbe 逐档试建的结果）+ 设备 port 声明 +
        // 蓝牙 codec 协商率 + 当前选中值。只列 device port 会漏 —— 实测本机扬声器
        // 的 port profile 只声明 48k，而 HAL 的 direct_pcm_out 能建到 192k。
        // 用户要看到完整能力，并自己决定"要不要让系统再转一次"。
        val btc = if (route == AudioRoute.bluetooth) BluetoothCodec.info.value else null
        val codecRate = btc?.sampleRate?.takeIf { it > 0 } ?: 0
        // 系统实际会用哪个率：蓝牙 = codec 协商率（实测与 a2dp output thread 一致）；
        // 非蓝牙 = primary mixer 率；bit-perfect 成功时 = 当时请求的那个率。
        val systemRate = systemMixRateFor(route, nativeRate, deviceRates, codecRate, plan.sourceRate)
        val effectiveRate = if (bitPerfect && srcRate > 0) srcRate else systemRate
        // 真峰值 / 削波：native 侧「取走即清零」，这里按曲累加。
        // 只是两次锁内读取 + 一次比较，不建轨、不分配，可以安全地放在状态构造路径上。
        factory?.resampler?.takeOutputPeak()?.let { (peak, clips) ->
            if (peak > _sessionPeak) _sessionPeak = peak
            if (clips > 0) _sessionClips += clips.toLong()
        }
        // 一次性排查：把决定胶囊文案的每个输入写一行（每首歌一次，播放线程，不碰音频线程）
        runCatching {
            val line = "at=${_statusClock.format(java.util.Date())} " +
                "want=${MoreSettings.usbExclusive} eff=${_effectivePath.key} " +
                "planPath=${_plannedSinkPath.key} planRate=${plan.sampleRate} " +
                "srcRate=${plan.sourceRate} " +
                "planTier=${plan.tier} decoded=$decodedRate willResample=$willResample " +
                "downgrade=${_downgradeNotice?.reason?.name} tier=$tier " +
                "infoRate=${info?.sampleRate} pipeline=$pipelineRate " +
                "deliv=${plan.deliveredRate} native=$nativeRate " +
                "raw=${_dsdTransport.key} tag=${_transportKey(_dsdTransport, _activeDsdLevel)} " +
                "dsdnote=${_dsdTransportNotice?.name} " +
                // 内置 DAC 那条流上一次开流的一句话结论。这台 DAP 的 logcat 不留 app 日志，
                // 没有这一栏就只能猜"为什么又降级了"。
                "dap=${com.annieplayer.annie_music.kt.core.audio.dap.NativeDapDac.lastOpenNote} " +
                // 反复 configure = 反复重建 AudioTrack = 位置追踪器反复复位（时间轴看起来会变慢）
                "cfg=${AnnieAudioSink.configureCount} cfglast=${AnnieAudioSink.lastConfigureNote} " +
                // **不变式检查**：真正交给 AudioTrack 的率必须等于计划交付率。
                // 不等就说明"计划"与"实际"脱节（实测成因：重采样器配置比曲目晚一拍）。
                "inv=" + run {
                    val cfg = AnnieAudioSink.lastConfigureNote.substringBefore('/').toIntOrNull() ?: 0
                    val want = plan.deliveredRate
                    when {
                        _dsdTransport.isRaw -> "raw"
                        cfg > 0 && want > 0 && cfg != want -> "VIOLATION"
                        else -> "-"
                    }
                } +
                    // 位置被纠正回多少（ms）。正常设备恒 0；会少算播放头的机器上等于补回来的量。
                    " crr=" + SystemOutput.lastCorrectionMs
            // **换曲必须留一行**，哪怕内容与上一首一字不差。
            //
            // 只按「字符串相同就不写」去重会骗人：连播两首同为 176.4k 的曲目时，
            // 状态行的每个字段都一样，于是新曲一行都没有 —— 文件 mtime 停在上一首，
            // 事后看会以为"状态没更新"（这个坑真踩过一次）。
            // 所以判据加上"AudioInfo 换了实例"，并在行首打上时刻。
            if (line != _lastStatusLine || info !== _lastStatusInfo) {
                _lastStatusLine = line
                _lastStatusInfo = info
                java.io.File(_context.filesDir, "audio-status.txt").appendText(line + "\n")
            }
        }
        _emit(
            mapOf(
                "audioStatus" to AudioOutputStatus(
                    mode = plan.mode,
                    route = route,
                    deviceName = out.currentDeviceName(),
                    nativeRate = nativeRate,
                    nativeDepth = _mixerDepthCache.first,
                    nativeFloat = _mixerDepthCache.second,
                    deviceMaxRate = deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }
                        .maxOrNull() ?: nativeRate,
                    deviceSampleRates = deviceRates,
                    effectiveRate = effectiveRate,
                    // 「设备这一级」的位深：蓝牙 = codec 协商位深（同 `_deviceDepthFor`），
                    // 非蓝牙才用 mixer 能力（float 优先，Media3 的 enableFloatOutput 会 upcast）
                    sinkDepth = _deviceDepthFor(route),
                    // 与 `sinkDepth` **同源**：实测说 32 是 float 才算 float。
                    // 探针失败过就退回 `AudioCapabilities` 的结论（那时也没有更好的依据）。
                    sinkFloat = if (AudioDirectProbe.sinkDepth() > 0) {
                        AudioDirectProbe.sinkFloat()
                    } else {
                        _mixerDepthCache.second
                    },
                    autoTargetRate = autoTargetRate,
                    bluetoothRate = btRate,
                    bluetoothDepth = btDepth,
                    bluetoothCodec = btCodec?.codecName.orEmpty(),
                    bluetoothBitrate = btCodec?.bitrate ?: 0,
                    sourceRate = plan.sourceRate,
                    sourceDepth = plan.sourceDepth,
                    pipelineRate = pipeline?.rate ?: 0,
                    pipelineDepth = pipeline?.bits ?: 0,
                    dsdTargetRate = plan.dsdTargetRate,
                    dsdTransport = _dsdTransport,
                    dsdDowngradeReason = _dsdTransportNotice,
                    dsdRawAlt = _usbDacCaps?.hasRawAlt == true,
                    rateFallback = plan.fallback,
                    // 实测可用最高率（只读缓存，不建轨）—— 与 `deviceMaxRate`（声明）并列，
                    // 用户能看出"设备说自己能、实际建不出来"这件事
                    probedMaxRate = SystemSinkProbe.knownHighest(
                        candidates = SystemSinkProbe.PROBE_CANDIDATES,
                        deviceId = out.currentOutputDevice()?.id ?: 0,
                        route = route,
                        depth = 16,
                    ),
                    bitPerfect = bitPerfect,
                    eqOn = eqOn,
                    loudnessOn = loudnessApplied,
                    replayGainDb = info?.replayGainDb ?: 0f,
                    decode = plan.decode,
                    outputPath = _plannedSinkPath,
                    effectivePath = _effectivePath,
                    downgrade = _downgradeNotice,
                    streamDepth = _streamDepth,
                    didResample = willResample,
                    // int → float 只是换容器，不算改位深（判据在 `OutputPlan.didConvertDepth`）
                    didConvertDepth = needDepthConvert && plan.didConvertDepth,
                    // `plan.deliveredRate` 才是**交给 sink 的率**；`pipeline.rate` 是 native 的
                    // 解码出口，两者在 DSD 上必然不同（352.8k 网格 → 176.4k 抽取）。
                    // 曾经拿 pipeline 覆盖它，结果界面只剩「352.8 → 设备」，
                    // 用户选 176.4k 却被显示成没生效。
                    deliveredRate = plan.deliveredRate,
                    deliveredDepth = pipeline?.bits?.takeIf { it > 0 } ?: plan.deliveredDepth,
                    mixerTarget = mixerTarget,
                    tier = tier,
                ),
            ),
        )
    }

    /**
     * 当前这一首的输出计划 —— **唯一入口**。
     *
     * 播放层（重建音源时取 `sampleRate`）、状态上报、设置页（档位候选）都读这一份，
     * 不允许任何地方再自己拼条件。判断本身在纯函数 [resolveOutputPlan] 里，可单测。
     *
     * 全链路的判定复用 [FfmpegLocalRoute]（唯一判据）。
     */
    private fun _currentPlan(
        info: AudioInfo? = _lastAudioInfo,
        /** 本路由是否值得请求 bit-perfect（路由 + 权限）；默认就地再问一次 */
        attemptable: Boolean = _audioOutput?.canAttemptBitPerfect() ?: false,
        /** 上一次请求的结果；不是 attemptable 时一律按"没拿到"算 */
        granted: Boolean = _bitPerfectGranted,
        /**
         * **解码出来的**采样率（全链路时是 native 回报值）；0 = 未知。
         * 它决定"我方是否真的会重采样"，也就决定了位深要不要由我们接管。
         */
        decodedRate: Int = 0,
        /**
         * DSD 的最终 PCM 目标率（44.1 家族）；0 = 不是 DSD。
         * 有值时压过整条采样率梯子 —— 详见 [resolveOutputPlan] 的同名参数。
         */
        dsdTargetRate: Int = 0,
        rawPassthrough: Boolean = false,
    ): OutputPlan {
        val out = _audioOutput
        val item = _queue.getOrNull(_currentAbsIndex)
        val url = item?.get("url").orEmpty()
        val localPath = if (url.startsWith("file://")) {
            runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        } else {
            ""
        }
        val sourceRate = info?.sampleRate ?: 0
        val mime = _mimeOfCodec(info?.codec)
        val route = if (out != null) _outputRoute(out) else AudioRoute.other
        val policy = MoreSettings.playbackPolicy()
        val caps = AudioDirectProbe.capabilities()
        val aaudioReady = policy.aaudioReady(caps, sourceRate)
        val codecRate = if (route == AudioRoute.bluetooth) {
            BluetoothCodec.info.value?.sampleRate ?: 0
        } else {
            0
        }
        val usbDirectNow = policy.usbExclusive && route == AudioRoute.usb
        val plannedChain = resolveSinkChain(
            SinkRequest(
                route = route,
                usbExclusive = policy.usbExclusive,
                rawFrames = false,
                dsdNativeAvailable = _dapWorkingNow() != null,
                aaudioExclusiveAvailable = aaudioReady,
            ),
        )
        val plannedHead = plannedChain.firstOrNull()
        _plannedSinkPath = plannedHead ?: SinkKind.audioTrack
        return resolveOutputPlan(
            mode = policy.modeAt(route == AudioRoute.bluetooth),
            route = route,
            nativeRate = out?.nativeSampleRate() ?: 0,
            deviceRates = out?.devicePcmRates().orEmpty(),
            codecRate = codecRate,
            sourceRate = sourceRate,
            sourceDepth = info?.bitDepth ?: 0,
            decodedRate = decodedRate,
            aaudioExclusive = aaudioReady,
            followSourceRate = policy.toggles.followSourceRate,
            fullChain = localPath.isNotEmpty() && FfmpegLocalRoute.useFullChain(localPath),
            dsdTargetRate = dsdTargetRate,
            rawPassthrough = rawPassthrough,
            exclusiveSink = plannedHead == SinkKind.usbUac || plannedHead == SinkKind.oboe,
            bitExactSink = plannedHead == SinkKind.usbUac,
            exclusiveDepth = caps.exclusiveNativeDepth,
            bitPerfectGranted = attemptable && granted,
            // 能不能建出这个率 —— **两层判据**，缺一不可：
            //
            // ① `platformCanBuildRate`：`AudioFormat` 的 API 契约（192k 硬上限）。它不会误伤 ——
            //    拿不到合法 `AudioFormat` 就调不到 `AudioTrack`。但它也不充分。
            // ② `SystemSinkProbe`：真建一次轨。只有超过 192k 时才做 —— 那些档位要么 framework
            //    被厂商放开过（能建）、要么就是建不出来，API 契约管不到这一段。
            //    192k 以内不做：AOSP 的 HAL 都支持常见档位，每次定计划白建一次轨毫无收益。
            //
            // 探测按 (deviceId, route, rate, depth) 缓存，同一台设备只建一次；
            // 设备/路由变化时 `SystemSinkProbe.invalidate()` 清空。
            canBuildRate = { rate ->
                // 独占通路上这两层判据**都不适用**：`AudioFormat` 的 192k 是 AudioTrack
                // 的契约，`SystemSinkProbe` 建的是 AudioTrack 的轨，而我们写的是 UAC2 端点。
                // 拿它们卡独占，结果就是「DAC 明明支持 352.8k，却被判成建不出来」。
                usbDirectNow ||
                    (
                        platformCanBuildRate(rate) && (
                            rate <= AUDIO_FORMAT_MAX_RATE ||
                                SystemSinkProbe.canBuild(
                                    deviceId = out?.currentOutputDevice()?.id ?: 0,
                                    route = route,
                                    rate = rate,
                                    // 探的是**这个率**，所以用兼容性最好的 16 Bit：
                                    // 16 都建不出来，24 / float 更不用谈；反过来 16 能建、
                                    // float 不能，那是位深问题（Media3 的 sink 自己会降），
                                    // 不该在这里判成"这个率不能用"。
                                    depth = 16,
                                )
                        )
                    )
            },
        )
    }

    /**
     * 这一首 DSD **实际**用的传输方式。
     *
     * 与 `plan.sampleRate` 是两条正交的轴：后者说"抽到哪个率"，这个说"抽不抽"。
     * 只有 [DsdTransport.pcm] 会真的抽；另外两个把 1-bit 原样送出去。
     */
    @Volatile
    private var _dsdTransport: DsdTransport = DsdTransport.pcm

    /**
     * 用户意图 →（带通路与设备约束的）实际传输方式。
     *
     * 判定本身在纯函数 [decideDsdTransport] 里（可单测），这里只负责**沿降级链走**。
     * **只信实测通路**：设置里的意图在降级后仍是"意愿"，而 DoP 帧一旦经过 mixer，
     * 音量那次 float 乘法就会把 0x05 / 0xFA 标记改写掉 —— 听到的是满幅噪声。
     *
     * ## 为什么必须显式走链，而不是"降一级再问一次"
     *
     * 以前是先把意图改写成下一级、再整体问一次 `decideDsdTransport`。那样有两个坑：
     *
     * 1. Native 不可用时意图被改写成 DoP，于是 `decide` 的返回值**直接当结论**——
     *    DoP 若在同一次判定里也不成立，就一路掉到 PCM，看起来像"跳过了 DoP"。
     * 2. 失败记忆是**按传输方式**记的（`native` / `dop`），播过一次 DSD512 的失败会把
     *    整台设备的该方式永久否掉，之后 DSD64 也不再用它。
     *
     * 现在逐个候选问，第一个**能够成立**的胜出；失败记忆按 `方式@档位` 分。
     */
    private fun _pathForDsd(wanted: DsdTransport, aaudioDopReady: Boolean): SinkKind {
        val out = _audioOutput
        val route = out?.currentRoute() ?: AudioRoute.other
        val dapReady = _dapWorkingNow() != null
        val dopPayload = wanted == DsdTransport.dop ||
            (wanted == DsdTransport.native && !dapReady)
        val chain = resolveSinkChain(
            SinkRequest(
                route = route,
                usbExclusive = MoreSettings.usbExclusive,
                rawFrames = true,
                dsdNativeAvailable = dapReady,
                aaudioExclusiveAvailable = aaudioDopReady,
                dopFrames = dopPayload,
            ),
        )
        return chain.firstOrNull() ?: SinkKind.audioTrack
    }

    /**
     * Native raw DSD 的 32 位字要不要按小端打。
     *
     * **现在恒 false（= 字节顺序就是时间顺序，与 USB 独占那条路一致）。**
     *
     * 原先按容器名推：`0x1A000002` 自报 `DSD_U32_LE`，于是每 4 字节反转一次。
     * 实测结果是**一开声就是噪声**（JM21，18:30:28 那次 `configRawPack mode=2 be=0`）。
     * 原因想清楚了：`DSD_U32_LE` 描述的是"这个 32 位**数值**怎么存"，而 DAC 的
     * 1-bit 解调器**按时序把位挤出去** —— 每 4 字节把时序倒一遍，等于每 4 字节
     * 往回跳一次，任何解调器都只能解出白噪。
     *
     * 而位序（`bitReverse`）保持 true：集内四种 DSD 采样格式（`DSD_U8/16/32`）
     * 都约定**字节内 bit7 是最早的样本**，DSF 文件是 LSB-first，所以必须反转过来。
     *
     * 这条结论还用一次实测兜底：`files/dsd-pack.txt` 写 `rotate`，每次开流换一种组合，
     * 四次之内必能听出哪一种对（见 `DapPackOverride`）。
     */
    private fun _nativeDapWordsLittleEndian(transport: DsdTransport): Boolean = false

    /**
     * 这台机器上**实测成立**的那一组内置 DAC 参数（快照）。
     *
     * ## 为什么要有这个快照，而不是每次去读 `DapCapabilityProbe.current`
     *
     * 探测是在**空闲**时跑才有效：私有 format 走的那条 direct 端口常常只允许开一条流，
     * 播放中重测必然全失败，`working` 会变成 `null`。而"这台机器有没有内置 DAC 直通"
     * 是**机器属性**，不该被一次播放中的重测推翻。
     *
     * 实测症状正是这个：同一秒里先 `tag=native@2822400`（选上了），下一拍就
     * `DapNotMeasured`（`working` 没了）→ 又降回 PCM，来回抖。
     */
    @Volatile
    private var _dapWorking: com.annieplayer.annie_music.kt.core.audio.dap.DapFormatVerdict? = null

    /** 我们自己开/关内置 DAC 直通流的时刻（判断设备变化是不是自方回声） */
    @Volatile
    private var _dapEchoAt = 0L

    /**
     * 这次设备变化是不是"我方刚开关了内置 DAC 的 DIRECT 轨"引起的。
     *
     * 与 `UsbAudioDirect.selfEcho()` 同源的问题：那条 DIRECT 输出线程一建一拆，
     * 系统就看到输出设备增删。不挡住就会「重建 → 设备事件 → 再重建」自激，
     * 而代价是整条探测矩阵重跑 + 两次 `_reloadCurrentSource()`。
     */
    private fun dapSelfEcho(): Boolean =
        android.os.SystemClock.elapsedRealtime() - _dapEchoAt in 1..DAP_SELF_ECHO_MS

    /** 只在**没有在播**时刷新快照：播放中重测的结论不可信（端口被自己占着） */
    private fun _refreshDapWorking() {
        if (_player?.isPlaying == true || _player?.playWhenReady == true) return
        _dapWorking = com.annieplayer.annie_music.kt.core.audio.dap.DapCapabilityProbe
            .current?.working
    }

    /**
     * 实测成立的组合 —— **读的时候才落快照**。
     *
     * 只有 [_refreshDapWorking] 一个写入口不够：它挂在启动那条探测线程上，只在
     * `annie-output-probe` 里试一次，而且有"在播就不写"的门。于是"启动即播放"的用户
     * 永远拿不到快照，`_dapWorking` 一路是 `null`：
     *
     * ```
     * want=dap-direct  dap=skip: 无实测可用组合  dsdnote=NotMeasured  → 全程走系统通路
     * ```
     *
     * 而 `files/dap-capability.txt` 里明明有一条
     * `dsd-vendor … writes=5 idle=0 frames=1319` —— 设置全对，路却不通。实测踩到。
     *
     * 现读是安全的：`DapCapabilityProbe` 的 `kicked` 保证进程内只真测一次，
     * `current` 只有 `reset()`（用户点「重新探测」）才会失效，不会自己在播放中变。
     * 真遇到"边播边重测"，那次探测的候选全都 `idle=41`（无消费者）→ `working == null`
     * → 现读也只是回到"没测到"，不会把错的当成对的。
     */
    private fun _dapWorkingNow(): com.annieplayer.annie_music.kt.core.audio.dap.DapFormatVerdict? {
        _dapWorking?.let { return it }
        val live = com.annieplayer.annie_music.kt.core.audio.dap.DapCapabilityProbe.current?.working
        if (live != null) _dapWorking = live
        return live
    }

    private fun _resolveDsdTransport(level: DsdLevel?): DsdTransportDecision {
        val wanted = when (MoreSettings.dsdMode.value) {
            DsdOutputMode.pcm -> DsdTransport.pcm
            DsdOutputMode.dop -> DsdTransport.dop
            DsdOutputMode.native -> DsdTransport.native
        }
        val dopExclusive = _aaudioDopExclusive(level)
        return resolveDsdChain(
            wanted = wanted,
            level = level,
            sink = _pathForDsd(wanted, dopExclusive),
            routingKnown = _routingKnown,
            caps = _usbDacCaps,
            // 内置 DAC 直通有没有**实测**通。只有它是 true，`path == dapDirect` 时才肯给
            // `native`；否则按"没测过"降到 PCM（`DapNotMeasured`）。
            // 用 `_dapWorkingNow()` 而不是裸字段：快照有就用快照（防"播放中重测"），
            // 没有就现读探测结论（补启动窗口那条缝，理由见它的注释）。
            dapNativeReady = _dapWorkingNow() != null,
            aaudioDopReady = dopExclusive,
            failed = { _transportKey(it, level) in _failedTransports },
        )
    }

    /**
     * DoP 帧走 AAudio 独占的前提：**这个帧率 + 24 bit** 在探测矩阵里被判成独占。
     *
     * DoP 的帧就是 24 bit PCM（DSD64 → 176 400 Hz），所以它能用 AAudio 的唯一条件，
     * 是这台机器的 policy 声明了该率、该格式的 MMAP 端口，并且探测真的开出过独占流。
     * 矩阵里没有这个组合时恒 false —— 例如 JM21 的 `mmap_no_irq_out` 只有 48k/16，
     * 于是 DoP 在那儿永远走不到 AAudio（请求未声明组合会把厂商 HAL 打死，实测过）。
     */
    private fun _aaudioDopExclusive(level: DsdLevel?): Boolean {
        if (!MoreSettings.aaudioExclusive) return false
        val rate = level?.dopFrameRate ?: return false
        if (rate <= 0) return false
        return AudioDirectProbe.probedExclusive(
            rate,
            com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream.FORMAT_I24,
        ) == true
    }

    /** 失败记忆的键：`方式@档位`。档位未知（非 DSD）时不参与 */
    private fun _transportKey(transport: DsdTransport, level: DsdLevel?): String =
        "${transport.key}@${level?.bitRate ?: 0}"

    /**
     * USB 直连时读到的 DAC 能力。`null` = 还没读过 —— 那时 DoP / Native 一律降 PCM。
     *
     * 读它要真的解析一次描述符，而解析又必须在**开流之前**完成（否则 DoP 的第一帧
     * 已经送出去了）。所以这里有缓存：`UacEngine` 每次开流把结果留在 native 侧，
     * 首次起播仍走 PCM，探到能力后由 `_reloadCurrentSource()` 切到 DoP / Native。
     */
    @Volatile
    private var _usbDacCaps: com.annieplayer.annie_music.kt.core.audio.dsd.UsbDacCaps? = null

    /** 读到 [_usbDacCaps] 时的输出设备 id；换了设备就重读 */
    private var _usbDacCapsDeviceId: Int = -1

    /**
     * 本次会话里**已经失败过**的 `方式@档位`（见 [_transportKey]）。
     *
     * 设备接不住某个档位（例如 DSD512 的 raw word 率）时，不记就会每首都白试一次，
     * 表现为「每首歌开头先静音/爆一下再降级」。换设备时清空 —— 那是另一台 DAC 的事了。
     *
     * **必须按档位分**：按方式记的话，播过一次 DSD512 失败就把整台设备的 Native 永久否掉，
     * 之后连 DSD64 都不敢试。
     */
    private val _failedTransports =
        java.util.concurrent.CopyOnWriteArraySet<String>()

    /**
     * 当前这一首的 DSD 档位；非 DSD 为 `null`。
     *
     * 失败记忆要按档位分键，而失败回调（`onAaudioFailure`）里拿不到源码路径，
     * 所以在这里留一份。
     */
    @Volatile
    private var _activeDsdLevel: DsdLevel? = null

    /** 这一首 DoP / Native 没走成的原因；null = 意图成立或本来就不是 DSD */
    @Volatile
    private var _dsdTransportNotice: com.annieplayer.annie_music.kt.core.audio.dsd.DsdDowngradeReason? = null

    /**
     * 排队中的那次切模式重建。保存句柄是为了**取消上一次**：切歌之后陈旧的那次
     * 不该再跑一遍（白多一次断音）。
     */
    private var _dsdSwitchTask: Runnable? = null

    /** 出口校验的重试计数（见 `_scheduleDsdSwitch`），防止病态循环 */
    private var _dsdSwitchRetries = 0

    /**
     * 请求"按当前 DSD 模式重建音源"。
     *
     * 这里的**合并**不是简单地把重复请求丢掉 —— 那样会漏掉最后一次：
     * 一次重建要全量拆流（`stopStream` 等 inFlight 归零，上限 1 s）再 `SET_INTERFACE` /
     * `SET_CUR` / 重开 URB，窗口远大于 [DSD_SWITCH_COALESCE_MS]。用户在重建**执行期间**
     * 又点一次，若只按"pending 就丢弃"处理，那次选择就永远不会被应用：
     * 流是按旧模式建的、界面显示新模式 —— 听感就是"切完没声音"。
     *
     * 所以：入口只保留最后一次（`removeCallbacks` 掉还没跑的），**出口再校验一次** ——
     * 跑完之后如果模式又变了，补跑一次（有界）。
     */
    private fun _scheduleDsdSwitch() {
        // 还没跑的那次作废，用最后这次替换它
        _dsdSwitchTask?.let { _mainHandler.removeCallbacks(it) }
        _dsdSwitchRetries = 0
        postDsdSwitchTask()
    }

    private fun postDsdSwitchTask() {
        val task = object : Runnable {
            override fun run() {
                _dsdSwitchTask = null
                val player = _player ?: return
                if (player.mediaItemCount == 0) return
                val appliedMode = MoreSettings.dsdMode.value
                val appliedRates = MoreSettings.dsdPcmRates.value
                // 用户显式改模式 = 重新给这条路一次机会（失败记忆只该驱动自动降级，
                // 不该压过用户的选择）。只在**真的重建**时清，被合并掉的那几次不清。
                _failedTransports.clear()
                _applyOutputPolicy(_lastAudioInfo)
                _reloadCurrentSource()
                // **出口校验**：这次重建期间用户可能又改了 —— 那样本次构建的就是旧模式。
                val changed = MoreSettings.dsdMode.value != appliedMode ||
                    MoreSettings.dsdPcmRates.value != appliedRates
                if (changed && _dsdSwitchRetries < DSD_SWITCH_MAX_RETRIES) {
                    _dsdSwitchRetries++
                    postDsdSwitchTask()
                } else {
                    _dsdSwitchRetries = 0
                }
            }
        }
        _dsdSwitchTask = task
        _mainHandler.postDelayed(task, DSD_SWITCH_COALESCE_MS)
    }

    /**
     * 这首本地文件的 DSD 档位；不是 DSD（或读不出来）返回 null。
     *
     * 数据源按可靠性排：曲库索引（扫描时已按 1-bit 真实率命名）→ 现读文件头。
     * 不读 `_lastAudioInfo`：它在 `_setSource` 之后才更新（那里面是上一首的规格）。
     */
    private fun _dsdLevelOf(localPath: String): DsdLevel? {
        val indexed = com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
            .trackOf(localPath)?.sampleRate?.takeIf { it > 0 }
        val sourceRate = indexed
            ?: runCatching {
                com.annieplayer.annie_music.kt.data.local.LocalHeaderProbe
                    .probe(File(localPath))?.sampleRate
            }.getOrNull()
            ?: 0
        return DsdLevel.of(sourceRate)
    }

    /**
     * 这一首若是 DSD，返回全链路要用的目标率；否则 null（调用方走常规 `OutputPlan.sampleRate`）。
     *
     * **判 DSD 不能看 `_lastAudioInfo`。** 它是异步探测的结果，而 `_setSource` 在
     * `_startAudioInfoProbe` **之前**执行（起播顺序固定），所以那一刻 `_lastAudioInfo`
     * 还是**上一首**的规格：首播 DSD 会拿不到档位、落回常规 plan
     * （实测：本该 176.4 kHz，显示成 48 kHz）。本地文件有更可靠的来源：
     * 曲库索引里存的就是 DSD 的 **1 bit 真实率**（`LocalHeaderProbe` 按 2822400 命名 dsd64…），
     * 没扫进曲库时再读一次文件头 —— dsf/dff 的头只有几十字节，1~2ms。
     *
     * 判定全在纯函数 [dsdFullChainRate] 里（可单测），这里只负责凑它的入参。
     */
    private fun _dsdFullChainRate(localPath: String): Int? {
        val level = _dsdLevelOf(localPath) ?: return null
        val out = _audioOutput
        val route = out?.currentRoute() ?: AudioRoute.other
        val declared = out?.devicePcmRates().orEmpty()
        // 独占通路的上限与系统建轨无关，见 `dsdFullChainRate`。
        // 判据与 `_currentPlan` 的 `path` 一致：没实测过就用设置里的意图。
        // **并且要求设备真的在**：拔掉 DAC 后两者都可能还停在 usbDirect 一拍，
        // 那时放开 352.8k 只会让 AudioTrack 建轨失败。
        val exclusive = route == AudioRoute.usb && MoreSettings.usbExclusive
        return dsdFullChainRate(
            level = level,
            configuredRate = MoreSettings.dsdPcmRateOf(level),
            route = route,
            codecRate = if (route == AudioRoute.bluetooth) {
                BluetoothCodec.info.value?.sampleRate ?: 0
            } else {
                0
            },
            // 上限取**这台输出声明的** 44.1 家族最高档，**不再真建轨去探**。
            //
            // 原来这里调 `SystemSinkProbe.highestUsable(DSD_PCM_RATES, …)`，它会从高往低
            // 真建轨（705600 → 384000 → 352800 → 176400 …）。每一次建轨都会让 AudioFlinger
            // **重配共享输出**（`createTrack:updateParams = 1 sample_rate=… format=…`），
            // 而共享输出**不会恢复**：
            //
            //   15:39:48  普通 44.1k 歌：native(=PROPERTY_OUTPUT_SAMPLE_RATE) = 48000
            //   15:40:02  点开 DSD 那一刻：`updateParams = 1 sample_rate=705600 format=5`
            //             同一条状态行 native 变成 176400
            //   15:40:07  切回同一首 44.1k 歌：native 仍是 176400 —— 只有重启 App 才回 48000
            //
            // **以上只是时间相关，不是因果证明。** 已知为真的是"建轨会让 AudioFlinger 重配
            // 共享输出"（`updateParams = 1` 就是它），所以删掉建轨的理由是**消除这个副作用**；
            // 而"176.4k 是它造成的"仍未证实 —— 独立审查给的另一种解释是：这套 ROM 把
            // `deep_buffer` 输出本身钉在 176400（它的 profile 起点是 44100，说明不是我们请求的率）。
            // 若要判定归因，看的是"删掉建轨之后 DSD→普通歌 是否还变 176.4k"。
            // 那一步没做之前，不要在这段注释里写成结论。
            //
            // 代价：声明表不全的设备会被低估（原注释里担心过这点）。但这是一次**上限**，
            // 不是选择结果 —— 用户选的档位只要 ≤ 上限就照用，探不到时退回 176.4k 的保守值。
            maxBuildRate = if (exclusive) {
                0
            } else {
                dsdMaxPcmRate(
                    declared.filter { it > 0 && it % 44_100 == 0 }.maxOrNull() ?: 0,
                )
            },
            // 系统 mixer 实际会用的率 —— 只在档位选「跟随系统」时用
            systemRate = systemMixRateFor(
                route = route,
                nativeRate = out?.nativeSampleRate() ?: 0,
                deviceRates = declared,
                codecRate = 0,
                sourceRate = level.bitRate,
            ),
            // 独占通路的 DSD 上限不该由系统建轨探针决定，见 `dsdFullChainRate`。
            exclusive = exclusive,
        )
    }

    /**
     * 会影响 AudioSink `configure` 的那几个值；只用于判断"要不要重建音源"。
     *
     * ⚠️ **绝不能把 `plan.path` 放进来。** 它来自 `_effectivePath`，也就是 Sink 上报的
     * **播放结果**；把它编进指纹，指纹就不再是自变量的函数，而是"意图 × 上次结果"的函数：
     * 一次通路翻转（`usbDirect ⇄ systemMix`）会同时改 `path` 与 `sampleRate`，
     * 于是「翻转 → 指纹变 → 重建 → configure → 再翻转」形成没有收敛项的自激环，
     * 表现为 audio focus 每 3 秒 request→abandon 数次、整条独占流被反复拆开（听感全哑）。
     *
     * 通路切换**确实**会改变 `configure` 的参数，但那个改变已经全部体现在
     * `sampleRate` / `bitDepth` / `decode` 上（独占按源率 → `0`，系统通路 → 实际率），
     * 所以去掉 `path` 不会漏掉任何真正需要重建的场景。
     */
    /**
     * 计划指纹 —— **决定"要不要重建音源"**。
     *
     * 必须含 DSD 传输方式：`raw`（DoP / Native）与 `pcm` 的率/位深可以**一模一样**，
     * 但源是按哪一种**建**出来的（`openRaw` + 打包 vs `open` + 解码），
     * 只改 `_dsdTransport` 而不重建，等于拿 PCM 源去喂 raw 通路（或反过来）。
     *
     * 实测踩到：通路切到「播放器内置 DAC」后，判定从 `NotMeasured` 变成 `native`，
     * 但 `decode:rate:bitDepth` 三个字段一个都没变 → `needReload` 不成立 → 一直播 PCM。
     */
    /**
     * 计划指纹用的**档位快照** —— 与 [_activeDsdLevel] 分开的第二个字段。
     *
     * 为什么不能直接读 `_activeDsdLevel`：那个字段在 `_setSource` **开头**就被清成 null
     * （每首曲目都要归位），而 `_planKeyOf` 会在任意时刻被路由回调调用。于是重建期间的
     * 任意一次路由上报都会算出 `…:pcm` 的指纹，与上一次的 `…:native` 不同 →
     * 判定"计划变了" → 再重建 → 再清空 → **无限重建**（实测 `_setSource` 被调了 110 次，
     * 播放器一个字节都没播出去）。
     *
     * 这个字段只在**建源完成时**更新，因此重建过程中它是稳定的。
     */
    @Volatile
    private var _planLevel: DsdLevel? = null

    private fun _planKeyOf(plan: OutputPlan): String =
        "${plan.decode}:${plan.sampleRate}:${plan.bitDepth}:" +
            // **必须含"用户选的通路"**：切通路时 sink 必须重建（旧后端不能继续挂着）。
            // 只含 decode/rate/depth 的话，从「内置 DAC」切回「系统 · AudioTrack」时
            // 指纹不变 → `needReload=false` → 不重建 → `eff` 还停在 dap-direct、
            // 一个字节都不出声，只有重启才恢复（实测）。
            // 用**意图**而不是实测通路：实测值会在每次 configure 后变化，拿它会自激。
            "${MoreSettings.usbExclusive}:" +
            "${MoreSettings.aaudioExclusive}:" +
            _resolveDsdTransport(_planLevel).transport.key


    /**
     * 探测到的 codec 名 → Media3 mime。
     *
     * ⚠️ 以前它**只服务 offload 直通**（判断"这一首能不能直通"）。offload 已整条删除，
     * 现在只用于格式归属的判断，不再参与任何通路决策。
     */
    private fun _mimeOfCodec(codec: String?): String? = when (codec?.lowercase()?.trim()) {
        "aac", "mp4a" -> androidx.media3.common.MimeTypes.AUDIO_AAC
        "mp3", "mp2", "mpeg" -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        "opus" -> androidx.media3.common.MimeTypes.AUDIO_OPUS
        "ac3", "ac-3" -> androidx.media3.common.MimeTypes.AUDIO_AC3
        "eac3", "ec-3", "e-ac-3" -> androidx.media3.common.MimeTypes.AUDIO_E_AC3
        "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
        "alac" -> androidx.media3.common.MimeTypes.AUDIO_ALAC
        "ape" -> "audio/ape"
        "vorbis" -> androidx.media3.common.MimeTypes.AUDIO_VORBIS
        else -> null
    }

    /**
     * 系统 mixer 的位深（16 / 24 / 32）+ 是否 float。
     *
     * Android 没有位深属性可读，只能问 `AudioCapabilities`：设备**真的**支持的最高精度
     * PCM 编码就是 mixer 的位深。拿不到就返回 0，界面不显示，绝不猜一个值。
     */
    /**
     * 设备音频能力缓存。
     *
     * **只能在后台线程通过 [_refreshAudioCapsIfNeeded] 写入，读取方一律只读缓存。**
     * 绝对不能在状态构造 / 播放路径上触发探测：`AudioCapabilities.getCapabilities()`
     * 内部会真的建 AudioTrack，与播放器自己的建轨并发时会抢资源
     * （本机出现过 `compute_offload maxOpenCount=1` 被探测轨占用、
     * `AudioTrack` 建轨失败导致整首播不了）。
     */
    @Volatile
    private var _caps: androidx.media3.exoplayer.audio.AudioCapabilities? = null

    /** 缓存是否需要重算（设备变更时置 true，由后台线程消费） */
    @Volatile
    private var _capsDirty: Boolean = true

    @Volatile
    private var _mixerDepthCache: Pair<Int, Boolean> = 0 to false

    @Volatile
    private var _supportedDepthsCache: List<Int> = emptyList()

    /**
     * 「设备这一级」的位深 —— 界面的「跟随设备」、位深下拉的"实际 sink"、
     * `OutputPlan.bitDepth` 全都读这一个值。
     *
     * 判定本身在纯函数 [resolveDeviceDepth] 里（可单测）。来源优先级：
     *
     * 1. **蓝牙 → codec 协商位深**（`getCodecStatus`，mixer 对它一无所知）；
     * 2. **非蓝牙 → 实测 sink 位深**（`AudioDirectProbe.sinkDepth()`，真建一次
     *    float / 24 / 16 的 AudioTrack，谁建得出来就是谁）；
     * 3. 都没拿到才退回 `AudioCapabilities` 的结论。
     *
     * 为什么非要实测：本机 `AudioCapabilities.getCapabilities()` **只报 PCM_16**，
     * 而 AudioFlinger 主线程的 processing format 就是 `PCM_FLOAT`、float 轨也建得出来。
     * 照它把「跟随设备」钉在 16 bit，等于在无损曲目上白丢 8 位（实测听感就是这么差的）。
     */
    private fun _deviceDepthFor(route: AudioRoute): Int {
        val probed = AudioDirectProbe.sinkDepth()
        return resolveDeviceDepth(
            route = route,
            codecDepth = if (route == AudioRoute.bluetooth) {
                BluetoothCodec.info.value?.bitsPerSample ?: 0
            } else {
                0
            },
            mixerDepth = if (probed > 0) probed else _mixerDepthCache.first,
            // 实测已经给出确定值（含 float 记成 32 的语义），不再让框架的 float 标志覆盖它
            mixerFloat = probed <= 0 && _mixerDepthCache.second,
        )
    }

    /**
     * 后台刷新一次能力缓存。
     *
     * **无条件重算**（不做 "非 dirty 就跳过"）：调用点本来就只有冷启动和设备变更两处，
     * 每一次都该拿到最新结果。之前加了 dirty 判断却没人置回 true，
     * 导致切换设备后位深可选档位永远停在首次探测的结果（本机踩过）。
     */
    private fun _refreshAudioCaps() {
        _capsDirty = false
        val caps = runCatching {
            androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(_context)
        }.getOrNull()
        _caps = caps
        _mixerDepthCache = when {
            caps == null -> 0 to false
            caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_FLOAT) -> 32 to true
            caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_24BIT) -> 24 to false
            caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_32BIT) -> 32 to false
            caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_16BIT) -> 16 to false
            else -> 0 to false
        }
        // 32 位在本应用固定走 float（见 `PcmResamplerAudioProcessor`），
        // 所以只要设备支持 float 就算支持 32；选项只列设备真做得到的档位。
        _supportedDepthsCache = if (caps == null) {
            listOf(0)
        } else {
            buildList {
                add(0)
                if (caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_16BIT)) add(16)
                if (caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_24BIT)) add(24)
                if (caps.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_FLOAT)) add(32)
            }
        }
        AudioDirectProbe.recordMixerDepth(_mixerDepthCache.first, _mixerDepthCache.second)
        AudioDirectProbe.recordSupportedDepths(_supportedDepthsCache)
        AudioDirectProbe.recordDeviceEncodings(
            _audioOutput?.currentOutputDevice()?.encodings.orEmpty(),
        )
        if (MoreSettings.aaudioExclusive) AudioDirectProbe.warmUpAaudioAsync()
    }

    /**
     * 内置 DAC 直通：把 Media3 交来的格式换成一条 native `AudioTrack`。
     *
     * 一条通路三种内容，按**源的字节形状**分流（不是按品牌、不是按档位）：
     *
     * | 内容 | 走哪个端口 | 需要什么前置条件 |
     * |---|---|---|
     * | 普通 PCM | DIRECT **PCM** 端口，源率 | 形状能映射成公开 `audio_format_t` |
     * | DoP | DIRECT **PCM** 端口，176.4k / 24 bit | 同上（DoP 帧就是 24 bit PCM） |
     * | raw native | 私有 DSD 容器 `0x1A0000xx` | 本机**实测**过一条能用的 `(format, rate, flags)` |
     *
     * 为什么 raw native 需要实测而 PCM 不需要：私有 format 没有任何设备会声明它存在，
     * 只能真开一次轨验证；PCM 的 `audio_format_t` 是公开值，`set()` 收不收本身就是答案。
     *
     * ⚠️ 私有容器只认探测测到的**那个率**。探测按 DSD64 跑，所以 DSD128 以上现在会退到
     * DoP / PCM —— 这是"不拿没测过的组合去赌"的代价，等探测支持按档位跑再放开。
     */
    private fun _openDapDirect(
        format: androidx.media3.common.Format,
    ): com.annieplayer.annie_music.kt.core.audio.dap.DapDirectOutput? {
        fun skip(why: String): com.annieplayer.annie_music.kt.core.audio.dap.DapDirectOutput? {
            com.annieplayer.annie_music.kt.core.audio.dap.NativeDapDac.lastOpenNote = "skip: $why"
            return null
        }
        val usbExclusive = MoreSettings.usbExclusive
        if (usbExclusive) {
            return skip("USB 独占开着")
        }
        if (com.annieplayer.annie_music.kt.core.audio.dap.NativeDapDac.teardownStuck) {
            return skip("直出收尾不返回，已停用")
        }
        // **这一首的源是按哪种字节形状建的** —— 它决定哪条端口允许收它。
        //
        // `FfmpegOutputStream.Spec.transport` 是这件事的唯一声明方（由建源的那一方写下）。
        // 三种形状去的端口完全不同，混用不会报错、只会出噪声：
        //
        // | transport | 一帧 | 唯一收得下的端口 |
        // |---|---|---|
        // | `native` | 8 字节（32 bit word × 2 声道） | 私有 DSD 容器 `0x1A0000xx` |
        // | `dop` | 6 字节（24 bit PCM，`0x05 / 0xFA` 是帧标记） | **PCM 端口** |
        // | `pcm` | 普通样本 | **PCM 端口** |
        //
        // 非全链路（流媒体 / Media3 自己解）没有这一栏 → 退回全局量；`_setSource` 开头
        // 已把它归零成 `pcm`，所以这个兜底只会在"本首就是按 raw 建源"时取到非 pcm 值 ——
        // 而那正是我们要的（宁可留在私有容器，也不能把 8 字节原生帧塞进 PCM 端口）。
        val transport = com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegOutputStream
            .transportOf(_fullChainPath) ?: _dsdTransport
        val vendor = com.annieplayer.annie_music.kt.core.audio.dap.detectDapVendor(
            manufacturer = android.os.Build.MANUFACTURER,
            brand = android.os.Build.BRAND,
            model = android.os.Build.MODEL,
            product = android.os.Build.PRODUCT,
            fileExists = { p -> runCatching { java.io.File(p).exists() }.getOrDefault(false) },
            device = android.os.Build.DEVICE,
        )
        val working = if (transport == DsdTransport.native) _dapWorkingNow() else null
        // ── ① raw native：只认私有 DSD 容器 ──
        val dsdSpec = if (transport == DsdTransport.native) {
            val built = working?.let { w ->
                com.annieplayer.annie_music.kt.core.audio.dap.dapStreamSpecFor(
                    sampleRate = format.sampleRate,
                    pcmEncoding = format.pcmEncoding,
                    channelCount = format.channelCount,
                    working = w,
                )
            }
            // 形状对不上就**不开口**。换一种端口硬塞的下场是"能播、出噪声"，
            // 而那会掩盖真正的原因（探测结论与建源形状不一致）。
            if (built == null) {
                return skip(
                    "native 源无可用私有容器 format(rate=${format.sampleRate} " +
                        "enc=${format.pcmEncoding} ch=${format.channelCount}) " +
                        "working=${working?.label ?: "null"}",
                )
            }
            // **必须先下发厂商模式开关**：飞傲的 HAL 要先把 `format` / `sampling_rate` 拨到 DSD，
            // `set()` 才会落在那条私有端口上。探测阶段是下发了才建成的，播放阶段漏了这一步
            // 就会一直开不出来（实测症状：`raw=native` 一闪 → 记成失败 → 退 DoP → 白噪）。
            //
            // 比特率从**实测组合的容器宽度反推**，不能用 `format.sampleRate × 32`：
            // 16 bit 容器（`0x1A000001`）的率是 `bitRate / 16`，乘 32 会得到两倍的比特率，
            // 厂商 HAL 于是按错误的率切模式。
            val divisor = when (
                com.annieplayer.annie_music.kt.core.audio.dap.dapContainerOf(working.format)
            ) {
                com.annieplayer.annie_music.kt.core.audio.dap.DapContainer.word32 -> 32
                com.annieplayer.annie_music.kt.core.audio.dap.DapContainer.short16 -> 16
                com.annieplayer.annie_music.kt.core.audio.dap.DapContainer.byte8 -> 1
                null -> com.annieplayer.annie_music.kt.core.audio.dap.DAP_WORD_DIVISOR
            }
            com.annieplayer.annie_music.kt.core.audio.dap.DapVendorTuning
                .enterDsd(_context, vendor, working.sampleRate * divisor)
            built
        } else {
            null
        }
        // ── ② 其余（DoP 与普通 PCM）：DIRECT 的 **PCM** 端口 ──
        //
        // DoP 的帧就是 24 bit PCM（6 字节/帧），它从来不需要私有 format ——
        // 这条路之前不通，只是因为**这里根本没有 PCM 端口**，不是 DoP 本身有问题。
        //
        // 这里**不下发厂商参数**：DSD 模式开关只用于"进 DSD"，PCM 不需要；
        // 而 `_setSource` / `onClosed` 已经负责把 HAL 拨回 PCM。多下发一次
        // `setParameters` 是一次没有证据支持的全局改动。
        val spec = dsdSpec
            ?: com.annieplayer.annie_music.kt.core.audio.dap.dapPcmStreamSpecFor(
                sampleRate = format.sampleRate,
                pcmEncoding = format.pcmEncoding,
                channelCount = format.channelCount,
                // raw 帧只许试保真 flags：落到 mixer 的组合会把 DoP 标记改掉（噪声 > 静音）
                alternateFlags = if (transport == DsdTransport.dop) {
                    com.annieplayer.annie_music.kt.core.audio.dap.DAP_DOP_FLAG_LADDER
                } else {
                    com.annieplayer.annie_music.kt.core.audio.dap.DAP_PCM_FLAG_LADDER
                },
            )
            ?: return skip(
                "PCM 端口不支持这组形状 rate=${format.sampleRate} " +
                    "enc=${format.pcmEncoding} ch=${format.channelCount} " +
                    "transport=${transport.key}",
            )
        // 开流这一刻标记"设备变化可能是自方回声"（关流在 `onClosed` 里再标一次）。
        // 只对 raw 那条做：私有 DSD 端口会让系统看到设备增删，而普通 PCM 直通不该
        // 把真实插拔事件一起吞掉。
        if (transport == DsdTransport.native) {
            _dapEchoAt = android.os.SystemClock.elapsedRealtime()
            AudioSelfAction.mark()
        }
        return com.annieplayer.annie_music.kt.core.audio.dap.DapDirectOutput(
            sink = com.annieplayer.annie_music.kt.core.audio.dap.NativeDapSink(),
            spec = spec,
            // PCM 端口没有探测结论可用 → 从软时钟起算，播放头真前进过会自动升级
            // （见 `DapDirectOutput.refreshHwUsable`）。这条路宁可先不信硬件读数。
            hardwarePosition = working?.positionOk == true,
            onClosed = {
                // 复位成 PCM：不复位的话下一次普通曲目会带着 DSD 的 format 进 HAL
                com.annieplayer.annie_music.kt.core.audio.dap.DapVendorTuning
                    .reset(_context, vendor, 48_000)
                // 拆流同样会让系统看到设备增删（只对 raw 那条）
                if (transport == DsdTransport.native) {
                    _dapEchoAt = android.os.SystemClock.elapsedRealtime()
                    AudioSelfAction.mark()
                }
            },
            onOpenFailed = {
                // 失败原因单独落一行：`dap=` 那一栏会被下一次 configure 的 `skip:` 覆盖掉，
                // 而 rc / 三段返回值正是判断"是 HAL 拒绝还是端口被占"的唯一依据。
                runCatching {
                    val note = com.annieplayer.annie_music.kt.core.audio.dap.NativeDapDac.lastOpenNote
                    java.io.File(_context.filesDir, DAP_OPEN_LOG).appendText(
                        "at=${_statusClock.format(java.util.Date())} " +
                            "rate=${format.sampleRate} enc=${format.pcmEncoding} " +
                            "transport=${transport.key} $note\n",
                    )
                }
                if (transport != DsdTransport.native) {
                    // PCM 端口开不出来：**不重建**。这不是"这一档 native 不可用"，
                    // 而是这个率/位深上没有 DIRECT PCM 端口 —— 重建只会再撞一次同样的拒绝，
                    // 而重试的路径本来就存在（下一次 format 变化 / 用户切通路）。
                } else {
                    // raw native：探测阶段这同一组 `(format, rate, flags)` 是通的，播放阶段却
                    // 开不出来。**必须记住**：记住之后降级链会把这一档的 native 跳过
                    // （`TransportFailed`），音源按 PCM 重建一次。不记的后果不是"音质差" ——
                    // raw DSD 帧落到系统 mixer 上是满幅噪声，而且每首歌都会再来一次。
                    _failedTransports.add(_transportKey(DsdTransport.native, _activeDsdLevel))
                    _mainHandler.post { runCatching { _reloadCurrentSource() } }
                }
            },
        )
    }

    private fun _warmDirectCapabilities() {
        val out = _audioOutput ?: return
        // 播放中一律不探测：一次会开关二十来条试探轨，每条都让系统看到设备增删，
        // 又回调 `onDevicesChanged` → 再探 → 自激（实测周期 ≈1 秒）。
        // 而每次重算计划都会把目标率在「按源率」和「重采样」之间翻一次，
        // 于是 AudioTrack 每秒重建一次，我方重采样永远活不过一次 configure。
        if (_player?.playWhenReady == true) {
            AudioSelfAction.mark()
            return
        }
        val isBluetooth = out.currentRoute() == AudioRoute.bluetooth
        // 这一整段会开关二十来条试探轨 —— 每一条都会让系统看到设备增删。
        // 先登记，让 `onDevicesChanged` 把随之而来的回调当成自方回声（见 `AudioSelfAction`）。
        AudioSelfAction.mark()
        Thread({
            // 位深 / 输出能力与路由无关，蓝牙下也要刷新，否则切回本机后档位是旧的
            _refreshAudioCaps()
            // 输出能力只在本机输出下有意义；蓝牙链路由 codec 决定
            if (!isBluetooth) {
                runCatching { AudioDirectProbe.capabilities(out) }
            }
            runCatching { _applyOutputPolicy(_lastAudioInfo) }
            // 内置 DAC 直通测一次（结果落盘，之后开机直接读缓存）。
            // 放在这个线程的最后：它会开真轨试私有 format，不该挡着上面的能力刷新；
            // 而且此刻必然还没开始播，试轨不会打断任何东西。
            com.annieplayer.annie_music.kt.core.audio.dap.DapCapabilityProbe.ensureAsync(_context)
            // 探测是异步的，且只在空闲时可信 —— 这里等它出结果再抓快照（最多 4 秒）。
            // 抓不到就保持 null，下一次进这个线程再试。
            repeat(20) {
                if (com.annieplayer.annie_music.kt.core.audio.dap.DapCapabilityProbe.current != null) {
                    return@repeat
                }
                Thread.sleep(200)
            }
            runCatching { _refreshDapWorking() }
            // 探测结束：再登记一次，覆盖"最后一条试探轨的析构晚于本次 mark"这种情况
            AudioSelfAction.mark()
        }, "annie-output-probe").start()
    }

    /**
     * `FfmpegLocalExtractor.init()` 在播放线程上完成 open 之后才会把规格挂出来，
     * 比第一次 `_applyOutputPolicy` 晚；这里收到通知就重发一次状态（切回主线程）。
     */
    private fun _bindOutputStream() {
        FfmpegOutputStream.onPublished = {
            _scope.launch { _applyOutputPolicy(_lastAudioInfo) }
        }
    }

    /**
     * 当前曲目若是本地全链路，返回 native **已经回报**的真实输出规格；否则 null。
     *
     * 只认 `FfmpegOutputStream` 里 path 对得上的那一条：切歌瞬间新曲目还没 open 完，
     * 这时返回 null 让 UI 退回设置层推算，不会出现「上一首的 176.4k 挂在当前曲目上」。
     */
    private fun _currentFullChainSpec(): FfmpegOutputStream.Spec? {
        val item = _queue.getOrNull(_currentAbsIndex) ?: return null
        val url = item["url"].orEmpty()
        if (!url.startsWith("file://")) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        if (path.isEmpty() || !FfmpegLocalRoute.useFullChain(path)) return null
        return FfmpegOutputStream.specFor(path)
    }

    /**
     * 用 ffprobe 读取 [url] 的格式信息；失败 / 超时返回 null。
     *
     * 缓存分片可能还没落盘完整：此时 ffprobe 拿得到采样率/比特率却**拿不到 `codec_name`**，
     * 于是结果里缺编码。所以探到「无编码」时改用完整 URL 重探一次，保证编码一定拿得到。
     */
    private suspend fun _probeAudioInfo(url: String): AudioInfo? = withContext(Dispatchers.IO) {
        // 本地文件：优先用**曲库索引**里的信息（扫描时由 TagLib + 头解析算好的
        // 真实编码 / 位深 / 采样率）。现场 ffprobe 也能跑，但对 `.m4a` 这种
        // 「ALAC 还是 AAC 取决于 sample entry」的容器，索引里的值更准、更快；
        // 索引里没有（还没扫进曲库）才退回 ffprobe。
        if (url.startsWith("file://")) {
            val path = runCatching { Uri.parse(url).path }.getOrNull()
            if (!path.isNullOrEmpty()) {
                val meta = com.annieplayer.annie_music.kt.data.local.LocalLibraryStore.trackOf(path)
                if (meta != null && meta.probed && meta.codec.isNotEmpty()) {
                    return@withContext AudioInfo(
                        codec = meta.codec.uppercase(),
                        bitDepth = meta.bitDepth,
                        sampleRate = meta.sampleRate,
                        bitrate = if (meta.bitrate > 0) meta.bitrate * 1000 else 0,
                        // 曲库索引**不存**响度标签（`LocalTrackMeta` 没有这个字段）。
                        // 以前这里硬编码 0f，导致「响度归一化」对**所有已扫描进曲库的本地歌**
                        // 恒不生效 —— 开关打开也听不出任何变化。现在单独读一次并缓存。
                        replayGainDb = _replayGainOfPath(path),
                    )
                }
                return@withContext _probeTarget(path)
            }
        }
        val cached = _cachedFileFor(url)?.absolutePath
        if (cached != null) {
            val info = _probeTarget(cached)
            if (info != null && info.codec.isNotEmpty()) return@withContext info
        }
        // 首次播放时 Media3 还没 commit 缓存分片（只在数据源关闭时才写索引），本地无文件可探；
        // 而 ffprobe 直接拉 URL 常拿不到编码 → 自己抓一段头部字节再 probe
        val prefix = _probeUrlPrefix(url)
        if (prefix != null && prefix.codec.isNotEmpty()) return@withContext prefix
        _probeTarget(url)
    }

    /**
     * 本地文件的 ReplayGain 曲目增益（dB），按路径缓存。
     *
     * 为什么要单独读一次：`LocalTrackMeta` **不存**这个标签（曲库扫描走 TagLib，
     * 没取 ReplayGain），而响度归一化必须拿到它。以前曲库分支硬编码 `0f`，
     * 效果就是「开关打开、对已扫描进曲库的本地歌永远没有任何变化」。
     *
     * 成本：只有第一次会真跑一次 ffprobe（本地文件，几十毫秒读头 + tags），
     * 之后命中 [CacheService]（30 天）。用户重打标后清缓存即可重算。
     */
    private suspend fun _replayGainOfPath(path: String): Float {
        val key = "replaygain:$path"
        CacheService.get<String>(key)?.toFloatOrNull()?.let { return it }
        val db = _probeTarget(path)?.replayGainDb ?: 0f
        CacheService.set(key, db.toString(), _replayGainTtlMs)
        return db
    }

    /**
     * 抓 [url] 开头 [Companion._probePrefixBytes] 字节到临时文件后 probe。
     *
     * 首次播放只能这样拿编码：mp3 的 ID3 标签 + 首帧、flac 的 STREAMINFO 都在文件头部，
     * 拿到头部 ffprobe 就能给出编码 / 位深 / 采样率。用完即删。
     */
    private suspend fun _probeUrlPrefix(url: String): AudioInfo? = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            val req = Request.Builder().url(url).header("User-Agent", _userAgent).build()
            val client = globalOkHttp.newBuilder()
                .callTimeout(_probeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            file = File.createTempFile("probe_", ".bin", _context.cacheDir)
            client.newCall(req).execute().use { resp ->
                val body = resp.body ?: return@withContext null
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (total < _probePrefixBytes) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
            _probeTarget(file.absolutePath)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { file?.delete() }
        }
    }

    /** 对单个目标（本地文件或 URL）跑一次 ffprobe。 */
    private suspend fun _probeTarget(target: String): AudioInfo? = withContext(Dispatchers.IO) {
        try {
            val media = FFprobeKit.getMediaInformation(target, _probeTimeoutMs).mediaInformation
                ?: return@withContext null
            val audio = media.streams.firstOrNull { it.type == "audio" } ?: return@withContext null
            Log.i(
                _tag,
                "[audio] probe target=$target streams=${media.streams.size} " +
                    "codec=${audio.codec} depth=${audio.getStringProperty("bits_per_raw_sample")} " +
                    "rate=${audio.sampleRate} bps=${audio.bitrate}",
            )
            val codec = _formatCodec(audio.codec)
            val rate = audio.sampleRate?.toIntOrNull() ?: 0
            if (codec.isEmpty() && rate == 0) return@withContext null
            Log.i(
                _tag,
                "[audio] route=${AudioFormatRouter.route(media.format.orEmpty(), audio.codec.orEmpty())} " +
                    "container=${media.format}",
            )
            AudioInfo(
                codec = codec,
                bitDepth = audio.getStringProperty("bits_per_raw_sample")?.toIntOrNull()
                    ?: audio.getStringProperty("bits_per_sample")?.toIntOrNull()
                    ?: 0,
                sampleRate = rate,
                bitrate = audio.bitrate?.toIntOrNull()?.takeIf { it > 0 }
                    ?: media.bitrate?.toIntOrNull()?.takeIf { it > 0 }
                    ?: 0,
                replayGainDb = _replayGainOf(media),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 取 [url] 在 Media3 缓存里从 0 开始的 span 文件（存在即已落盘、可本地 probe）。 */
    private fun _cachedFileFor(url: String): File? {
        val cache = _cache ?: return null
        val key = _cacheKeyByUrl[url] ?: url
        return try {
            cache.getCachedSpans(key).firstOrNull { it.position == 0L && it.length > 0 }?.file
        } catch (_: Throwable) {
            null
        }
    }

    /** ffprobe 编码名 -> 展示名（`mp3` -> `MP3`；`pcm_*` 归并为 `PCM`）。 */
    private fun _formatCodec(codec: String?): String {
        val c = codec?.trim().orEmpty()
        if (c.isEmpty()) return ""
        if (c.startsWith("pcm", ignoreCase = true)) return "PCM"
        return c.uppercase()
    }

    /** 读 ReplayGain 曲目增益（dB）；没有标签或解析失败返回 0（= 不做响度处理）。 */
    private fun _replayGainOf(media: com.arthenica.ffmpegkit.MediaInformation): Float {
        val tags = media.tags ?: return 0f
        val raw = tags.optString("REPLAYGAIN_TRACK_GAIN", "")
            .ifEmpty { tags.optString("replaygain_track_gain", "") }
        if (raw.isEmpty()) return 0f
        return raw.trim().substringBefore(' ').toFloatOrNull()?.coerceIn(-24f, 24f) ?: 0f
    }

    /**
     * 启动前台服务（`MusicSessionService`）：Media3 在服务内看到 player 正在播放时会自动
     * 进入前台并展示媒体通知。仅在已有媒体项时调用，避免 startForegroundService 的 5s 超时。
     */
    private fun _ensureServiceStarted() {
        val p = _player
        if (p == null || p.mediaItemCount == 0) return
        if (_serviceStarted) return
        _serviceStarted = true
        try {
            ContextCompat.startForegroundService(
                _context,
                Intent(_context, MusicSessionService::class.java),
            )
        } catch (_: Throwable) {
            _serviceStarted = false
        }
    }

/*
     *
     * MediaSession 回调：把通知栏/锁屏/线控的「上一首/下一首」转发到应用级切歌。
     *
     * 引擎队列每次只有一个 MediaItem，Media3 默认的 `seekToNext/PreviousMediaItem` 会因没有
     * 下一/上一项而 no-op，导致通知栏按钮失效。这里拦截对应命令调用 [skipNext]/[skipPrevious]
     * （对齐 Flutter `AudioPlayerHandler.skipToNext/skipToPrevious → onNext/onPrevious`）。
     * 由于队列仅单个 MediaItem，返回 RESULT_SUCCESS 后即便命令再转发给 player 也无副作用。     */
    private inner class AnnieSessionCallback : MediaSession.Callback {
        @androidx.annotation.OptIn(UnstableApi::class)
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command playerCommand: Int,
        ): Int = when (playerCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                _scope.launch { skipNext() }
                SessionResult.RESULT_SUCCESS
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                _scope.launch { skipPrevious() }
                SessionResult.RESULT_SUCCESS
            }
            else -> super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

/*
     *
     * 让「上一首 / 下一首」在 Media3 眼里**可用**，否则通知栏 / 锁屏 / 蓝牙不会画出切歌键。
     *
     * 本引擎每次只装 1 个 MediaItem（每首歌的直链都要按需解析），ExoPlayer 因此把
     * `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` / `..PREVIOUS..` 从可用命令里剔掉 →
     * `DefaultMediaNotificationProvider` 判定「没有上一首/下一首」→ 媒体通知里没有切歌按钮。
     *
     * 这里只覆盖 [getAvailableCommands]（其余全部转发给真实 player，应用侧逻辑用的仍是原
     * ExoPlayer 实例），把这两条命令补回可用集合；真正的切歌仍由
     * [AnnieSessionCallback.onPlayerCommandRequest] 转发到 skipNext / skipPrevious。     */

/*
     *
     * 本机 `127.0.0.1` 上的 Apple HLS 代理**不做清单重试**：它要么立刻成功，要么就是死链
     * （会话已切走 / 端口已关）。Media3 默认的 HLS 清单重试会把这个失败拖成 3 秒静音，
     * 直接返回 [C.TIME_UNSET] 让它立刻走 `onPlayerError`，由
     * [_handlePlaybackFailure] 重新解析，用户几乎察觉不到。远端平台沿用默认策略。     */
    private class LocalFailFastPolicy(
        private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    ) : LoadErrorHandlingPolicy by delegate {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val host = loadErrorInfo.loadEventInfo.dataSpec.uri.host
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") return C.TIME_UNSET
            return delegate.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private inner class CommandAdvertisedPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val seekCommands: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            // 队列只有一首时不要凭空声明切歌能力，避免通知栏出现「点了没反应」的按钮
            if (_queue.size <= 1) return base
            return base.buildUpon().addAll(seekCommands).build()
        }

        override fun getCurrentPosition(): Long {
            val pos = super.getCurrentPosition()
            if (pos < 0L) return 0L
            val dur = super.getDuration()
            return if (dur > 0L && pos > dur) dur else pos
        }
    }

    /** ExoPlayer 监听：isPlaying / ended / error（对齐 Dart 的 playerStateStream 与 processingStateStream） */
    private inner class AnniePlayerListener : Player.Listener {
        /**
         * UI 的「播放 / 暂停」状态取 **`playWhenReady`（用户意图）**，不取 `isPlaying`。
         *
         * Media3 的 `isPlaying` 在 seek / 缓冲 / 换源那一瞬间会变 `false`，把它直接推给 UI，
         * 播放按钮就会在「拉进度条」时自己闪成播放三角（现象：播放中拖进度条，按钮变了）；
         * 真正暂停时 `playWhenReady` 才是 false，播放 / 暂停两条路径都照常收到回调。
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playWhenReady = _player?.playWhenReady == true
            _playWhenReadyAtMs = android.os.SystemClock.elapsedRealtime()
            if (_switchingSource && !_playWhenReady) return
            _emit(mapOf("isPlaying" to (_player?.playWhenReady == true)))
        }

        /** 播放意图变化（暂停 / 续播）单独推一次，避免只靠 isPlaying 回调漏事件。 */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _playWhenReady = playWhenReady
            _playWhenReadyAtMs = android.os.SystemClock.elapsedRealtime()
            if (_switchingSource && !playWhenReady) return
            _emit(mapOf("isPlaying" to playWhenReady))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!_active) return
            // 诊断：状态机每一次跃迁都留一行 —— 暂停/恢复那条链只在状态层面能看清。
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("V") {
                "state=$playbackState player=${System.identityHashCode(_player)} " +
                    "pwr=${runCatching { _player?.playWhenReady }.getOrDefault(false)}"
            }
            if (playbackState == Player.STATE_ENDED) {
                _scope.launch { _handleEnded() }
            }
            if (playbackState == Player.STATE_READY && !_resumeSeekSettled) {
                _verifyResumeSeekSoon()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!_active) return
            val idx = _currentAbsIndex
            // 顶层 message 只是 "MediaCodecAudioRenderer error, index=..."，
            // 真正的原因（AudioTrack 建轨参数被拒 / 编码不支持 / buffer 非法）在 cause 链里。
            val chain = generateSequence(error.cause) { it.cause }
                .take(4)
                .joinToString(" ⟵ ") { "${it.javaClass.simpleName}: ${it.message?.take(140)}" }
            AudioDirectProbe.recordPlaybackFailure(
                "onPlayerError ${error.errorCodeName}: " +
                    (chain.ifEmpty { error.message?.take(200) ?: "无 cause" }),
            )
            Log.w(_tag, "[audio] onPlayerError: ${error.errorCodeName} $chain")
            _scope.launch { _handlePlaybackFailure(idx) }
        }
    }

    internal companion object {
        const val _tag: String = "AnnieAudio"
        const val _progressIntervalMs: Long = 100L
        const val _cacheDirName: String = PLAYBACK_CACHE_DIR_NAME

        const val RESUME_SEEK_TOLERANCE_MS: Long = 1_500L
        const val RESUME_SEEK_MAX_ATTEMPTS: Int = 2

        /**
         * DSD 模式切换的重建合并窗口。
         *
         * 每次切模式都要 `alt 0 → alt N` 全量拆流，连续切会叠成一串；这个窗口把一串
         * 合并成一次，代价是"切完到生效"多等这么久。150 ms 比一次重建本身（几百毫秒）
         * 小得多，所以手感上只有"切了之后稍微等一下"，不会有二次延迟。
         */
        const val DSD_SWITCH_COALESCE_MS: Long = 150L

        /** 出口校验最多补跑几次（见 `_scheduleDsdSwitch`） */
        const val DSD_SWITCH_MAX_RETRIES: Int = 3

        // ── LoadControl（ExoPlayer 缓冲/起播门槛）────────────────────────────────────
        //
        // 全部**必须显式给**：Media3 1.4.1 的 `DefaultLoadControl` 默认值是给视频调的，
        // 对音乐是灾难。以下五个数来自对着 `DefaultLoadControl` 与 `ExoPlayerImplInternal`
        // 字节码逐条核过的判据（`javap -p -c`），不是手感调参：
        //
        // · `DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000`
        //   seek 之后只要掉一次 BUFFERING，就要求"再缓冲 5 秒音频"才恢复播放。
        //   `shouldTransitionToReadyState` → `shouldStartPlayback(rebuffering=true)`。
        //   本地文件表现为几十毫秒（解码快），**流媒体就是真等 5 秒数据下载完** ——
        //   这正是"拉进度条延迟有点大"的主因，而且两条输出链路共用这一层。
        // · `DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2500`：起播同样要等 2.5 秒数据。
        // · `DEFAULT_MIN/MAX_BUFFER_MS = 50000`：音乐不必预取 50 秒。
        //   副作用是 `bufferedDurationUs` 长期远大于门槛，判据被 12.5 MB 字节兜底接管，
        //   行为随码率漂移（同一首歌 flac / aac 表现不同）。
        // · `DEFAULT_BACK_BUFFER_DURATION_MS = 0`：往回拖（拖到已播过的地方）无法命中，
        //   白白重新解析/下载。**但这一条不能靠加后缓冲来补** —— 见下面 [BACK_BUFFER_MS]。
        //
        // 数值依据：30 s 起缓冲 / 45 s 上限 —— 与 48 MB 字节上限配合后，无损流（1.15 MB/s）
        // 实际能攒到 ≈ 41 s，够扛住「播放中并行下载」把带宽分走一半；起播仍是 1 s、
        // 重缓冲仍是 1 s，所以手感没有变肉。
        /**
         * 正在跑的下载任务数。整首预热在 `> 0` 时全程让路。
         *
         * 用计数而不是布尔：下载队列现在是 4 路并行，一个任务结束不代表全部结束 ——
         * 布尔会被先完成的那个提前复位，预热立刻回来抢带宽。
         */
        private val _downloadTasks = java.util.concurrent.atomic.AtomicInteger(0)

        internal val downloadActive: Boolean get() = _downloadTasks.get() > 0

        internal fun markDownloadTask(active: Boolean) {
            if (active) _downloadTasks.incrementAndGet() else _downloadTasks.decrementAndGet()
        }

        const val _loadControlMaxBufferMs: Int = 45_000
        const val _loadControlBufferForPlaybackMs: Int = 1_000

        /**
         * seek 之后若掉进 BUFFERING，要等这么多音频才恢复播放。
         *
         * 这是**拉进度条手感**的直接决定项：目标区间没命中缓存时（跳到没听过的地方），
         * 这一条决定了"松手到出声"的等待。2000 → 1000 等于把这段等待砍半；
         * 1 秒音频在 1 Mbps 的 FLAC 上只有 125KB，命中本地缓存时这点量瞬间就到，
         * 所以弱网反复卡的风险很小。
         */
        const val _loadControlBufferForPlaybackAfterRebufferMs: Int = 1_000

        /**
         * 后缓冲的**时间上限** 10 s —— 现在只是低码率的兜底，高码率由
         * [_loadControlBackBudgetBytes] 换算成更短的秒数。
         *
         * 以前它是硬编码的时间，`SampleQueue.keepAfterRead` 按时间保留、不受
         * `setTargetBufferBytes` 约束，所以 DSD512（22.6 MB/s）10 秒就是 226 MB 常驻 ——
         * 这正是 DSD 高倍档位一播就 OOM 的来源。改成字节预算后这条只负责
         * 「44.1k 上不要保留超过 10 秒」。
         */
        const val _loadControlBackBufferMs: Int = 10_000

        /**
         * 前向缓冲的**字节预算 48 MB**，与码率解耦。
         *
         * 换算成秒（这才是「缓冲有多少余量」）：
         * · AM 24/192（1.152 MB/s）→ **41.7 s**，足够扛住「播放中并行下载」分走一半带宽；
         * · 44.1k/16bit（0.176 MB/s）→ 273 s，被 [maxBufferMs] 45 s 先截住，行为不变；
         * · DSD64（2.82 MB/s）→ 17.1 s；
         * · DSD512（22.6 MB/s）→ **2.1 s** —— 内存不再随档位膨胀。
         */
        const val _loadControlForwardBudgetBytes: Int = 48 * 1024 * 1024

        /**
         * 后缓冲的**字节预算 16 MB**，同样与码率解耦。
         *
         * DSD512 只保留 0.7 秒、AM 无损保留 10 秒（被 [_loadControlBackBufferMs] 截住）。
         * 回拖没命中时网络流会走 `SimpleCache`（命中磁盘，无网络开销），本地文件重读磁盘，
         * 代价可以接受；换来的是「任何档位总占用都不超过 64 MB」这条硬保证。
         */
        const val _loadControlBackBudgetBytes: Int = 16 * 1024 * 1024

        /** [DefaultAllocator] 的单块大小（Media3 默认 64 KB） */
        const val _allocatorChunkBytes: Int = 64 * 1024

        /**
         * 整首预热：缓冲领先到这么多才起跑（见 `_startPendingWarmIfReady`）。
         *
         * 5 秒对 Hi-Res 是 5.8 MB —— 足够让播放器越过「起播 1 秒门槛」并且有余量，
         * 同时不会把预热拖到半首歌之后。
         */
        const val _warmStartBufferedMs: Long = 5_000L

        /** 整首预热每补满这么多字节让路一次（见 `_startFullCacheWarm` 的进度回调）。 */
        const val _warmYieldBytes: Long = 8L * 1024 * 1024

        /** 让路时长：8 MB / 250 ms 约等于降速 10%，补满整首慢几秒，换来播放不断流。 */
        const val _warmYieldMs: Long = 250L

        /**
         * 内置 DAC 直通流开关引起的设备变化，在这个窗口内算**自方回声**。
         *
         * 窗口取 1.5 s：开/关流到系统广播设备增删通常在几十毫秒内，而 STRAM 重建的
         * 观测窗口（`DEVICE_REEVALUATE_MS`）也在这个量级，留足余量又不至于吞掉真实拔插。
         */
        const val DAP_SELF_ECHO_MS: Long = 1_500L

        /** 首次播放探测音频信息时抓的头部字节数（够覆盖 ID3 标签 + 首帧 / FLAC STREAMINFO） */
        const val _probePrefixBytes: Int = 256 * 1024

        /**
         * 内置 DAC **播放期**开流失败的原因（`Files/dap-open.log`）。
         *
         * 为什么不能只靠状态行的 `dap=`：那一栏是"最近一次开流的一句话"，任何一次
         * `configure` 都会覆盖它（换回 PCM 时写的是"格式对不上"），而失败的那一行
         * 恰恰被盖掉了 —— 而 rc 与 native 三段返回值是判断"HAL 拒绝 / 端口被占 /
         * 参数非法"的唯一依据。
         */
        const val DAP_OPEN_LOG: String = "dap-open.log"

        /** 本地文件 ReplayGain 的缓存时长：标签不会变，30 天足够（重打标后清缓存即可） */
        const val _replayGainTtlMs: Long = 30L * 24 * 3600 * 1000

        /** 进程级唯一的磁盘缓存：`SimpleCache` 对同一目录只允许一个实例，重复创建会抛异常崩溃 */
        @Volatile
        private var _sharedCache: Cache? = null
        private val _sharedCacheLock = Any()

        fun sharedCache(context: Context): Cache {
            _sharedCache?.let { return it }
            synchronized(_sharedCacheLock) {
                _sharedCache?.let { return it }
                val dir = File(context.cacheDir, _cacheDirName)
                if (!dir.exists()) dir.mkdirs()
                val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(_maxCacheBytes))
                _sharedCache = created
                return created
            }
        }
        const val _userAgent: String = "AnnieMusic/1.0 (Android)"

        /**
         * 播放缓存上限。
         *
         * 原来是 `NoOpCacheEvictor`（永不淘汰、无上限）—— 听得多就一路涨，实测已到 45MB 且只增不减。
         * 512MB 够放十几首无损的流式缓存，又不至于把 `cacheDir` 撑爆（KKBOX 解密缓存另有自己的上限）。
         */
        const val _maxCacheBytes: Long = 512L * 1024 * 1024

        /** 顺序模式往后预热的曲目数。
         *
         * 只留 2：Apple 每首都要打 `/parse/song`（Cloudflare 后面）+ 2 次 CDN + init + key，
         * 5 首在弱网下纯浪费流量；实际使用里连跳两首以上极少见。
         */
        const val _prefetchAhead: Int = 2
        const val _probeTimeoutMs: Int = 10_000
        const val _prefetchConcurrency: Int = 3
        const val _maxConsecutiveFailures: Int = 3

        /** 自方 USB 动作之后补一次设备评估（真插拔可能落在抑制窗口里） */
        const val DEVICE_REEVALUATE_MS = 500L
        /** USB 插上后等 `UsbManager` 追上 `AudioManager` 再重试 */
        const val USB_ATTACH_RETRY_MS = 400L
    }
}

/** 进程级共享持有者：`MediaPlayerBackend` 创建，`MusicSessionService` 读取以对外暴露 MediaSession */
@androidx.annotation.OptIn(UnstableApi::class)
internal object AnnieAudioHolder {
    @Volatile
    var player: ExoPlayer? = null
    @Volatile
    var session: MediaSession? = null

    /** 进程级共享磁盘缓存与数据源工厂（供 Activity 重建时新实例接管复用） */
    @Volatile
    var cache: Cache? = null
    @Volatile
    var cachedSourceFactory: DefaultMediaSourceFactory? = null
    @Volatile
    var plainSourceFactory: DefaultMediaSourceFactory? = null

    /** 自建 RenderersFactory（处理器链持有 EQ / 重采样参数，Activity 重建时需复用） */
    @Volatile
    var renderersFactory: AnnieRenderersFactory? = null

    /** 输出设备控制器（播放页的设备切换面板直接读取/操作） */
    @Volatile
    var audioOutput: AudioOutputController? = null

    /** 服务 `onDestroy` 时的回调，用于让 backend 复位 `_serviceStarted`（否则后续播放不再起前台服务） */
    @Volatile
    var onServiceStopped: (() -> Unit)? = null

    /** 新实例接管引擎时调用，把上一实例「下线」（短路其回调/进度协程，避免争抢 player） */
    @Volatile
    var onDeactivate: (() -> Unit)? = null
}

/** 播放缓存目录名（Media3 `SimpleCache`）；`AssetCache` 统计/清理时复用同一常量，避免写错。 */
const val PLAYBACK_CACHE_DIR_NAME: String = "annie_audio_cache"

/**
 * 媒体音频用途（ExoPlayer 音频焦点 / 直通属性；与 AudioOutputController 的 android.media 属性一致）。
 *
 * `SPATIALIZATION_BEHAVIOR_NEVER`：明确请求系统**不要**对本应用的声音做空间化。
 *
 * 这不是调音偏好，是防止被破坏。本机（PLQ110 / Android 16）`dumpsys media.audio_flinger`
 * 里存在 SPATIALIZER 线程，实测 `isSpatialized = false` —— 那只是「当前没被激活」，
 * 不是「关掉了」：只要 ROM 侧或耳机联动把它打开，已完成的立体声混音会被虚拟环绕搅掉。
 * 那种损失是听得出来的破坏，不是精度问题，我们做的无损/直通/我方 SRC 会全部白搭。
 *
 * API 32+ 生效。已核对 media3 1.4.1 `AudioAttributes$AudioAttributesV21` 的字节码：
 * `SDK_INT >= 32` 时会调 `Api32.setSpatializationBehavior(builder, spatializationBehavior)`
 * 把它透传给 `android.media.AudioAttributes`，所以这里设了就会真的落到 AudioTrack 上。
 */
private val _mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
    .build()

internal fun shouldStartFullCacheWarm(
    playbackState: Int,
    isPlaying: Boolean,
    bufferedPosition: Long,
    currentPosition: Long,
    minBufferedMs: Long,
): Boolean = playbackState == Player.STATE_READY &&
    isPlaying &&
    bufferedPosition - currentPosition >= minBufferedMs
