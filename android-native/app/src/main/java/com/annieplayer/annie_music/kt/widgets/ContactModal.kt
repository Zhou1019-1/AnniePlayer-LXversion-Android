package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLineHeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography

private val ContactModalPreviewCloseTop = AppSpacing.sp44 + AppSpacing.sp6

private const val ContactModalDisclaimer =
    "声明：本软件仅供学习交流使用，完全免费，不涉及任何商业用途。所有资源请在下载后24小时内删除。"

private const val ContactModalJoinNote =
    "请通过安妮播放器扫码入群的用户，在入群问题回答时明确备注入群渠道"

@Composable
fun ContactModal(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var previewRes by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        if (previewRes != null) {
            previewRes = null
        } else {
            onClose()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩（对齐 Dart rgba(0,0,0,0.6)）
        // 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 卡片里的「关闭」按钮）
        // `clickableNoRipple` 必须保留：吃掉触摸、阻止点击穿透到下层页面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.scrim60)
                .clickableNoRipple { },
        )

        // 居中卡片（吞掉卡片内空白点击，避免误触遮罩关闭）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .background(AppColors.modalPanel, RoundedCornerShape(AppRadius.r20))
                .clickableNoRipple {}
                .padding(horizontal = AppSpacing.sp28, vertical = AppSpacing.sp40),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "联系我们", style = AnnieTypography.sheetTitle)
                Spacer(modifier = Modifier.height(AppSpacing.sp28))
                ContactModalGroupCard(
                    imageRes = R.drawable.qq_group_annie,
                    groupName = "安妮播放器专属群",
                    groupNo = "1128065156",
                    groupDesc = "需求反馈 / Bug 上报",
                    onOpenPreview = { previewRes = R.drawable.qq_group_annie },
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                ContactModalGroupCard(
                    imageRes = R.drawable.qq_group_hifi,
                    groupName = "真无损HiFi音乐发烧友群",
                    groupNo = "1023637098",
                    groupDesc = "无敌章鱼哥的 HiFi 交流群",
                    onOpenPreview = { previewRes = R.drawable.qq_group_hifi },
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                Text(
                    text = ContactModalJoinNote,
                    textAlign = TextAlign.Center,
                    color = AppColors.warning,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp24))
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onClose)
                        .background(AppColors.glass, RoundedCornerShape(AppRadius.card))
                        .padding(horizontal = AppSpacing.sp32, vertical = AppSpacing.sp10),
                ) {
                    Text(
                        text = "关闭",
                        color = AppColors.white,
                        fontSize = AppFontSize.size15,
                        fontWeight = AppFontWeight.semiBold,
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                Text(
                    text = ContactModalDisclaimer,
                    textAlign = TextAlign.Center,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )
            }
        }

        // 大图预览（对齐 Dart rgba(0,0,0,0.92)）
        previewRes?.let { res ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.scrim92)
                    .clickableNoRipple { previewRes = null },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.7f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ContactModalPreviewCloseTop, end = AppSpacing.sp20)
                        .clickableNoRipple { previewRes = null }
                        .padding(AppSpacing.sp8),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = AppColors.white,
                        modifier = Modifier.size(AppIconSize.icon28),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactModalGroupCard(
    imageRes: Int,
    groupName: String,
    groupNo: String,
    groupDesc: String,
    onOpenPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.whiteA06, RoundedCornerShape(AppRadius.r10))
            .clickableNoRipple(onOpenPreview)
            .padding(AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            modifier = Modifier
                .width(AppSpacing.sp60)
                .height(AppSpacing.sp60 * 1.3f)
                .clip(RoundedCornerShape(AppRadius.r6)),
            contentScale = ContentScale.Fit,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = groupName,
                color = AppColors.white,
                fontSize = AppFontSize.base,
                fontWeight = AppFontWeight.semiBold,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = "群号 $groupNo · $groupDesc",
                color = AppColors.white54,
                fontSize = AppFontSize.xs,
                lineHeight = AppLineHeight.body16,
            )
        }
    }
}

/**
 * 破图占位图标：圆角方框 + 裂缝 + 太阳点。
 * 形状对齐 Flutter `Icons.broken_image_outlined`（errorBuilder 的占位语义）。
 */
@Composable
private fun ContactModalBrokenImageGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA20,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = stroke),
        )
        val crack = Path().apply {
            moveTo(w * 0.58f, h * 0.08f)
            lineTo(w * 0.44f, h * 0.44f)
            lineTo(w * 0.60f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.92f)
        }
        drawPath(path = crack, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.30f, h * 0.30f))
    }
}
