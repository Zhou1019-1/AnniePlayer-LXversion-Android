package com.annieplayer.wrapperlite.qemu

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 首次启动时把 assets 里的 guest 资产释放到 filesDir。
 *
 * assets 结构：
 * ```
 * guest/vmlinuz
 * guest/initramfs.cpio.gz
 * guest/data.empty.img.gz     （空 ext4 账号盘，压缩后极小）
 * qemu-fw/bios.bin ...        （QEMU -L 目录）
 * ```
 */
object AssetsInstaller {

    private const val GUEST_DIR = "guest"
    private const val FW_DIR = "qemu-fw"
    private const val LIB_DIR = "lib"
    private const val BUF = 1 shl 16

    /**
     * 只有空盘镜像需要解压成裸 ext4（guest 要挂载它）。
     *
     * 扩展名故意用 `.dat` 而不是 `.gz`：AGP 的 mergeDebugAssets 阶段会把 `.gz`
     * 资源自动解压掉，导致 APK 里塞进 124MB 的裸 initramfs。
     */
    private const val EMPTY_IMG_ARCHIVE = "data.empty.dat"

    data class Progress(val message: String, val fraction: Float)

    /** 释放环境；已存在的大文件跳过，可重复调用。 */
    fun install(ctx: Context, onProgress: (Progress) -> Unit = {}) {
        val guest = WrapperPaths.guest(ctx).apply { mkdirs() }
        val names = ctx.assets.list(GUEST_DIR)?.toList().orEmpty()
        val total = names.size + 1
        var step = 0

        onProgress(Progress("准备运行环境…", 0f))

        for (name in names) {
            step++
            val fraction = step.toFloat() / total
            if (name == EMPTY_IMG_ARCHIVE) {
                val plain = File(guest, "data.empty.img")
                if (plain.length() > 0) continue
                onProgress(Progress("释放 ${plain.name}…", fraction))
                ctx.assets.open("$GUEST_DIR/$name").use { input ->
                    GZIPInputStream(input).use { gz ->
                        plain.outputStream().use { out -> gz.copyTo(out, BUF) }
                    }
                }
            } else {
                // initramfs.dat 保持原始的 gzip 字节，QEMU 的 -initrd 认 gzip magic
                val target = File(guest, name)
                if (target.length() > 0) continue
                onProgress(Progress("释放 $name…", fraction))
                ctx.assets.open("$GUEST_DIR/$name").use { input ->
                    target.outputStream().use { out -> input.copyTo(out, BUF) }
                }
            }
        }

        // firmware：含 keymaps 子目录，必须递归
        val fwDir = WrapperPaths.firmwareDir(ctx).apply { mkdirs() }
        copyAssetTree(ctx, FW_DIR, fwDir)

        // QEMU 的依赖库（扁平目录，跑的时候靠 LD_LIBRARY_PATH 找到）
        val libDir = WrapperPaths.libDir(ctx).apply { mkdirs() }
        copyAssetTree(ctx, LIB_DIR, libDir)

        // 账号盘：不存在就用空盘初始化
        val data = WrapperPaths.dataImg(ctx)
        if (!data.exists() || data.length() == 0L) {
            WrapperPaths.emptyAccountImg(ctx).copyTo(data, overwrite = true)
        }

        onProgress(Progress("运行环境已就绪", 1f))
    }

    /** 递归复制 assets 目录（QEMU 的 keymaps 是子目录）。 */
    private fun copyAssetTree(ctx: Context, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val children = ctx.assets.list(assetPath).orEmpty()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childTarget = File(targetDir, name)
            val nested = ctx.assets.list(childAsset).orEmpty()
            if (nested.isNotEmpty()) {
                copyAssetTree(ctx, childAsset, childTarget)
            } else {
                if (childTarget.length() > 0) continue
                ctx.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { out -> input.copyTo(out, BUF) }
                }
            }
        }
    }

    /**
     * 退出登录 / 切换账号：token、session、设备标识全在 data.img 这一张镜像里，
     * 换成空盘就等于彻底清空账号状态（同时保留已装好的 guest 资产）。
     */
    fun resetAccount(ctx: Context) {
        val data = WrapperPaths.dataImg(ctx)
        if (data.exists()) data.delete()
        WrapperPaths.emptyAccountImg(ctx).copyTo(data, overwrite = true)
    }
}
