package com.annieplayer.annie_music.kt.data.sources.common

import com.annieplayer.annie_music.kt.data.model.MusicPlatform

/**
 * 「搜索元数据」的相关度排序（纯逻辑，不碰网络/Compose）。
 *
 * 规则由用户定死：**按「标题 / 艺术家 / 专辑」三项的匹配度排序**，Apple Music 只在
 * 匹配度相同时排前面。刻意**不看时长、不看封面、不看平台口碑** —— 时长接近只是"可能同一首"，
 * 而一首歌在不同平台上的时长能差 10 秒（前奏/尾奏/无间隙版），拿它当主信号会把精确匹配挤到后面。
 */

/** 本地文件当前的三个关键字段（打分基准）。 */
data class RankQuery(
    val title: String,
    val artist: String,
    val album: String,
)

/** 一条候选结果的三字段 + 平台。 */
data class RankCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val platform: MusicPlatform,
)

object MetadataRank {

    /** 括号里的内容不参与比较：`(feat. X)` / `[Remastered 2011]` / `（Live）` */
    private val BRACKETED = Regex("""[(\[（【][^)\]）】]*[)\]）】]""")

    /** 归一化：小写 + 去括号内容 + 只留字母/数字/汉字 */
    internal fun normalize(raw: String): String {
        val stripped = BRACKETED.replace(raw.lowercase(), " ")
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /** 字符集合重叠率：中文没有空格分词，用字符级 Jaccard 比"词元"稳 */
    private fun overlap(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toHashSet()
        val sb = b.toHashSet()
        var inter = 0
        for (c in sa) if (c in sb) inter++
        val union = sa.size + sb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * 单字段打分。
     *
     * 两档"完全匹配"是故意的：**原文一模一样**（`안녕 Goodbye`）比**剥掉括号后一样**
     * （`안녕 Goodbye (Live)`）更可信 —— 后者是现场/重制版，选它替换元数据会把版本信息写错。
     */
    private fun fieldScore(
        queryRaw: String,
        candidateRaw: String,
        equal: Int,
        prefix: Int,
        contains: Int,
        overlapMax: Int,
    ): Int {
        val q = normalize(queryRaw)
        val c = normalize(candidateRaw)
        if (q.isEmpty() || c.isEmpty()) return 0
        return when {
            queryRaw.trim().equals(candidateRaw.trim(), ignoreCase = true) -> equal + 20
            q == c -> equal
            c.startsWith(q) || q.startsWith(c) -> prefix
            c.contains(q) || q.contains(c) -> contains
            else -> (overlap(q, c) * overlapMax).toInt()
        }
    }

    /** 标量与权重的唯一来源（改权重只改这里） */
    fun score(query: RankQuery, candidate: RankCandidate): Int =
        fieldScore(query.title, candidate.title, equal = 120, prefix = 80, contains = 50, overlapMax = 40) +
            fieldScore(query.artist, candidate.artist, equal = 60, prefix = 30, contains = 30, overlapMax = 20) +
            fieldScore(query.album, candidate.album, equal = 25, prefix = 12, contains = 12, overlapMax = 10)

    /** 打过分的一条（算一次分，排序与置顶共用） */
    private data class Scored<T>(
        val index: Int,
        val item: T,
        val candidate: RankCandidate,
        val score: Int,
    )

    /**
     * 稳定排序：**把 Apple Music 里最匹配的那一条置顶，其余全部按匹配度降序**。
     *
     * 这是用户第三次收口的口径，也是最明确的一次：
     * 「am 优先的只是 am 里最匹配的那一个放在前面，其余放在后面」。
     *
     * 于是：
     * 1. 从 Apple 结果里挑 `score` 最高的那条（同分取先出现的）→ 放第一位；
     * 2. 包括**剩下的 Apple 结果在内**的所有条目，一起按 `score` 降序（同分保持接口顺序）。
     *
     * 注意这里刻意**不是**「Apple 整段置顶」：那样第一条仍然是 Apple，但第二条、第三条也还是
     * Apple 的烂结果；也不是「同档优先」：那会把 Apple 的第二名也抬上来。只抬第一名。
     */
    fun <T> rank(query: RankQuery, items: List<T>, describe: (T) -> RankCandidate): List<T> {
        if (items.size < 2) return items
        val scored = items.mapIndexed { index, item ->
            val candidate = describe(item)
            Scored(index, item, candidate, score(query, candidate))
        }
        val byScore = compareByDescending<Scored<T>> { it.score }.thenBy { it.index }
        val topApple = scored
            .filter { it.candidate.platform == MusicPlatform.apple }
            .sortedWith(byScore)
            .firstOrNull()
        val rest = scored.filter { it !== topApple }.sortedWith(byScore)
        return buildList(items.size) {
            topApple?.let { add(it.item) }
            rest.forEach { add(it.item) }
        }
    }
}
