package com.annieplayer.annie_music.kt.core.audio.dap

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **自管节奏的写入器** —— 环 + 独立写线程，`offer` 永不因设备拒收而阻塞调用方。
 *
 * ## 为什么必须存在
 *
 * 自己开流的设备（内置 DAC 的私有 DIRECT 端口 / UAC 端点 / AAudio MMAP）**不保证两件事**：
 * 1. 写入会可靠背压 —— 端口满时 `AudioTrack::write` 返回 `-11`（`EWOULDBLOCK`）；
 * 2. 位置可信 —— 私有 DSD 端口的 `getPosition` 会返回 `-38`。
 *
 * 而 Media3 的 `AudioSink` 契约假设两者都成立。把设备当同步文件写，就会演变成
 * "一次播放 3883 次写失败、位置要么不动要么跳、`hasPendingData` 说谎"这一族症状。
 *
 * 所以这一层把两个世界隔开：上层 `offer` 只管交字节，设备侧由这里按它的节奏喂。
 *
 * ## 两条通道一起干活
 *
 * [offer] 内部**先尝试同步喂一段**（设备有空就当场写进去），剩下的留在环里由后台线程继续。
 * 这样：
 * - 设备健康时行为与"同步写"完全一致（不会凭空多一次线程调度）；
 * - 设备卡顿时上层不再被打回，Media3 也不会陷入"同一块反复重试"。
 *
 * ## 环形缓冲的容量
 *
 * 按**时间**给（`capacityFrames`），不是按字节：水位要能和"缓冲多少毫秒"对得上。
 */
