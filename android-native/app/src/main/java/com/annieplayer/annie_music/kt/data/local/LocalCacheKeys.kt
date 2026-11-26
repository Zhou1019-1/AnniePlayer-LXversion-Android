package com.annieplayer.annie_music.kt.data.local

/**
 * 本地曲库两个「缓存身份」规则，抽出来是为了能被单测锁住。
 *
 * 它们决定了两件用户能直接感知的事：
 * - [isCacheFresh]：改了标签 / 换了内嵌封面之后，下次进曲库**会不会更新**；
 * - [coverFileName]：换了封面之后，界面上**显示的是新图还是旧图**。
 *
 * 两者都以音频文件的 `mtime + size` 为唯一依据，理由见各自的注释。
 */

/**
 * 「这条索引记录还能直接复用吗」。
 *
 * 只有两个依据：`mtime` 与 `size`。
 * - **改标签（含内嵌封面）必然改 mtime** —— 外部软件重打标签后下一次扫描自动重读，
 *   不需要用户点「重扫」；文件被整体替换（换源 / 重新下载）也一定命中。
 * - 两个字段必须**一起**比：只看 mtime 会漏掉「内容变了但时间戳被还原」，
 *   只看 size 会漏掉所有原地打标（复用补白写 tag 时文件大小可能一模一样）。
 * - `probed = false` 一律不算命中：那是上次扫到一半被杀留下的占位行，里面只有
 *   从文件名回落的标题，拿它当缓存会在列表里永久显示错误信息。
 *
 * 已知盲区：能同时把 mtime 还原、size 也不变的改写（`utime` 回写型工具）检测不到，
 * 这类只能靠用户点「重扫」（`force = true`）。
 */
internal fun isCacheFresh(cached: LocalTrackMeta, mtime: Long, size: Long): Boolean =
    cached.probed && cached.mtime == mtime && cached.size == size

/**
 * 封面缓存文件名的**路径前缀**（不含文件戳与扩展名）。
 *
 * 两段不同来源的哈希（全长 + 尾 24 字符）是为了同时压掉「不同路径 hashCode 撞了」
 * 和「文件名相同、目录不同」这两种碰撞。
 */
internal fun coverNamePrefix(path: String): String =
    Integer.toHexString(path.hashCode()) + "_" + Integer.toHexString(path.takeLast(24).hashCode())

/**
 * 封面缓存文件名：`<前缀>_<mtime>_<size>.jpg`。
 *
 * 把音频文件戳写进**文件名**，而不是只记在索引里，有两个不可替代的作用：
 * 1. 换了内嵌封面 → 戳变 → 新名字，旧图不可能被当成新封面复用；
 * 2. Coil 的缓存键就是 URI 字符串，名字变了它才会重新解码 —— 否则同一个路径
 *    会一直命中内存里的旧 Bitmap（表现是「换了封面，重扫也没用」）。
 *
 * 用十六进制是因为 mtime 有 13 位十进制，两个拼起来快 30 字符，白占目录项长度。
 */
internal fun coverFileName(path: String, mtime: Long, size: Long): String =
    coverNamePrefix(path) + "_" + mtime.toString(16) + "_" + size.toString(16) + ".jpg"
