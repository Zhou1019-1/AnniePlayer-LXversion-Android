package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.annieplayer.annie_music.kt.data.sources.update.UpdateApi
import com.annieplayer.annie_music.kt.data.sources.update.UpdateInfo
import com.annieplayer.annie_music.kt.data.sources.update.UpdateOutcome
import com.annieplayer.annie_music.kt.data.sources.update.UpdateStatus
import com.annieplayer.annie_music.kt.data.sources.update.isNewerVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalVersion(val name: String, val code: Int)

object UpdateState {
    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_LAST_CHECK = "update_last_check_at"
    private const val KEY_SKIPPED = "update_skipped_version_code"
    private const val KEY_REPO = "update_repo"
    private const val KEY_AUTO = "update_auto_check"
    private const val KEY_ETAG = "update_etag"
    private const val KEY_CACHE_VERSION = "update_cache_version"
    private const val KEY_CACHE_CODE = "update_cache_code"
    private const val KEY_CACHE_NOTES = "update_cache_notes"
    private const val KEY_CACHE_PAGE = "update_cache_page"
    private const val KEY_CACHE_APK = "update_cache_apk"
    private const val KEY_CACHE_PUBLISHED = "update_cache_published"

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null
    private var debuggable: Boolean = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            debuggable = (context.applicationContext.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    }

    fun defaultRepo(): String =
        if (debuggable) UpdateApi.FORK_REPO else UpdateApi.DEFAULT_REPO

    fun repo(): String =
        prefs?.getString(KEY_REPO, null)?.takeIf { it.isNotBlank() } ?: defaultRepo()

    fun setRepo(repo: String) {
        write { putString(KEY_REPO, repo.trim()) }
    }

    fun autoCheck(): Boolean = prefs?.getBoolean(KEY_AUTO, true) ?: true

    fun setAutoCheck(enabled: Boolean) {
        write { putBoolean(KEY_AUTO, enabled) }
    }

    fun skippedVersionCode(): Int = prefs?.getInt(KEY_SKIPPED, 0) ?: 0

    fun markSkipped(code: Int) {
        write { putInt(KEY_SKIPPED, code) }
    }

    fun lastCheckAt(): Long = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L

    fun shouldAutoCheck(): Boolean = autoCheck()

    fun localVersion(context: Context): LocalVersion =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                val legacyCode = info.versionCode
                legacyCode
            }
            LocalVersion(info.versionName.orEmpty(), code)
        } catch (missing: PackageManager.NameNotFoundException) {
            LocalVersion("", 0)
        }

    suspend fun check(localCode: Int, localName: String, manual: Boolean): UpdateStatus =
        mutex.withLock {
            _status.value = UpdateStatus(checking = true, outcome = UpdateOutcome.NONE)
            val storedEtag = prefs?.getString(KEY_ETAG, null)
            val result = UpdateApi.fetch(
                repo = repo(),
                userAgent = "AnniePlayer-Android/$localName",
                etag = storedEtag,
            )
            val info = when (result) {
                is UpdateApi.Result.Loaded -> {
                    cacheInfo(result.info)
                    write { putString(KEY_ETAG, result.etag) }
                    result.info
                }

                UpdateApi.Result.NotModified -> cachedInfo()
                UpdateApi.Result.Failed -> null
            }
            val failed = result == UpdateApi.Result.Failed
            val remoteCode = info?.versionCode
            val next = when {
                failed -> UpdateStatus(
                    checking = false,
                    outcome = UpdateOutcome.FAILED,
                    checkedAt = System.currentTimeMillis(),
                )

                info == null -> UpdateStatus(
                    checking = false,
                    outcome = UpdateOutcome.LATEST,
                    checkedAt = System.currentTimeMillis(),
                )

                remoteCode == null -> UpdateStatus(
                    checking = false,
                    outcome = UpdateOutcome.UNKNOWN,
                    info = info,
                    checkedAt = System.currentTimeMillis(),
                )

                isNewerVersion(remoteCode, localCode) -> UpdateStatus(
                    checking = false,
                    outcome = UpdateOutcome.NEWER,
                    info = info,
                    checkedAt = System.currentTimeMillis(),
                )

                else -> UpdateStatus(
                    checking = false,
                    outcome = UpdateOutcome.LATEST,
                    checkedAt = System.currentTimeMillis(),
                )
            }
            if (!failed) write { putLong(KEY_LAST_CHECK, next.checkedAt) }
            _status.value = next
            next
        }

    fun reset() {
        _status.value = UpdateStatus()
    }

    private fun cachedInfo(): UpdateInfo? {
        val store = prefs ?: return null
        val version = store.getString(KEY_CACHE_VERSION, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val page = store.getString(KEY_CACHE_PAGE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return UpdateInfo(
            version = version,
            versionCode = store.getInt(KEY_CACHE_CODE, 0).takeIf { it > 0 },
            notes = store.getString(KEY_CACHE_NOTES, null).orEmpty(),
            pageUrl = page,
            apkUrl = store.getString(KEY_CACHE_APK, null)?.takeIf { it.isNotBlank() },
            publishedAt = store.getString(KEY_CACHE_PUBLISHED, null)?.takeIf { it.isNotBlank() },
        )
    }

    private fun cacheInfo(info: UpdateInfo?) {
        if (info == null) {
            write {
                remove(KEY_CACHE_VERSION)
                remove(KEY_CACHE_CODE)
                remove(KEY_CACHE_NOTES)
                remove(KEY_CACHE_PAGE)
                remove(KEY_CACHE_APK)
                remove(KEY_CACHE_PUBLISHED)
            }
            return
        }
        write {
            putString(KEY_CACHE_VERSION, info.version)
            putInt(KEY_CACHE_CODE, info.versionCode ?: 0)
            putString(KEY_CACHE_NOTES, info.notes)
            putString(KEY_CACHE_PAGE, info.pageUrl)
            putString(KEY_CACHE_APK, info.apkUrl)
            putString(KEY_CACHE_PUBLISHED, info.publishedAt)
        }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val store = prefs ?: return
        store.edit().apply(block).apply()
    }
}
