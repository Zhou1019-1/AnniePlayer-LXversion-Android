package com.annieplayer.annie_music.kt.data.sources.qobuz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qobuz 译文语言选择（纯 JVM，不联网）。
 *
 * 守的是「换语言译文就消失」那条链路的第一环：用户显式选了语言时，
 * **不许**退化成 `langs.firstOrNull()`。实测 Baby（track 7635676）的
 * `translation_langs` 只有 de/es/fr/it，没有中文；旧逻辑会把「选中文」变成「给德语」，
 * 而且返回的 `translateLang` 覆盖掉用户选择、`translateLangs` 又被下游写空，整条语言下拉消失。
 */
class QobuzTranslateLangTest {

    private val langs = listOf("de", "es", "fr", "it")

    @Test
    fun explicit_available_language_is_used() {
        assertEquals("fr", pickQobuzTranslateLang(langs, "fr", autoPickFirst = false))
        assertEquals("fr", pickQobuzTranslateLang(langs, "fr", autoPickFirst = true))
    }

    @Test
    fun explicit_unavailable_language_does_not_fall_back() {
        assertNull(pickQobuzTranslateLang(langs, "zh-Hans", autoPickFirst = false))
        // 关键：autoPickFirst 不能盖过显式选择
        assertNull(pickQobuzTranslateLang(langs, "zh-Hans", autoPickFirst = true))
    }

    @Test
    fun blank_language_counts_as_not_asked() {
        assertEquals("de", pickQobuzTranslateLang(langs, null, autoPickFirst = true))
        assertEquals("de", pickQobuzTranslateLang(langs, "", autoPickFirst = true))
        assertNull(pickQobuzTranslateLang(langs, "", autoPickFirst = false))
    }

    @Test
    fun no_translations_at_all_yields_null() {
        assertNull(pickQobuzTranslateLang(emptyList(), "fr", autoPickFirst = true))
        assertNull(pickQobuzTranslateLang(emptyList(), null, autoPickFirst = true))
    }
}
