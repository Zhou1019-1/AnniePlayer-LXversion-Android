package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.sources.apple.AppleStorefront
import com.annieplayer.annie_music.kt.data.sources.apple.fetchAppleStorefronts
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apple Music 地区选择（**半屏弹窗**）。
 *
 * 与「AM 单曲搜索」用的是同一份全局 storefront（`appleMusicStorefront`）：在这里选完，
 * 搜索页 / 首页的 AM 结果也跟着变 —— 这是用户明确要的"像 AM 单曲搜索那样"。
 *
 * 面板高度固定 0.5 屏：地区表有 250 条，撑满整屏会把顶栏和搜索框挤出去；
 * 半屏 + 内部滚动 + 顶部搜索框是这条数据量下最顺的形态。
 */
@Composable
fun AppleStorefrontSheet(
    visible: Boolean,
    current: String,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var storefronts by remember { mutableStateOf<List<AppleStorefront>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(visible) {
        if (!visible || storefronts.isNotEmpty()) return@LaunchedEffect
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { fetchAppleStorefronts() } }
            .onSuccess { storefronts = it }
            .onFailure { error = it.message ?: "地区表加载失败" }
        loading = false
    }

    val filtered = remember(storefronts, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            storefronts
        } else {
            storefronts.filter {
                it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true)
            }
        }
    }

    AppSheet(
        visible = visible,
        title = "Apple Music 地区",
        onClose = onClose,
        contentHeightFraction = 0.5f,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBarField(
                value = query,
                onValueChange = { query = it },
                onSubmit = {},
                hint = "搜索国家 / 地区",
                horizontalPadding = 0.dp,
                verticalPadding = AppSpacing.sp4,
                height = 44.dp,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp6))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading && storefronts.isEmpty() -> Hint("加载中…")
                    error != null && storefronts.isEmpty() -> Hint("加载失败：$error")
                    filtered.isEmpty() -> Hint("没有匹配的地区")
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.code }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickableRipple { onSelect(item.code) }
                                    .padding(horizontal = AppSpacing.sp4, vertical = AppSpacing.sp12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.name,
                                    color = AppColors.textPrimary,
                                    fontSize = AppFontSize.size15,
                                    fontWeight = if (item.code == current) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                                Text(
                                    text = item.code.uppercase(),
                                    color = AppColors.textTertiary,
                                    fontSize = AppFontSize.size13,
                                )
                                if (item.code == current) {
                                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                                    Text("✓", color = AppColors.white, fontSize = AppFontSize.size13)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = AppColors.textTertiary, fontSize = AppFontSize.size13)
    }
}
