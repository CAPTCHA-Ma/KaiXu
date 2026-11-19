package com.kaixu.desktop.presentation.ui.home

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** 以卡片间距为单位的单一运动状态；新输入只更新目标，不重建动画或清零速度。 */
internal class CardScrollMotion(initialIndex: Int, private val lastIndex: Int) {
    var position = initialIndex.toFloat()
        private set
    var velocity = 0f
        private set
    var targetIndex = initialIndex
        private set
    var isRunning = false
        private set

    private var quietSeconds = 0f
    private var previewPull = 0f
    private var committed = false
    private var arrivalDirection = 0f

    // 只在越过最终落点后轻微展开右边缘，随后随弹簧回到原宽度。
    val arrivalStretch: Float
        get() = if (committed) ((position - targetIndex) * arrivalDirection).coerceIn(0f, .08f) * .18f else 0f

    fun input(index: Int, remainder: Float = 0f) {
        val next = index.coerceIn(0, lastIndex)
        if (next != targetIndex) {
            committed = true
            arrivalDirection = sign(next - position)
        }
        targetIndex = next
        previewPull = remainder.coerceIn(-1f, 1f) * ResistanceDistance
        quietSeconds = 0f
        isRunning = true
    }

    /** 小步积分让掉帧时的阻尼、速度衔接与正常帧率一致。 */
    fun advance(seconds: Float) {
        var remaining = seconds.coerceIn(0f, .064f)
        while (remaining > 0f && isRunning) {
            val step = min(remaining, 1f / 120f)
            advanceStep(step)
            remaining -= step
        }
    }

    private fun advanceStep(dt: Float) {
        quietSeconds += dt
        val target = targetIndex.toFloat()
        // 整卡切换使用欠阻尼弹簧；阈值前的预移仍用临界阻尼，避免小输入触发弹射。
        val destination = target + if (!committed && quietSeconds < InputGraceSeconds) previewPull else 0f
        springTo(destination.coerceIn(0f, lastIndex.toFloat()), dt, if (committed) SettleDamping else 1f)
        // 首尾也允许少量视觉越界，但不会生成越界事项或触发导航。
        val bounded = position.coerceIn(-BoundaryTravel, lastIndex + BoundaryTravel)
        if (bounded != position) {
            position = bounded
            velocity = 0f
        }
        if (quietSeconds >= InputGraceSeconds && abs(position - target) < .001f && abs(velocity) < .01f) {
            position = target
            velocity = 0f
            previewPull = 0f
            committed = false
            isRunning = false
        }
    }

    /** 弹簧解析解避免不同帧率改变回弹幅度；新目标保留当前位置和速度。 */
    private fun springTo(target: Float, dt: Float, damping: Float) {
        val omega = SpringFrequency
        val decay = exp(-damping * omega * dt)
        val difference = position - target
        val next: Float
        val nextVelocity: Float
        if (damping < 1f) {
            val frequency = omega * sqrt(1f - damping * damping)
            val cosine = cos(frequency * dt)
            val sine = sin(frequency * dt)
            val coefficient = (velocity + damping * omega * difference) / frequency
            val displacement = difference * cosine + coefficient * sine
            next = target + decay * displacement
            nextVelocity = decay * (-damping * omega * displacement + frequency * (coefficient * cosine - difference * sine))
        } else {
            val coefficient = velocity + omega * difference
            next = target + (difference + coefficient * dt) * decay
            nextVelocity = (velocity - omega * coefficient * dt) * decay
        }
        position += (next - position).coerceIn(-MaxSpeed * dt, MaxSpeed * dt)
        velocity = nextVelocity.coerceIn(-MaxSpeed, MaxSpeed)
    }

    companion object {
        const val InputGraceSeconds = .16f
        const val ResistanceDistance = .10f
        private const val SpringFrequency = 20f
        private const val SettleDamping = .74f
        private const val MaxSpeed = 22f
        const val BoundaryTravel = .12f
    }
}

internal data class CardScrollInput(val index: Int, val remainder: Float)
