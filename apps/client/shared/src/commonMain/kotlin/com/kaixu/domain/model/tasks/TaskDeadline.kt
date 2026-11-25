package com.kaixu.domain.model.tasks

import com.kaixu.domain.validation.scheduling.ShanghaiTime
import com.kaixu.domain.validation.tasks.TaskInput

/**
 * 任务截止要求（密封类型）：无截止、仅日期与具体时刻三者互斥。
 * 仅日期截止按上海时区自然日理解，不伪造 23:59 时刻；逾期在次日上海零点后判定。
 */
sealed interface TaskDeadline {
    data object None : TaskDeadline

    data class DateOnly(val date: String) : TaskDeadline {
        init {
            require(TaskInput.isValidDate(date)) { "截止日期必须是有效的 YYYY-MM-DD" }
        }
    }

    data class ExactTime(val epochMillis: Long) : TaskDeadline
}

val Task.deadline: TaskDeadline
    get() {
        require(deadlineDate == null || deadlineAt == null) { "日期截止与精确截止不能同时填写" }
        return when {
            deadlineDate != null -> TaskDeadline.DateOnly(deadlineDate)
            deadlineAt != null -> TaskDeadline.ExactTime(deadlineAt)
            else -> TaskDeadline.None
        }
    }

fun Task.withDeadline(deadline: TaskDeadline): Task = when (deadline) {
    TaskDeadline.None -> copy(deadlineDate = null, deadlineAt = null)
    is TaskDeadline.DateOnly -> copy(deadlineDate = deadline.date, deadlineAt = null)
    is TaskDeadline.ExactTime -> copy(deadlineDate = null, deadlineAt = deadline.epochMillis)
}

fun TaskDeadline.localDateOrNull(): String? = when (this) {
    TaskDeadline.None -> null
    is TaskDeadline.DateOnly -> date
    is TaskDeadline.ExactTime -> ShanghaiTime.localDateOf(epochMillis)
}

fun TaskDeadline.isPastAt(nowMillis: Long): Boolean = when (this) {
    TaskDeadline.None -> false
    is TaskDeadline.DateOnly -> ShanghaiTime.compareDates(date, ShanghaiTime.localDateOf(nowMillis)) < 0
    is TaskDeadline.ExactTime -> epochMillis < nowMillis
}

fun Task.isOverdueAt(nowMillis: Long): Boolean =
    status != TaskStatus.COMPLETED && deadline.isPastAt(nowMillis)

fun TaskDeadline.sortKeyMillis(): Long = when (this) {
    TaskDeadline.None -> Long.MAX_VALUE
    is TaskDeadline.DateOnly -> ShanghaiTime.atLocalDate(ShanghaiTime.plusDays(date, 1), 0) - 1
    is TaskDeadline.ExactTime -> epochMillis
}
