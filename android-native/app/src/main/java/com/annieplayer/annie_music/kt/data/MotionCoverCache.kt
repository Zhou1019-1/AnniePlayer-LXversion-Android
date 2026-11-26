package com.annieplayer.annie_music.kt.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object MotionCoverCache {
    const val DirName = "motioncover"

    private const val CacheBytes = 64L * 1024 * 1024

    private val lock = Any()

    private var cache: SimpleCache? = null

    fun dir(context: Context): File = File(context.applicationContext.cacheDir, DirName)

    fun size(context: Context): Long {
        val dir = dir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        return CacheDataSource.Factory()
            .setCache(cacheOf(appContext))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun warm(context: Context) {
        cacheOf(context.applicationContext)
    }

    private fun cacheOf(appContext: Context): SimpleCache = synchronized(lock) {
        cache ?: SimpleCache(
            dir(appContext),
            LeastRecentlyUsedCacheEvictor(CacheBytes),
            StandaloneDatabaseProvider(appContext),
        ).also { cache = it }
    }

    fun release() {
        synchronized(lock) {
            runCatching { cache?.release() }
            cache = null
        }
    }
}
