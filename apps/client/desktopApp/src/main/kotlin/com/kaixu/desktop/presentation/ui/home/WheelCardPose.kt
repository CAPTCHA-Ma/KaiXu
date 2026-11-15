package com.kaixu.desktop.presentation.ui.home

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.sign

/** 单张卡片的整体姿态；透明度属于整张卡片，不使用覆盖卡片的渐变遮罩。 */
internal data class WheelCardPose(
    val scale: Float,
    val alpha: Float,
    val offsetY: Float,
    val elevation: Float,
)

/** 输入和位移均为同一长度单位（界面传像素），适配不同视口与显示缩放。 */
internal fun wheelCardPose(distance: Float, viewportHeight: Float, cardHeight: Float, cardGap: Float): WheelCardPose {
    val depth = abs(distance)
    val whole = floor(depth).toInt()
    val fraction = depth - whole
    fun scaleAt(slot: Int): Float =
        (1f - (sqrt(slot.toFloat() * slot + .09f) - .3f) * .07f).coerceIn(.78f, 1f)
    val fromScale = scaleAt(whole)
    val scaleChange = scaleAt(whole + 1) - fromScale
    val scale = fromScale + scaleChange * smoothStep(fraction)
    // 对缩放后的高度积分：整数槽位之间的边缘间距恒为 cardGap，不随窗口拉伸。
    var integratedScale = 0f
    for (slot in 0 until whole) integratedScale += (scaleAt(slot) + scaleAt(slot + 1)) / 2
    integratedScale += fromScale * fraction + scaleChange * (fraction * fraction * fraction - .5f * fraction * fraction * fraction * fraction)
    val offsetY = (cardHeight * integratedScale + cardGap * depth) * sign(distance)
    val edgeSpace = viewportHeight / 2 - abs(offsetY) - cardHeight * scale / 2
    // 边缘淡出从中央三张之外开始；矮视口缩短淡出距离，不冲淡相邻卡片。
    val neighborEdgeSpace = viewportHeight / 2 - (cardHeight / 2 + cardHeight * scaleAt(1) + cardGap)
    val fadeLength = minOf(cardHeight * .42f, neighborEdgeSpace).coerceAtLeast(1f)
    val edgeFade = smoothStep((edgeSpace / fadeLength).coerceIn(0f, 1f))
    val depthFade = 1f - smoothStep(((depth - 3.2f) / 1.3f).coerceIn(0f, 1f))
    return WheelCardPose(
        scale = scale,
        alpha = if (depth <= 1f) 1f else exp(-.075f * (distance * distance - 1f)) * edgeFade * depthFade,
        offsetY = offsetY,
        elevation = cardHeight * (.015f + .065f * exp(-1.8f * distance * distance)),
    )
}

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
