package com.annieplayer.annie_music.kt.core.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 下载队列：最多 [MaxParallelDownloads] 个任务同时执行。
 *
 * 以前是严格串行（promise 链 `prev.join()`）。串行的问题在 AM 上最明显：一首无损
 * 40~60 MB，排在它后面的每一首要干等整首下完 —— 用户看到的是「点了下载没反应」。
 * 现在改成固定 4 个槽位：
 * - 任务之间不再互相等待，只受槽位限制；
 * - 同一 songId 重复入队仍然安全（`_taskMap` 取出即删，第二次取不到就空跑）；
 * - 顺序不再保证，但下载本来就不需要顺序。
 */

/** 队列任务类型（对齐 Dart `typedef QueueTask = Future<void> Function()`） */
typealias QueueTask = suspend () -> Unit

private const val MaxParallelDownloads = 4

/** songId -> 待执行任务（取消时移除；执行时按 id 取出最新任务） */
private val _taskMap = ConcurrentHashMap<String, QueueTask>()

/** 执行队列的协程作用域（等价 Dart 的全局 Future 链调度） */
private val _queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val _slots = Semaphore(MaxParallelDownloads)

/** 将下载任务加入队列（最多 [MaxParallelDownloads] 个并行） */
fun enqueueDownload(songId: String, task: QueueTask) {
    _taskMap[songId] = task
    _queueScope.launch {
        _slots.withPermit {
            val fn = _taskMap.remove(songId)
            if (fn != null) {
                try {
                    fn()
                } catch (_: Throwable) {
                    // 对齐 Dart：任务异常由 onError 吞掉，不阻断其它任务
                }
            }
        }
    }
}

/**
 * 批量下载中「单首取消」的标记。
 *
 * 批量下载是**一个批次一个队列任务**（key 是 batchId），单曲 id 在 `_taskMap` 里查不到，
 * 所以 `cancelPending(songId)` 曾经是空操作 —— 表现为「下载列表里删掉那一首，它照样下完」。
 * 这里按曲 id 记标记，下载循环每首开始前取走标记并跳过。
 */
private val _cancelledSongs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** 取消待执行任务 */
fun cancelPending(songId: String) {
    _taskMap.remove(songId)
    _cancelledSongs.add(songId)
}

/** 取走并清除该曲的取消标记；返回 true 表示这一首应被跳过 */
fun consumeCancelledSong(songId: String): Boolean = _cancelledSongs.remove(songId)
