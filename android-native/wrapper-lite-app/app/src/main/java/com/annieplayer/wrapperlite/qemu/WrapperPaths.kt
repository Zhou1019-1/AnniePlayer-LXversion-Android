package com.annieplayer.wrapperlite.qemu

import android.content.Context
import java.io.File

/**
 * wrapper-lite QEMU guest 的所有本地路径。
 *
 * filesDir 下的布局：
 * ```
 * wrapper/guest/vmlinuz               guest 内核 (x86_64)
 * wrapper/guest/initramfs.cpio.gz     initramfs，内含 lite + Apple 私有库
 * wrapper/guest/data.empty.img        空白账号盘（退出/切换账号时覆盖用）
 * wrapper/data.img                    当前账号盘，token/session 全在这里
 * wrapper/qemu-fw/                    QEMU firmware（-L 指向这里）
 * wrapper/lite-args                   guest 参数，一行一个
 * ```
 */
object WrapperPaths {

    /** 对外端口：CORS 代理监听这里，浏览器 / App 都连它。 */
    const val PORT = 12340

    /** QEMU hostfwd 的内部端口，只给 [CorsProxy] 转发用。 */
    const val UPSTREAM_PORT = 12341

    fun root(ctx: Context) = File(ctx.filesDir, "wrapper")

    fun guest(ctx: Context) = File(root(ctx), "guest")

    fun kernel(ctx: Context) = File(guest(ctx), "vmlinuz")

    fun initramfs(ctx: Context) = File(guest(ctx), "initramfs.dat")

    fun emptyAccountImg(ctx: Context) = File(guest(ctx), "data.empty.img")

    fun dataImg(ctx: Context) = File(root(ctx), "data.img")

    fun firmwareDir(ctx: Context) = File(root(ctx), "qemu-fw")

    /**
     * QEMU 的运行时依赖库（gnutls/glib/pixman/curl…，都是从 Termux 抽出来的 aarch64 so）。
     * 通过 `LD_LIBRARY_PATH` 指给子进程，不占 nativeLibraryDir。
     */
    fun libDir(ctx: Context) = File(root(ctx), "lib")

    fun argsFile(ctx: Context) = File(root(ctx), "lite-args")

    /**
     * 打包时把 aarch64 的 QEMU 可执行文件改名成 `libqemu_x86_64.so` 放进 jniLibs：
     * Android 10+ 只允许从 nativeLibraryDir 执行二进制，改成 .so 名才拿得到 exec 权限。
     */
    fun qemuBin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libqemu_x86_64.so")

    fun assetsInstalled(ctx: Context) =
        kernel(ctx).length() > 0 &&
            initramfs(ctx).length() > 0 &&
            emptyAccountImg(ctx).length() > 0 &&
            !libDir(ctx).list().isNullOrEmpty()
}
