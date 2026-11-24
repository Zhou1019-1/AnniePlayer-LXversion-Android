package com.annieplayer.annie_music.kt.core.remote

import android.content.Context
import android.content.SharedPreferences

object LanRemoteSettings {

    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_HOST = "lan-remote-host"
    private const val KEY_TOKEN = "lan-remote-token"
    private const val KEY_RECENT = "lan-remote-recent"
    private const val RECENT_LIMIT = 5

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var host: String
        get() = prefs?.getString(KEY_HOST, "").orEmpty()
        set(value) = write { putString(KEY_HOST, value) }

    var token: String
        get() = prefs?.getString(KEY_TOKEN, "").orEmpty()
        set(value) = write { putString(KEY_TOKEN, value) }

    fun recentHosts(): List<String> = prefs
        ?.getString(KEY_RECENT, "")
        .orEmpty()
        .split('\n')
        .filter { it.isNotBlank() }

    fun rememberHost(host: String) {
        val wanted = host.trim()
        if (wanted.isEmpty()) return
        val next = buildList {
            add(wanted)
            recentHosts().forEach { if (it != wanted && size < RECENT_LIMIT) add(it) }
        }
        write { putString(KEY_RECENT, next.joinToString("\n")) }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val p = prefs ?: return
        runCatching { p.edit().apply(block).apply() }
    }
}