internal class SelfPacedWriter(
    private val capacityFrames: Int,
    bytesPerFrame: Int,
    /** 一次交给设备的块上限（越小越平滑，越大越省系统调用） */
    private val deviceChunkFrames: Int,
    name: String,
    /** 写进设备：`>0` 收到这么多字节；`0` = 设备暂时不收；`<0` = 流坏了 */
    private val writeToDevice: (ByteBuffer) -> Int,
    /** 每成功写进设备一次（用来推进"已写帧"记账） */
    private val onDeviceWritten: (Int) -> Unit,
    /** `<0` 时回调一次（流坏了要上报/重建） */
    private val onFatal: () -> Unit = {},
) {

    private val frameBytes = bytesPerFrame.coerceAtLeast(1)

    private val ring = Ring(capacityFrames.coerceAtLeast(1) * frameBytes)

    /** 交给设备的暂存区（必须是 direct：native 侧走 `GetDirectBufferAddress`） */
    private val staging = ByteBuffer.allocateDirect(
        (deviceChunkFrames.coerceAtLeast(1) * frameBytes).coerceAtLeast(frameBytes),
    )

    private val lock = Any()

    /** 同一时刻只允许一个 drainer（调用方线程与后台线程会竞争） */
    private val draining = AtomicBoolean(false)

    @Volatile
    private var running = true

    /**
     * 已经停过。**停机之后绝不再碰设备** —— 这是 [stop] 的契约，不只是"别再写环"。
     *
     * 为什么必须是单独一个标志：调用方（`DapDirectOutput.release`）停完这里立刻就会
     * `close()` 掉设备句柄。线程若还活着并调了一次 `writeToDevice`，就会拿到
     * `-1`（句柄已失效）→ `onFatal` → 上游把这一档 native 记成"不支持" → 之后连
     * PCM 重建都跳过它。实测症状是"切回系统通路也一声不出，只有重启才恢复"。
     */
    @Volatile
    private var stopped = false

    /**
     * 允不允许把环里的数据继续交给设备。
     *
     * **暂停就靠这一位**，不靠 `AudioTrack.pause()` —— 实测这台飞傲 HAL 的私有 DSD 端口上
     * `pause()` 会**不返回**（trace：`control stall: pause（800ms 未返回）`），而那一行之后
     * 环里的数据仍在被灌进设备（`ring` 从 159 k 涨到 421 k），于是"按了暂停声音还在放"。
     * 参考实现（Resonāda 的 `sub_24AD90` 等三条产出路径）里**没有任何 pause 调用**，
     * 它的"暂停"就是不再产出/不再写。
     *
     * 关掉之后设备会在自己的缓冲（约 0.37 s）排空后停下，恢复时再打开并继续喂。
     */
    @Volatile
    private var feeding = true

    /** 播放/暂停的电平语义（晚几毫秒无所谓，不要求边沿精确） */
    fun setFeeding(on: Boolean) {
        feeding = on
        if (on && running && !stopped && synchronized(lock) { ring.filled } > 0) start()
    }

    private val thread = Thread({ loop() }, name).apply { isDaemon = true }

    fun start() {
        synchronized(this) {
            if (stopped || thread.isAlive) return
            thread.start()
        }
    }

    /**
     * 停止并**等线程退出**，之后再也不会调 [writeToDevice]。
     *
     * 只 `running = false + interrupt` 是不够的：线程可能正卡在 native `write` 里，
     * 它会在 `interrupt` 之后**继续跑完这一轮**。所以这里要 join。
     * join 超时（native 卡死）时也照样结束，靠 [stopped] 保证不再进设备：
     * 那个残留的线程会从 `writeToDevice` 返回后看到 `stopped` 直接退出。
     */
    fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
            running = false
        }
        thread.interrupt()
        if (Thread.currentThread() !== thread) {
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        synchronized(lock) { ring.clear() }
    }

    /**
     * 环里还有多少字节没交给设备
     *
     * 设备健康时它恒为 0 —— 因为 [offer] 内部会当场喂完。只有设备开始拒收，
     * 数据才会留在这里、后台线程才会启动（见 [offer]）。
     */
    fun pendingBytes(): Int = if (stopped) 0 else synchronized(lock) { ring.filled }

    /**
     * 接收一段字节。
     *
     * **先同步喂一次**：设备有空时这一步就喂完了，后台线程根本不会启动 ——
     * 健康路径上没有多一次线程调度，行为与"同步写"完全一致。
     * 只有当设备拒收、环里留下数据时，才拉起后台线程接管。
     *
     * @return 实际接收的字节数（< 请求量 = 环满了，调用方据此施加背压）
     */
    fun offer(src: ByteBuffer): Int {
        if (stopped) return 0
        val accepted = synchronized(lock) { if (stopped) 0 else ring.write(src) }
        drain()
        if (running && !stopped && synchronized(lock) { ring.filled } > 0) start()
        return accepted
    }

    /** 丢掉环里所有未交给设备的字节（seek / 换曲） */
    fun reset() {
        synchronized(lock) { ring.clear() }
    }

    private fun loop() {
        while (running && !stopped) {
            val wrote = drain()
            if (wrote == 0) {
                try {
                    Thread.sleep(IDLE_SLEEP_MS)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * 尽量把环里的数据交给设备，返回这一轮写进去的字节数。
     *
     * 设备返回 0（暂时不收）就立刻收手 —— 绝不在这里等，等待是后台线程的事。
     */
    private fun drain(): Int {
        if (stopped) return 0
        // 暂停中：一个字节都不许再进设备（见 [feeding]）
        if (!feeding) return 0
        if (!draining.compareAndSet(false, true)) return 0
        var total = 0
        try {
            while (running && !stopped && feeding) {
                staging.clear()
                val chunk = synchronized(lock) {
                    val n = minOf(ring.filled, staging.capacity())
                    if (n <= 0) return total.also { /* nothing to do */ }
                    ring.peek(staging, n)
                    n
                }
                staging.limit(chunk)
                staging.position(0)
                val written = try {
                    writeToDevice(staging)
                } catch (t: Throwable) {
                    onFatal()
                    return total
                }
                if (written > 0) {
                    synchronized(lock) { ring.discard(written) }
                    onDeviceWritten(written)
                    total += written
                    continue
                }
                if (written < 0) {
                    // 流坏了：把环清掉别再无意义地重试，并上报
                    synchronized(lock) { ring.clear() }
                    onFatal()
                }
                return total
            }
        } finally {
            draining.set(false)
        }
        return total
    }

    /** 单生产者 + 单消费者，但两者可能在不同线程：全部方法都在 [lock] 内调用 */
    private class Ring(private val capacity: Int) {
        private val buf = ByteArray(capacity)
        private var head = 0
        private var tail = 0
        var filled = 0
            private set

        fun clear() {
            head = 0
            tail = 0
            filled = 0
        }

        fun write(src: ByteBuffer): Int {
            val room = capacity - filled
            if (room <= 0) return 0
            val n = minOf(room, src.remaining())
            if (n <= 0) return 0
            var left = n
            while (left > 0) {
                val contiguous = minOf(left, capacity - tail)
                src.get(buf, tail, contiguous)
                tail = (tail + contiguous) % capacity
                left -= contiguous
            }
            filled += n
            return n
        }

        /** 只读拷贝出 [n] 字节到 [dst]，**不消费** */
        fun peek(dst: ByteBuffer, n: Int) {
            var left = n
            var pos = head
            while (left > 0) {
                val contiguous = minOf(left, capacity - pos)
                dst.put(buf, pos, contiguous)
                pos = (pos + contiguous) % capacity
                left -= contiguous
            }
        }

        fun discard(n: Int) {
            val k = n.coerceAtMost(filled)
            head = (head + k) % capacity
            filled -= k
        }
    }

    private companion object {
        const val IDLE_SLEEP_MS = 2L

        /**
         * [stop] 等写线程退出的上限。
         *
         * 正常路径线程在 2 ms 内看到 `running=false` 就退出；这里等的是"它正卡在
         * 一次 native `write` 里"。非阻塞写应当几毫秒返回，250 ms 只用来兜住
         * HAL 偶发的慢路径 —— 再长就会拖住 Media3 的播放线程（`release()` 在它上面）。
         */
        const val STOP_JOIN_MS = 250L
    }
}
