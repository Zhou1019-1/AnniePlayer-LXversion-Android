package com.annieplayer.annie_music.kt.data.sources.qq

// ================================================================================================
// QQ 音乐常量与请求头
// ================================================================================================
//
// 包内分工（一个用途一个文件）：
//   QqConfig   请求头 / musicu 端点与 guid
//   QqJson     JSON 取值 / BOM / 前缀剥离
//   QqTypes    跨文件共用的记录
//   QqQuality  音质与字节数判定（下游唯一依据）
//   QqMapper   JSON -> Song / Album / Artist（纯函数）
//   QqSearch   歌曲 / 专辑 / 歌手搜索
//   QqAlbum    专辑曲目 / 单曲补充
//   QqArtist   简介 / 单曲分页 / 专辑
//   QqPlaylist 歌单分页
//   QqLyric    歌词（QRC 逐字 + 翻译）
//
// 直链取链（长青解析，原 `QqPlay.kt`）已删除：QQ 的播放/下载统一由落雪插件解析。

/** QQ 音乐网页 API 通用请求头 */
val qqHeaders: Map<String, String> = mapOf(
    // 完整 Chrome UA：以前截断成「…Safari/537.36」（没有 Chrome/xxx），风控侧一眼认出不是浏览器
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    "Referer" to "https://y.qq.com/",
    "Origin" to "https://y.qq.com",
)

/** 移动端搜索接口（单次 60 条，比旧网页接口快约 3 倍）。 */
internal const val qqMusicuUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg"

/** musicu `comm.guid`：32 位 hex，进程内随机一次。 */
internal val qqMusicuGuid: String by lazy {
    val hex = "0123456789abcdef"
    (1..32).joinToString("") { hex[(Math.random() * 16).toInt()].toString() }
}

/**
 * musicu 的 `comm` 块（**所有** musicu 请求都必须带这一份）。
 *
 * `ct` / `cv` 是客户端标识，选错不是"被拒"，而是**静默空结果**。实测（同一 IP，轮换请求）：
 * - `ct=11, cv=0`（网页版）→ `code=0`，`item_song` 有 20 条；
 * - `ct=24, cv=4747474`（Android 客户端）→ `code=0`，所有 `item_*` 数组全空（搜索永远"搜不到"）；
 * - `ct=19, cv=1914`（旧网页版，本文件原来的值）→ `code=2001`，风控直接拒绝。
 *
 * 所以这里**只能**用 11 / 0。`format` / `inCharset` / `outCharset` / `platform` /
 * `needNewCode` / `uin` / `g_tk` 真实客户端都带，补上它们的同时保持 11 / 0 才稳定。
 */
internal fun qqComm(
    ct: Int = 11,
    cv: Int = 0,
): Map<String, Any?> = mapOf(
    "cv" to cv,
    "ct" to ct,
    "format" to "json",
    "inCharset" to "utf-8",
    "outCharset" to "utf-8",
    "notice" to 0,
    "platform" to "yqq.json",
    "needNewCode" to 1,
    "uin" to "0",
    "g_tk" to 5381,
    "guid" to qqMusicuGuid,
)
