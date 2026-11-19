package com.kaixu.desktop.presentation.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaixu.domain.usecase.scheduling.AgendaEntry
import com.kaixu.domain.usecase.scheduling.AgendaKind
import com.kaixu.domain.validation.scheduling.ShanghaiTime

/** 卡片所处的时间阶段，主要决定装饰色与进行中微光，卡面统一保持灰白毛玻璃质感。 */
private enum class AgendaPhase { PAST, ONGOING, UPCOMING }

private val TimeStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
private val TimeEndStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
private val TitleStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
private val MetaStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)

/**
 * 首页卡片：左侧是安静的时间刻度，右侧标题承担视觉重心，中间的引线把两者连起来，
 * 同时表达阶段——已过为实线，进行中按已用时长描出朱砂进度，未开始为点线。
 * 引线尽头的节点形状区分类型：圆点为固定事件，胶囊为个人时段（一段时间），菱形为任务截止（一个时刻）。
 */
@Composable
internal fun AgendaCard(entry: AgendaEntry, now: Long, today: String, onOpen: (AgendaEntry) -> Unit, modifier: Modifier = Modifier) {
    val phase = when {
        entry.isOngoing(now) -> AgendaPhase.ONGOING
        entry.completed || (entry.endsAt ?: entry.time) <= now -> AgendaPhase.PAST
        else -> AgendaPhase.UPCOMING
    }
    val accent = when (phase) {
        AgendaPhase.PAST -> HomeAgendaStyle.Past
        AgendaPhase.ONGOING -> HomeAgendaStyle.Ongoing
        AgendaPhase.UPCOMING -> HomeAgendaStyle.Upcoming
    }
    val progress = entry.endsAt?.takeIf { phase == AgendaPhase.ONGOING }
        ?.let { ((now - entry.time).toFloat() / (it - entry.time).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val date = ShanghaiTime.localDateOf(entry.time)
    val dayLabel = when (date) {
        today -> "今天"
        ShanghaiTime.plusDays(today, -1) -> "昨天"
        ShanghaiTime.plusDays(today, 1) -> "明天"
        else -> date.substring(5).replace('-', '/')
    }
    val kindLabel = when (entry.kind) {
        AgendaKind.FIXED_EVENT -> "固定事件"
        AgendaKind.WORK_SESSION -> "个人时段"
        AgendaKind.TASK_DEADLINE -> "任务截止"
    }
    val status = when {
        entry.completed -> "已完成"
        phase == AgendaPhase.ONGOING -> "进行中 · 剩 ${((requireNotNull(entry.endsAt) - now + 59_999) / 60_000)} 分钟"
        entry.kind == AgendaKind.TASK_DEADLINE && entry.time < now -> "已过截止"
        else -> null
    }
    val statusColor = when {
        entry.completed -> HomeAgendaStyle.PastText
        phase == AgendaPhase.ONGOING -> HomeAgendaStyle.Ongoing
        else -> HomeAgendaStyle.Ongoing.copy(alpha = .78f)
    }
    val startLabel = if (entry.dateOnly) "全天" else ShanghaiTime.format(entry.time).substring(11)
    val endLabel = when {
        entry.endsAt != null -> "至 " + ShanghaiTime.format(entry.endsAt!!).substring(11)
        else -> "截止"
    }

    val cardShape = RoundedCornerShape(HomeAgendaStyle.CardRadius)
    val outerBorderBrush = if (phase == AgendaPhase.ONGOING) {
        Brush.verticalGradient(
            listOf(
                lerp(HomeAgendaStyle.CardBorderTop, HomeAgendaStyle.Ongoing, .24f),
                lerp(HomeAgendaStyle.CardBorderBottom, HomeAgendaStyle.Ongoing, .16f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                HomeAgendaStyle.CardBorderTop,
                HomeAgendaStyle.CardBorderBottom,
            ),
        )
    }

    Surface(
        modifier = modifier.testTag("home-agenda-${entry.key}").clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
            onClick = { onOpen(entry) },
        ),
        shape = cardShape,
        color = HomeAgendaStyle.CardSurface,
        border = BorderStroke(1.dp, outerBorderBrush),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawFrostedGlassCard() },
        ) {
            AgendaCardLayout(
                modifier = Modifier.fillMaxSize().padding(start = 30.dp, end = 32.dp),
                start = {
                    Text(startLabel, style = TimeStyle,
                        color = if (phase == AgendaPhase.PAST) HomeAgendaStyle.PastText else accent, maxLines = 1)
                },
                end = { Text(endLabel, style = TimeEndStyle, color = HomeAgendaStyle.Muted, maxLines = 1) },
                leader = { AgendaLeader(phase, entry.kind, accent, progress) },
                title = {
                    Text(entry.title, style = TitleStyle, textAlign = TextAlign.End,
                        color = if (phase == AgendaPhase.PAST) HomeAgendaStyle.TitleFaded else HomeAgendaStyle.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                meta = {
                    val meta = buildAnnotatedString {
                        status?.let { withStyle(SpanStyle(color = statusColor)) { append(it) }; append("   ") }
                        append(listOfNotNull(kindLabel, dayLabel, entry.workspace).joinToString("  ·  "))
                    }
                    Text(meta, style = MetaStyle, color = HomeAgendaStyle.Muted, textAlign = TextAlign.End,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

/**
 * 极浅扁平毛玻璃卡面：
 * 1) 接近纯白背景的扁平浅雾基底（纵向变化克制在极小范围内）；
 * 2) 左上至中段的柔光漫射折光层；
 * 3) 1.1 dp 纯白内壁高光倒角与 1 dp 冷银外细边配合悬浮阴影，在纯白面板上形成轻盈通透的分层。
 */
private fun DrawScope.drawFrostedGlassCard() {
    drawRect(
        brush = Brush.verticalGradient(
            0f to HomeAgendaStyle.CardGlassTop,
            .50f to HomeAgendaStyle.CardSurface,
            1f to HomeAgendaStyle.CardGlassBottom,
        ),
    )
    drawRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = .32f),
                .34f to Color.White.copy(alpha = .08f),
                .72f to Color.White.copy(alpha = 0f),
            ),
            start = Offset.Zero,
            end = Offset(size.width * .56f, size.height * .86f),
        ),
    )
    val bevelInset = 1.5.dp.toPx()
    val innerRadius = (HomeAgendaStyle.CardRadius.toPx() - bevelInset).coerceAtLeast(0f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = .98f),
            .40f to Color.White.copy(alpha = .44f),
            1f to HomeAgendaStyle.CardInnerRefraction,
        ),
        topLeft = Offset(bevelInset, bevelInset),
        size = Size(size.width - bevelInset * 2f, size.height - bevelInset * 2f),
        cornerRadius = CornerRadius(innerRadius, innerRadius),
        style = Stroke(width = 1.1.dp.toPx()),
    )
}

/**
 * 两行网格：第一行「开始时间 —— 标题」共用一条中轴，第二行「结束时间 … 说明」对齐。
 * 时间列宽固定，相邻卡片的引线从同一位置起笔；标题按内容宽度贴右，余下宽度全部交给引线。
 */
@Composable
private fun AgendaCardLayout(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    leader: @Composable () -> Unit,
    title: @Composable () -> Unit,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(start, end, leader, title, meta), modifier = modifier) { measurables, constraints ->
        val (startM, endM, leaderM, titleM, metaM) = measurables
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 20.dp.roundToPx()
        val minLeader = 40.dp.roundToPx()
        val rowGap = 6.dp.roundToPx()
        val leaderHeight = 16.dp.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val startP = startM.first().measure(loose)
        val endP = endM.first().measure(loose)
        val timeWidth = maxOf(startP.width, endP.width, 54.dp.roundToPx())
        val rightMax = (width - timeWidth - gap * 2 - minLeader).coerceAtLeast(0)
        val titleP = titleM.first().measure(loose.copy(maxWidth = rightMax))
        val metaP = metaM.first().measure(loose.copy(maxWidth = rightMax))
        val rightWidth = maxOf(titleP.width, metaP.width)
        val leaderWidth = (width - timeWidth - rightWidth - gap * 2).coerceAtLeast(0)
        val leaderP = leaderM.first().measure(Constraints.fixed(leaderWidth, leaderHeight))
        val top = (height - titleP.height - rowGap - metaP.height) / 2
        val axis = top + titleP.height / 2
        val secondRow = top + titleP.height + rowGap + metaP.height / 2
        layout(width, height) {
            startP.place(0, axis - startP.height / 2)
            endP.place(0, secondRow - endP.height / 2)
            leaderP.place(timeWidth + gap, axis - leaderHeight / 2)
            titleP.place(width - titleP.width, top)
            metaP.place(width - metaP.width, top + titleP.height + rowGap)
        }
    }
}

@Composable
private fun AgendaLeader(phase: AgendaPhase, kind: AgendaKind, accent: Color, progress: Float) {
    Canvas(Modifier) {
        val y = (size.height / 2f).toInt() + .5f
        val glyphHalfWidth = when (kind) {
            AgendaKind.FIXED_EVENT -> 4.2.dp.toPx()
            AgendaKind.WORK_SESSION -> 6.8.dp.toPx()
            AgendaKind.TASK_DEADLINE -> 5.2.dp.toPx()
        }
        val nodeX = (size.width - glyphHalfWidth).toInt() + .5f
        val lineEnd = (nodeX - glyphHalfWidth - 8.dp.toPx()).coerceAtLeast(0f)
        val dotted = PathEffect.dashPathEffect(floatArrayOf(.01f, 5.dp.toPx()))
        val dotWidth = 1.8.dp.toPx()
        when (phase) {
            AgendaPhase.PAST -> drawLine(accent, Offset(0f, y), Offset(lineEnd, y), 1.4.dp.toPx(), cap = StrokeCap.Round)
            AgendaPhase.UPCOMING -> drawLine(accent.copy(alpha = .58f), Offset(0f, y), Offset(lineEnd, y), dotWidth,
                cap = StrokeCap.Round, pathEffect = dotted)
            AgendaPhase.ONGOING -> {
                val head = lineEnd * progress
                drawLine(accent.copy(alpha = .45f), Offset(head, y), Offset(lineEnd, y), dotWidth,
                    cap = StrokeCap.Round, pathEffect = dotted)
                drawLine(accent, Offset(0f, y), Offset(head, y), 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(accent.copy(alpha = .18f), 7.dp.toPx(), Offset(head, y))
                drawCircle(accent, 3.2.dp.toPx(), Offset(head, y))
            }
        }
        drawKindGlyph(kind, Offset(nodeX, y), accent, filled = phase != AgendaPhase.UPCOMING)
    }
}

private fun DrawScope.drawKindGlyph(kind: AgendaKind, center: Offset, color: Color, filled: Boolean) {
    val strokeWidth = 1.6.dp.toPx()
    val inset = if (filled) 0f else strokeWidth / 2f
    val style = if (filled) Fill else Stroke(width = strokeWidth, join = StrokeJoin.Round)
    when (kind) {
        AgendaKind.FIXED_EVENT -> drawCircle(color, 4.2.dp.toPx() - inset, center, style = style)
        AgendaKind.WORK_SESSION -> {
            val half = Size(6.8.dp.toPx() - inset, 3.6.dp.toPx() - inset)
            drawRoundRect(color, Offset(center.x - half.width, center.y - half.height),
                Size(half.width * 2, half.height * 2), CornerRadius(half.height), style = style)
        }
        AgendaKind.TASK_DEADLINE -> {
            val r = 5.2.dp.toPx() - inset
            val diamond = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r, center.y)
                lineTo(center.x, center.y + r)
                lineTo(center.x - r, center.y)
                close()
            }
            drawPath(diamond, color, style = style)
        }
    }
}
