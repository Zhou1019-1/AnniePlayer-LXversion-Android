package com.annieplayer.annie_music.kt.data.sources.update

data class UpdateInfo(
    val version: String,
    val versionCode: Int?,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val publishedAt: String?,
)

enum class UpdateOutcome {
    NONE,
    LATEST,
    NEWER,
    UNKNOWN,
    FAILED,
}

data class UpdateStatus(
    val checking: Boolean = false,
    val outcome: UpdateOutcome = UpdateOutcome.NONE,
    val info: UpdateInfo? = null,
    val checkedAt: Long = 0L,
) {
    val hasNewer: Boolean get() = outcome == UpdateOutcome.NEWER && info != null
}
