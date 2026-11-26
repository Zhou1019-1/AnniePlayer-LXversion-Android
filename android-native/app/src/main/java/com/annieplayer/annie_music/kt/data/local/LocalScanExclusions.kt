package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LocalScanExclusions {

    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_DIRS = "local-scan-excluded-dirs"

    private var prefs: SharedPreferences? = null

    private val _excluded = MutableStateFlow<Set<String>>(emptySet())

    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _excluded.value = normalize(p.getStringSet(KEY_DIRS, emptySet()).orEmpty())
    }

    fun isDirExcluded(path: String): Boolean = isPathExcluded(trimSlash(path), _excluded.value)

    fun hasExcludedAncestor(path: String): Boolean {
        val p = trimSlash(path)
        return _excluded.value.any { isPathUnder(p, it) }
    }

    fun addDir(path: String) {
        val p = trimSlash(path)
        if (p.isEmpty() || p == "/") return
        val current = _excluded.value
        if (p in current || hasExcludedAncestor(p)) return
        val next = current.filterNot { isPathUnder(it, p) }.toSet() + p
        _excluded.value = next
        persist()
    }

    fun removeDir(path: String) {
        val p = trimSlash(path)
        val current = _excluded.value
        val next = current.filterNot { it == p || isPathUnder(it, p) }.toSet()
        if (next == current) return
        _excluded.value = next
        persist()
    }

    fun clear() {
        if (_excluded.value.isEmpty()) return
        _excluded.value = emptySet()
        persist()
    }

    internal fun replaceAll(paths: Set<String>) {
        _excluded.value = normalize(paths)
        persist()
    }

    private fun persist() {
        val set = _excluded.value
        runCatching { prefs?.edit()?.putStringSet(KEY_DIRS, set)?.apply() }
    }

    private fun normalize(raw: Set<String>): Set<String> =
        raw.map { trimSlash(it) }.filter { it.isNotEmpty() && it != "/" }.toSet()

    private fun trimSlash(path: String): String {
        var p = path.trim()
        while (p.length > 1 && p.endsWith('/')) p = p.dropLast(1)
        return p
    }
}
