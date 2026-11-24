package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.network.httpPostJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * musicu 的**唯一出口**：串行 + 最小间隔 + 明确被拒时退避重试一次。
 *
 * 为什么要串行：搜索页一次并发打五个平台，QQ 这边用户还可能连点「搜索」、来回切 Tab。
 * `u.y.qq.com` 对同一 IP 的突发非常敏感，实测连续几次之后先返回 `code=2001`，
 * 再往后变成 `code=0` 但所有 `item_*` 都是空数组（软限流）—— 界面上看就是「搜不到这首歌」。
 * 请求形状（[qqComm] / [qqHeaders]）修好之后，剩下能做的就是别把请求挤在一起发。
 */
private val qqMusicuMutex = Mutex()

/** 两次 musicu 请求之间的最小间隔（毫秒）。真实客户端也不会一秒打十几个。 */
private const val QQ_MUSICU_MIN_GAP_MS = 260L

private var qqMusicuLastPostAt = 0L

/**
 * 发一次 musicu 请求。
 *
 * @return 原始 JSON；连 `code=2001` 重试之后仍未成功时返回最后一次的结果（调用方按空处理）。
 */
internal suspend fun qqMusicuPost(payload: Map<String, Any?>): Map<String, Any?> =
    qqMusicuMutex.withLock {
        var result: Map<String, Any?> = emptyMap()
        for (attempt in 0..1) {
            val wait = QQ_MUSICU_MIN_GAP_MS - (System.currentTimeMillis() - qqMusicuLastPostAt)
            if (wait > 0) delay(wait)
            result = try {
                httpPostJson(qqMusicuUrl, qqHeaders, payload)
            } catch (_: Throwable) {
                if (attempt == 0) {
                    delay(QQ_MUSICU_RETRY_MS)
                    continue
                }
                throw IllegalStateException("QQ 请求失败")
            }
            qqMusicuLastPostAt = System.currentTimeMillis()
            if (!qqMusicuRejected(result)) break
            // 2001 = 明确拒绝：退避一次再来。第二次仍被拒就交给上层当空结果。
            if (attempt == 0) delay(QQ_MUSICU_RETRY_MS + (0..300).random())
        }
        result
    }

/** 被拒后的退避基数（毫秒） */
private const val QQ_MUSICU_RETRY_MS = 600L

/** 顶层任一 `req_*` 的 `code` 非 0（实测 2001）即视为被拒；空响应也算 */
private fun qqMusicuRejected(data: Map<String, Any?>): Boolean {
    if (data.isEmpty()) return true
    for ((key, value) in data) {
        if (!key.startsWith("req_")) continue
        val code = (value as? Map<*, *>)?.get("code") ?: continue
        val n = code.toString().toDoubleOrNull()?.toInt() ?: 0
        if (n != 0) return true
    }
    return false
}
