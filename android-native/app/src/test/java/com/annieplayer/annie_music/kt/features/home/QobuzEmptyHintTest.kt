package com.annieplayer.annie_music.kt.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QobuzEmptyHintTest {

    @Test
    fun missing_token_gets_config_hint() {
        val hint = qobuzEmptyHint(QobuzDiscoverFailure.NoToken)
        assertEquals("未配置 Qobuz Token", hint?.first)
        assertTrue(hint?.second?.contains("Qobuz Token 管理") == true)
    }

    @Test
    fun expired_token_gets_replace_hint() {
        val hint = qobuzEmptyHint(QobuzDiscoverFailure.Unauthorized)
        assertEquals("Qobuz Token 已失效", hint?.first)
        assertTrue(hint?.second?.contains("新的 Token") == true)
    }

    @Test
    fun other_failure_gets_retry_hint() {
        val hint = qobuzEmptyHint(QobuzDiscoverFailure.Other)
        assertEquals("Qobuz 内容加载失败", hint?.first)
        assertEquals("下拉刷新重试", hint?.second)
    }

    @Test
    fun healthy_state_has_no_hint() {
        assertNull(qobuzEmptyHint(QobuzDiscoverFailure.None))
    }

    @Test
    fun http_401_maps_to_unauthorized() {
        assertEquals(
            QobuzDiscoverFailure.Unauthorized,
            qobuzFailureOf(IllegalStateException("HTTP 401")),
        )
    }

    @Test
    fun other_errors_map_to_other() {
        assertEquals(
            QobuzDiscoverFailure.Other,
            qobuzFailureOf(IllegalStateException("HTTP 500")),
        )
        assertEquals(QobuzDiscoverFailure.Other, qobuzFailureOf(null))
    }
}
