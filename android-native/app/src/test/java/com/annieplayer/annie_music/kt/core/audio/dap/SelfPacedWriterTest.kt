package com.annieplayer.annie_music.kt.core.audio.dap

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SelfPacedWriter` 的**停机契约**与**拒收恢复**。
 *
 * 这两条都是真机踩出来的，不是理论：
 * - 设备返回 `-11`（`EWOULDBLOCK`）时不能当成流坏了 —— 那是"暂时不收"，
 *   当成致命错误就等于"第一次卡顿就判这条路不支持"；
 * - `stop()` 之后绝不能再调 `writeToDevice`。调用方（`DapDirectOutput.release`）
 *   停完立刻 `close()` 句柄，此时再写一次就会拿到 `-1` → `onFatal` → 上游把这一档
 *   native 记成失败 → 之后连 PCM 重建都跳过它（实测：切回系统通路没声，要重启 App）。
 */
class SelfPacedWriterTest {

    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(2)
        }
        return cond()
    }

    @Test
    fun `设备先拒收再恢复时能持续写出`() {
        val calls = AtomicInteger()
        val deviceBytes = AtomicInteger()
        // 前 4 次 = AudioTrack 返回 -11（EWOULDBLOCK），`DapDirectOutput` 把它映射成 0
        val refusals = 4
        val writer = SelfPacedWriter(
            capacityFrames = 200,
            bytesPerFrame = 4,
            deviceChunkFrames = 32,
            name = "test-transient",
            writeToDevice = { buf ->
                val n = buf.remaining()
                if (calls.incrementAndGet() <= refusals) {
                    0
                } else {
                    buf.position(buf.position() + n)
                    deviceBytes.addAndGet(n)
                    n
                }
            },
            onDeviceWritten = {},
        )

        val data = ByteBuffer.allocate(200 * 4)
        repeat(200 * 4) { data.put(0) }
        data.flip()

        assertEquals("整块必须被环接住（不阻塞调用方）", 800, writer.offer(data))
        assertTrue(
            "设备恢复后后台线程应把 800 字节全部交出，实际 ${deviceBytes.get()}",
            awaitUntil(3_000) { deviceBytes.get() == 800 },
        )
        assertEquals("环应已排空", 0, writer.pendingBytes())
        assertTrue("4 次拒收 + 至少 7 次写满 800 字节，实际 ${calls.get()}", calls.get() >= 11)

        writer.stop()
    }

    @Test
    fun `stop 之后绝不再调用 writeToDevice`() {
        val calls = AtomicInteger()
        val writer = SelfPacedWriter(
            capacityFrames = 200,
            bytesPerFrame = 4,
            deviceChunkFrames = 32,
            name = "test-stop",
            // 永远"暂时不收"：线程会一直在环上打转，正是最容易在停机后还写一次的状态
            writeToDevice = {
                calls.incrementAndGet()
                0
            },
            onDeviceWritten = {},
        )

        val data = ByteBuffer.allocate(200 * 4)
        repeat(200 * 4) { data.put(0) }
        data.flip()
        writer.start()
        assertEquals(800, writer.offer(data))
        assertTrue("写线程应已经在轮询设备", awaitUntil(3_000) { calls.get() >= 3 })

        writer.stop()
        val atStop = calls.get()
        Thread.sleep(150)
        assertEquals("stop 返回后不许再碰设备", atStop, calls.get())

        assertEquals("停机后 offer 一律不受理", 0, writer.offer(ByteBuffer.allocate(64)))
        assertEquals("不受理就不该再写", atStop, calls.get())
    }
}
