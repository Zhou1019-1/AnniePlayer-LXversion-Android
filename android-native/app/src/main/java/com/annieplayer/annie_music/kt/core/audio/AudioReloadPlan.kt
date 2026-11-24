package com.annieplayer.annie_music.kt.core.audio

/**
 * "引擎还能不能信"的几个时间常数 —— 全部只有一处定义，纯逻辑与 sink 共用。
 */
internal object EngineHealth {

    /**
     * sink 控制调用超过这么久没返回，就算"这条流卡住了"。
     *
     * 取值依据：正常的 native `AudioTrack.pause()` / `flush()` 是几十到几百**微**秒级
     * （只有 `SelfPacedWriter.stop()` 的 join 上限是 250 ms，而那个在 release 里）。
     * 800 ms 已经比正常路径慢三个数量级，却远小于用户能察觉"卡死"的时间。
     */
    const val CONTROL_TIMEOUT_MS: Long = 800L

    /**
     * 一次控制超时在多长时间内还算"引擎不可信"。
     *
     * 是一次**事件**带来的怀疑，不是永久结论：换过引擎（或过了一段时间）之后就重新相信它。
     * 20 s 足够覆盖"暂停 → 用户想了一下 → 点播放"这条实测路径。
     */
    const val STALL_WINDOW_MS: Long = 20_000L

    /** 换源之后等出口重配（`AnnieAudioSink.configure`）的上限 */
    const val CONFIGURE_VERIFY_MS: Long = 1_200L

    /** 等数据的上限：`play()` 之后这么久还一个样本都没进出口，就认定渲染器没跑起来 */
    const val DATA_FLOW_VERIFY_MS: Long = 1_500L

    /** 两次引擎重建之间的最小间隔：防止"重建 → 仍不 configure → 再重建"的自激 */
    const val REBUILD_MIN_INTERVAL_MS: Long = 3_000L
}

/**
 * 「应用通路 / 计划变更」这一个动作该落到哪条路上。
 *
 * 三档是**互斥且有优先序**的，顺序本身就是结论：
 *
 * 1. [ReloadAction.none] —— 没有源可以换（队列空 / 直链还没解析）。换源没有对象。
 * 2. [ReloadAction.rebuildEngine] —— 两条判据，任一条成立就必须换引擎：
 *    · `pathChanged`：用户改了通路。那之前一定拆过一次内置 DAC 直通流，
 *      而拆流/停流是 native `AudioTrack` 调用，在部分 ROM 上不返回；
 *    · `engineSuspect`：sink 侧已经报过控制调用超时 —— 播放线程可能就卡在那一次调用里，
 *      它再也不会处理任何消息（`setMediaSource` / `prepare` 都排在这条线程后面）。
 *      这种引擎**不可能**靠原地换源救回来：卡住的线程杀不掉，消息永远不入栈。
 * 3. [ReloadAction.swapInPlace] —— 只是计划变了（采样率 / 位深 / 传输方式 / 蓝牙协商），
 *    引擎还健康，原地 `stop → setMediaSource → prepare` 就够，代价是一次断音。
 *
 * 抽样上界（[AudioReloadPlanTest]）保证"通路变更永远落在 2"，
 * 也就是永远走与冷启动完全相同的构建路径。
 */
internal enum class ReloadAction {
    /** 什么都不做 */
    none,

    /** 引擎健康：原地 `stop → setMediaSource → prepare` */
    swapInPlace,

    /** 换一条新引擎（= App 冷启动那条路径：新播放线程、新渲染器、新 AudioSink） */
    rebuildEngine,
}

internal fun reloadActionFor(
    pathChanged: Boolean,
    planChanged: Boolean,
    engineSuspect: Boolean,
    hasSource: Boolean,
): ReloadAction = when {
    !hasSource -> ReloadAction.none
    pathChanged -> ReloadAction.rebuildEngine
    engineSuspect -> ReloadAction.rebuildEngine
    planChanged -> ReloadAction.swapInPlace
    else -> ReloadAction.none
}

/**
 * 换源之后的**出口校验**：`AnnieAudioSink.configure` 有没有真的再来一次。
 *
 * 为什么需要它：`setMediaSource` + `prepare` 返回 true 只证明"调用没抛异常"。
 * 实测有两条路径是"调用成功、出口从没重配"——播放器停在 BUFFERING
 * （`state=2`、`durMs=UNSET`、进度条不动），界面一切正常、就是没声。
 * 出口计数是唯一能把这两件事分开的证据。
 */
internal enum class ConfigureVerdict {
    /** 还没超时，也还没看到新的 configure —— 继续等 */
    pending,

    /** 出口重配了：这条换源真的落地 */
    confirmed,

    /** 超时且计数没动：升级成换引擎 */
    escalate,
}

internal fun configureVerdict(
    elapsedMs: Long,
    timeoutMs: Long,
    configureCountBefore: Int,
    configureCountNow: Int,
): ConfigureVerdict = when {
    configureCountNow > configureCountBefore -> ConfigureVerdict.confirmed
    elapsedMs >= timeoutMs -> ConfigureVerdict.escalate
    else -> ConfigureVerdict.pending
}

/**
 * sink 侧那次"控制调用超时"还该不该当成"引擎不可信"。
 *
 * 判据是时间窗而不是布尔位：超时是一次**事件**，它会一直留在计数里；只在最近这段时间内
 * 才允许它触发换引擎，否则一次几十秒前的卡顿会让之后每一次"计划变更"都白换一条引擎
 * （换引擎的代价是重新建解码器 + 重新 configure，约几十到上百毫秒的断音）。
 *
 * `stalledAtMs == 0L` 表示从来没有超时过。
 */
internal fun engineSuspectAt(stalledAtMs: Long, nowMs: Long, windowMs: Long): Boolean =
    stalledAtMs != 0L && nowMs - stalledAtMs <= windowMs

internal fun dataFlowStalled(
    bufferCallsBefore: Int,
    bufferCallsNow: Int,
    outletAlive: Boolean,
): Boolean = !outletAlive || bufferCallsNow <= bufferCallsBefore

internal fun outletNeedsReload(
    requiredRate: Int,
    requiredDepth: Int,
    lastConfigureNote: String,
): Boolean {
    if (requiredRate <= 0) return false
    val parts = lastConfigureNote.split('/')
    if (parts.size < 2) return false
    val configuredRate = parts[0].toIntOrNull() ?: 0
    if (configuredRate <= 0) return false
    if (configuredRate != requiredRate) return true
    val configuredDepth = depthOfPcmEncoding(parts[1].toIntOrNull() ?: 0)
    return requiredDepth > 0 && configuredDepth > 0 && configuredDepth != requiredDepth
}

internal fun depthOfPcmEncoding(encoding: Int): Int = when (encoding) {
    2 -> 16
    21 -> 24
    4 -> 32
    else -> 0
}
