package com.annieplayer.annie_music.kt.data.sources.home

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class HomeCacheForce(val on: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HomeCacheForce>
}

internal suspend fun homeCacheForceActive(): Boolean =
    coroutineContext[HomeCacheForce]?.on == true

internal suspend fun <T> withHomeCacheForce(force: Boolean, block: suspend () -> T): T =
    if (!force) block() else kotlinx.coroutines.withContext(HomeCacheForce(true)) { block() }
