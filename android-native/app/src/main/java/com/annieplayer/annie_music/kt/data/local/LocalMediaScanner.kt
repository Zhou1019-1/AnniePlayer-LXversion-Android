package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File

internal object LocalMediaScanner {

    internal val PRIORITY_DIRS = listOf(
        "Music", "AnnieMusic", "Download", "Downloads", "Ringtones", "Notifications",
        "Alarms", "Podcasts", "Recordings", "Documents", "录音", "音乐", "网易云音乐", "QQ音乐",
        "netease", "cloudmusic", "CloudMusic", "NetEase",
        "kugou", "kuwo", "qqmusic", "kgmusic", "migu",
    )

    internal val SKIP_DIRS = setOf(
        "Android", "DCIM", "Pictures", "Movies", "LOST.DIR", ".thumbnails", ".trash",
        "tencent", "MicroMsg", "cache", "Cache", "obb", "data", "MIUI", "backups",
        "Telegram", "WeChat", "com.tencent.mm", "node_modules",
    )

    private const val MAX_DEPTH = 7

    private const val MAX_FILES = 50_000

    private const val NOMEDIA = ".nomedia"

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun scan(
        context: Context,
        force: Boolean = false,
        onProgress: (Int) -> Unit = {},
    ): List<File> {
        val fromFs = if (hasAllFilesAccess()) _scanFileSystem(context, force, onProgress) else emptyList()
        if (fromFs.isNotEmpty()) return fromFs
        return _scanMediaStore(context)
    }

    fun storageRoots(context: Context): List<File> = _storageRoots(context)

    private fun _scanFileSystem(
        context: Context,
        force: Boolean,
        onProgress: (Int) -> Unit,
    ): List<File> {
        val roots = _storageRoots(context)
        if (roots.isEmpty()) return emptyList()

        val index = if (force) {
            LocalScanIndex.clear()
            emptyMap()
        } else {
            LocalScanIndex.load()
        }
        val found = LinkedHashMap<String, File>()
        val newIndex = HashMap<String, LocalScanIndex.DirEntry>()
        val visitedDirs = HashSet<String>()
        var visited = 0

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val path = dir.absolutePath
            if (LocalScanExclusions.isDirExcluded(path)) return
            if (LocalScanSettings.respectNoMediaNow && File(dir, NOMEDIA).isFile) return
            if (!visitedDirs.add(path)) return
            val mtime = dir.lastModified()
            val cached = index[path]
            if (cached != null && mtime != 0L && cached.mtime == mtime) {
                for (f in cached.files) {
                    if (found.size >= MAX_FILES) break
                    found[f] = File(f)
                }
                newIndex[path] = cached
                for (sub in cached.subdirs) walk(File(sub), depth + 1)
                return
            }
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: return
            visited++
            onProgress(visited)
            val subdirs = ArrayList<String>()
            val files = ArrayList<String>()
            for (child in children) {
                val name = child.name
                if (LocalScanSettings.skipHiddenDirsNow && name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (name in SKIP_DIRS) continue
                    subdirs += child.absolutePath
                    walk(child, depth + 1)
                } else if (found.size < MAX_FILES && isAudioFileName(name)) {
                    files += child.absolutePath
                    found[child.absolutePath] = child
                }
            }
            newIndex[path] = LocalScanIndex.DirEntry(
                path = path,
                mtime = mtime,
                subdirs = subdirs,
                files = files,
            )
        }

        for (root in roots) {
            for (name in PRIORITY_DIRS) {
                val dir = File(root, name)
                if (dir.isDirectory) walk(dir, 0)
            }
        }
        val walkedRoots = HashSet<String>()
        for (root in roots) {
            if (found.size >= MAX_FILES) break
            walkedRoots += root.absolutePath
            walk(root, 0)
        }
        for ((p, e) in index) {
            if (newIndex.containsKey(p)) continue
            if (LocalScanExclusions.isDirExcluded(p)) continue
            if (walkedRoots.none { p.startsWith("$it/") }) newIndex[p] = e
        }
        if (newIndex != index) LocalScanIndex.save(newIndex)
        return _sorted(found.values)
    }

    private fun _storageRoots(context: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        fun add(dir: File?) {
            if (dir == null) return
            val path = dir.absolutePath
            if (path.isEmpty() || path == "/") return
            if (out.containsKey(path)) return
            val usable = runCatching { dir.isDirectory && !dir.list().isNullOrEmpty() }
                .getOrDefault(false)
            if (usable) out[path] = dir
        }

        @Suppress("DEPRECATION")
        add(Environment.getExternalStorageDirectory())

        runCatching { context.getExternalFilesDirs(null) }.getOrNull()
            ?.filterNotNull()
            ?.forEach { add(volumeRootOf(it.absolutePath)?.let(::File)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            runCatching { sm?.storageVolumes }.getOrNull()
                ?.forEach { v -> add(runCatching { v.directory }.getOrNull()) }
        }

        for (parent in listOf("/storage", "/mnt/media_rw")) {
            File(parent).listFiles()?.forEach { dir ->
                val name = dir.name
                if (name == "emulated" || name == "self" || name.startsWith(".")) return@forEach
                add(dir)
            }
        }
        return out.values.toList()
    }

    private fun _scanMediaStore(context: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { c ->
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val path = c.getString(dataIdx)
                    if (path.isNullOrBlank() || !isAudioFileName(path)) continue
                    if (LocalScanExclusions.isDirExcluded(parentOf(path))) continue
                    val f = File(path)
                    if (f.isFile) out[f.absolutePath] = f
                }
            }
        } catch (_: Throwable) {
        }
        return _sorted(out.values)
    }

    private fun _sorted(files: Collection<File>): List<File> =
        files.sortedBy { it.absolutePath.lowercase() }
}

internal fun volumeRootOf(appDir: String): String? {
    if (appDir.length < 2 || !appDir.startsWith('/')) return null
    val i = appDir.indexOf("/Android/")
    if (i <= 0) return null
    return appDir.substring(0, i)
}
