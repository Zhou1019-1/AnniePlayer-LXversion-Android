package com.annieplayer.wrapperlite.qemu

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 进程级单例：持有 QEMU、日志缓冲和登录状态，供 UI 与前台 Service 共用。
 *
 * 状态机：
 * ```
 * Preparing → LoggedOut ──login──> LoggingIn ──2FA──> Need2FA ──> LoggedIn
 *                    ^                  └──fail──> LoggedOut
 *                    └────logout/切换账号──── LoggedIn ──serve──> Serving
 * ```
 */
object WrapperLite {

    enum class Phase {
        Preparing,
        LoggedOut,
        LoggingIn,
        Need2FA,
        LoggedIn,

        /** 已点「启动服务」，QEMU 正在起来，端口还没监听 */
        Starting,
        Serving,
        Failed,
    }

    private const val PREFS = "wrapper_lite"
    private const val KEY_ACCOUNT = "account"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val runner: QemuRunner by lazy { QemuRunner(appContext) }

    private val _phase = MutableStateFlow(Phase.Preparing)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 当前登录的账号（脱敏展示用），空串表示未登录。 */
    private val _account = MutableStateFlow("")
    val account: StateFlow<String> = _account.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 账号所属区域（storefront），来自 guest 的 /status，例如 TR / US。 */
    private val _region = MutableStateFlow("")
    val region: StateFlow<String> = _region.asStateFlow()

    /** 登录态健康度：guest 后台续期成功/失败会刷新这里。 */
    enum class TokenHealth { Unknown, Ok, RefreshFailed }

    private val _tokenHealth = MutableStateFlow(TokenHealth.Unknown)
    val tokenHealth: StateFlow<TokenHealth> = _tokenHealth.asStateFlow()

    private val _installProgress = MutableStateFlow(0f to "")
    val installProgress: StateFlow<Pair<Float, String>> = _installProgress.asStateFlow()

    private const val MAX_LOGS = 800

    fun init(ctx: Context) {
        if (::appContext.isInitialized) return
        appContext = ctx.applicationContext
        _account.value = prefs().getString(KEY_ACCOUNT, "").orEmpty()
        _phase.value = if (_account.value.isNotEmpty() && hasToken()) Phase.LoggedIn else Phase.LoggedOut
        observeRunner()
    }

    fun installIfNeeded() {
        if (WrapperPaths.assetsInstalled(appContext)) {
            _phase.value = if (hasToken()) Phase.LoggedIn else Phase.LoggedOut
            return
        }
        scope.launch {
            runCatching {
                AssetsInstaller.install(appContext) { p ->
                    _installProgress.value = p.fraction to p.message
                    _logs.value = (_logs.value + p.message).takeLast(MAX_LOGS)
                }
            }.onFailure {
                _logs.value = (_logs.value + "[local] 释放运行环境失败：${it.message}").takeLast(MAX_LOGS)
                _phase.value = Phase.Failed
                return@launch
            }
            _phase.value = if (hasToken()) Phase.LoggedIn else Phase.LoggedOut
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) return
        resetLogFile()
        _phase.value = Phase.LoggingIn
        _logs.value = emptyList()
        pendingAccount = email
        pendingAutoServe = false
        ensureService()
        runner.login(email, password)
    }

    fun submit2fa(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        if (!runner.running) {
            appendLog("[local] QEMU 已退出，验证码没有送进 guest")
            return
        }
        runner.send2fa(trimmed)
        _phase.value = Phase.LoggingIn
        twoFaCount++
        appendLog("[local] 已提交验证码（第 $twoFaCount 次）")
    }

    /** 本次登录里 guest 要过几次验证码 —— 只写日志，方便区分「重复弹窗」和「真的要第二次」 */
    private var twoFaCount: Int = 0

    fun startServing() {
        if (_phase.value != Phase.LoggedIn && _phase.value != Phase.Serving) return
        ensureService()
        // 真正的「运行中」要等 guest 打印 listening on（见 SERVING 事件），
        // 这里先进入 Starting：UI 显示正在启动，端口不算就绪。
        _phase.value = Phase.Starting
        appendLog("[local] 正在启动服务…")
        runner.serve()
    }

    /** 手动停服务：停 guest + 撤掉前台服务，但保留登录态。 */
    fun stopServing() {
        pendingAutoServe = false
        CorsProxy.stop()
        runner.stop()
        _phase.value = Phase.LoggedIn
        appendLog("[local] 服务已停止")
        stopService()
    }

    /** 前台服务被系统回收时调用：收掉 QEMU，避免孤儿进程占端口。 */
    fun onServiceDestroyed() {
        CorsProxy.stop()
        if (runner.running) runner.stop()
        if (_phase.value == Phase.Serving || _phase.value == Phase.Starting) {
            _phase.value = Phase.LoggedIn
        }
    }

    /** 退出登录：停 guest、清空账号盘（token 全在镜像里）。 */
    fun logout() {
        pendingAutoServe = false
        _region.value = ""
        _tokenHealth.value = TokenHealth.Unknown
        runner.stop()
        stopService()
        scope.launch {
            runCatching { AssetsInstaller.resetAccount(appContext) }
            prefs().edit().remove(KEY_ACCOUNT).apply()
            _account.value = ""
            _phase.value = Phase.LoggedOut
            _logs.value = (_logs.value + "[local] 已退出登录，账号数据已清空").takeLast(MAX_LOGS)
        }
    }

    /** 切换账号 = 退出后回到登录页。 */
    fun switchAccount() = logout()

    fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(MAX_LOGS)
        // 同时落盘，方便排查（App 在后台时 UI 拿不到）
        runCatching {
            val f = logFile()
            if (f.length() > 2L * 1024 * 1024) f.delete()
            f.appendText(line + "\n")
        }
    }

    /**
     * 从 guest 的 /status 拿账号区域（storefront），用于在账号旁边显示国家。
     *
     * 走内部端口而不是对外端口，避免绕一圈 CORS 代理。
     */
    private fun fetchRegion() {
        scope.launch {
            runCatching {
                val conn = java.net.URL(
                    "http://127.0.0.1:${WrapperPaths.UPSTREAM_PORT}/status",
                ).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val regions = org.json.JSONObject(text)
                    .optJSONObject("data")
                    ?.optJSONArray("regions")
                if (regions != null && regions.length() > 0) {
                    _region.value = regions.optString(0).uppercase()
                }
            }
        }
    }

    fun logFile(): File = File(appContext.filesDir, "wrapper-lite.log")

    private fun resetLogFile() {
        runCatching { logFile().writeText("") }
    }

    private var pendingAccount: String = ""

    /** 登录成功后是否还要接着起服务（guest 关机后需要重启一次的那种路径）。 */
    private var pendingAutoServe: Boolean = false

    private fun observeRunner() {
        scope.launch {
            runner.events.collect { e ->
                when (e.kind) {
                    QemuRunner.Kind.LOG -> appendLog(e.line)
                    QemuRunner.Kind.NEED_2FA -> {
                        appendLog("[local] guest 正在等待验证码（60 秒内有效）")
                        _phase.value = Phase.Need2FA
                    }
                    QemuRunner.Kind.LOGIN_OK -> {
                        pendingAccount.ifEmpty { _account.value }
                            .takeIf { it.isNotEmpty() }
                            ?.let { acc ->
                                prefs().edit().putString(KEY_ACCOUNT, acc).apply()
                                _account.value = acc
                            }
                        // 登录成功后马上就会进服务模式，UI 直接显示「正在启动服务」，
                        // 不能退回「已登录 + 启动服务」那一屏（否则看着像没启动）
                        pendingAutoServe = true
                        _phase.value = Phase.Starting
                    }
                    QemuRunner.Kind.LOGIN_FAIL -> {
                        pendingAutoServe = false
                        _phase.value = Phase.Failed
                    }
                    QemuRunner.Kind.SERVING -> {
                        // guest 已经开始监听（登录+服务合并成功，省掉一次冷启动）
                        appendLog("[local] guest 已开始监听端口")
                        pendingAutoServe = false
                        // 在外面再套一层加 CORS 头的代理，网页工具才能跨域调用
                        CorsProxy.start()
                        _phase.value = Phase.Serving
                        ensureService()
                        fetchRegion()
                    }
                    QemuRunner.Kind.TOKEN_OK -> {
                        _tokenHealth.value = TokenHealth.Ok
                    }
                    QemuRunner.Kind.TOKEN_FAIL -> {
                        _tokenHealth.value = TokenHealth.RefreshFailed
                        appendLog("[local] ⚠ 后台 token 续期失败，登录态可能已失效，建议退出后重新登录")
                    }
                    QemuRunner.Kind.EXIT -> {
                        val wasLogging =
                            _phase.value == Phase.LoggingIn || _phase.value == Phase.Need2FA

                        if (wasLogging) {
                            _phase.value =
                                if (hasToken()) Phase.LoggedIn else Phase.Failed
                        }

                        if (pendingAutoServe) {
                            // 登录成功但 guest 关机了（没打合并补丁的路径）→ 再起一次进服务模式
                            pendingAutoServe = false
                            _phase.value = Phase.Starting
                            appendLog("[local] 切换到服务模式…")
                            scope.launch {
                                kotlinx.coroutines.delay(1200)
                                runner.serve()
                            }
                        } else if (_phase.value == Phase.Starting) {
                            _phase.value = Phase.LoggedIn
                            appendLog("[local] 服务启动失败（guest 已退出，检查端口是否被占用）")
                        }
                    }
                }
            }
        }
    }

    /**
     * 是否已登录。
     *
     * 账号盘是**定长 64MB 镜像**（空盘和有 token 的盘长度一样），没法靠文件大小判断；
     * 而 token 在 ext4 内部，宿主要读得先挂载。所以登录成功时在 prefs 留一条账号标记，
     * 退出登录时清掉 —— 这也正好是「退出即抹掉账号状态」的判定依据。
     */
    private fun hasToken(): Boolean =
        prefs().getString(KEY_ACCOUNT, "").orEmpty().isNotEmpty()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureService() {
        runCatching {
            val i = Intent(appContext, WrapperService::class.java)
            appContext.startForegroundService(i)
        }
    }

    fun stopService() {
        runCatching { appContext.stopService(Intent(appContext, WrapperService::class.java)) }
    }

    /**
     * 跳到系统的「耗电管理 / 电池优化」入口。
     *
     * 欧加系（ColorOS/Oplus）会拦掉标准的电池优化对话框，所以按优先级试一串：
     * 1. 标准对话框（原生/类原生一次点掉）
     * 2. 电池优化白名单列表页
     * 3. 应用详情页（ColorOS 上的「耗电管理」「自启动」都在这里面）
     */
    fun openBatterySettings(ctx: Context) {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        appendLog(
            if (ignoring) "[local] 当前已在电池优化白名单"
            else "[local] 当前未加入白名单，正在打开系统设置…",
        )

        val pkg = Uri.parse("package:" + ctx.packageName)
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(pkg),
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(pkg),
        )
        for (intent in candidates) {
            if (runCatching {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            ) {
                return
            }
        }
        appendLog("[local] 没能打开系统设置页，请手动到 设置 → 电池 → 耗电管理")
    }
}
