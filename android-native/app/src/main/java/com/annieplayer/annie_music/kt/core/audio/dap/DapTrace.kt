package com.annieplayer.annie_music.kt.core.audio.dap

/**
 * 内置 DAC 直通链路的**临时**时间序列采样。
 *
 * 存在的理由：这条路上的每个停摆都发生在**别的线程**上（Media3 渲染线程、loader 线程、
 * AudioTrack 内部线程），`files/audio-status.txt` 只记"某一刻的一句话结论"，
 * 看不出"谁先停"。这里按毫秒时标把整条链的读数写成一列，才能定责。
 *
 * 纪律（AGENTS.md）：这是诊断代码，**定位完必须删干净**。行数上限就是为此设的。
 */
internal object DapTrace {

    private const val FILE_NAME = "audio-trace.txt"

    /** 上限：一首曲目 20 秒的采样绰绰有余，又不会把闪存写满 */
    private const val MAX_LINES = 8000

    @Volatile
    private var file: java.io.File? = null

    private var lines = 0

    /** 只用 JVM 时钟：`DapDirectOutputTest` 会走到这条链，`SystemClock` 在那里是 not mocked */
    private val t0 = System.nanoTime()

    /** 每次进程启动重来一份（旧文件直接删掉，避免和上一轮混在一起） */
    fun init(dir: java.io.File) {
        val f = java.io.File(dir, FILE_NAME)
        runCatching {
            if (f.exists()) f.delete()
            f.createNewFile()
        }
        synchronized(this) {
            lines = 0
            file = f
        }
    }

    fun log(tag: String, msg: String) {
        val f = file ?: return
        if (lines >= MAX_LINES) return
        val t = (System.nanoTime() - t0) / 1_000_000L
        synchronized(this) {
            if (lines >= MAX_LINES) return
            lines++
            runCatching { f.appendText("$t\t$tag\t$msg\n") }
        }
    }

    /** 诊断是否启用（未 init 时不要启看门狗线程之类的设施） */
    val enabled: Boolean get() = file != null
}

/** 未初始化时静默丢弃，链路上可以随手写 */
internal inline fun dapTrace(tag: String, msg: () -> String) {
    runCatching { DapTrace.log(tag, msg()) }
}

/** 诊断是否启用：没 init 就不要启看门狗线程之类的设施 */
internal val dapTraceEnabled: Boolean get() = DapTrace.enabled
