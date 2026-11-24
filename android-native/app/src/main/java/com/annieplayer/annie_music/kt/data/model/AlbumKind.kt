package com.annieplayer.annie_music.kt.data.model

const val ALBUM_KIND_SINGLE = "单曲"
const val ALBUM_KIND_EP = "EP"
const val ALBUM_KIND_ALBUM = "专辑"

fun albumKindByTrackCount(trackCount: Int): String? = when {
    trackCount <= 0 -> null
    trackCount == 1 -> ALBUM_KIND_SINGLE
    trackCount <= 6 -> ALBUM_KIND_EP
    else -> ALBUM_KIND_ALBUM
}

fun Album.albumKind(): String? =
    kind.ifBlank { albumKindByTrackCount(musiccnt.toIntOrNull() ?: 0) ?: "" }.ifBlank { null }
