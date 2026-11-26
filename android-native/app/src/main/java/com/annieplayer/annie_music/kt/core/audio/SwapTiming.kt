package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.Player

/**
 * 一次换源（`setMediaSource` + `prepare`）怎么落地。
 *
 * 判据全部来自 1.4.1 的字节码（`javap -p -c`，不是注释、不是记忆）：
 *
 * 1. `ExoPlayerImpl.prepare()` 第一段就是 `if (playbackInfo.playbackState != 1) return;`
 *    （offset 29-40）—— 非 IDLE 时 `prepare()` **连 `ExoPlayerImplInternal.prepare()` 都不调**。
 * 2. `ExoPlayerImpl.setMediaSource()` → `setMediaSourcesInternal()`：末尾只有
 *    `internalPlayer.setMediaSources(...)` + `updatePlaybackInfo(...)`，**没有** `prepare()`；
 *    非 IDLE 时它把状态改成 BUFFERING。所以 `setMediaSource` 在播放中留下的正是那个
 *    「state=2、再也不动」的死状态（trace `swap-set state=2` 就是这一句）。
 * 3. `ExoPlayerImpl.stop()` → `stopInternal()`：先 `copyWithPlaybackState(STATE_IDLE=1)`
 *    再 `updatePlaybackInfo(...)`，而 `updatePlaybackInfo` 的第一段就是
 *    `this.playbackInfo = <新值>`（offset 9-11）。**`stop()` 返回时 `playbackState` 已经是 IDLE。**
 *
 * 由 3 得出一条以前被过度设计的结论：`stop(); setMediaSource(); prepare()` 在同一个主线程
 * 回合里是成立的，不需要挂起到 `onPlaybackStateChanged(IDLE)`。那套挂起实现在实测里有两条
 * 死路（`Q swap-prepared state=2` 之后再没有任何 `C configure`），其中一条是播放线程已经
 * 卡在 native 控制调用里，回调永远不会来。
 */
internal enum class SwapStep {
    /** 已经是 IDLE：直接 `setMediaSource` + `prepare` */
    setAndPrepare,

    /** 非 IDLE：先 `stop()`（同步落到 IDLE），再 `setMediaSource` + `prepare` */
    stopThenSetAndPrepare,
}

internal fun swapStepFor(playbackState: Int): SwapStep =
    if (playbackState == Player.STATE_IDLE) SwapStep.setAndPrepare else SwapStep.stopThenSetAndPrepare
