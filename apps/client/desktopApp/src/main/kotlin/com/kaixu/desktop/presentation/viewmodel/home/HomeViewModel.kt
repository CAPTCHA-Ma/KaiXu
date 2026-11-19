package com.kaixu.desktop.presentation.viewmodel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.data.datasource.WorkspaceDataSource
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import com.kaixu.domain.usecase.scheduling.AgendaUseCase
import com.kaixu.domain.usecase.scheduling.AgendaWindow
import com.kaixu.domain.usecase.scheduling.FixedEventConfirmation
import com.kaixu.domain.usecase.scheduling.FixedEventSaveResult
import com.kaixu.domain.usecase.scheduling.FixedEventSchedule
import com.kaixu.domain.usecase.tasks.TaskUseCases
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeQuickCreateKind { TASK, EVENT }

data class HomeQuickCreateRequest(
    val kind: HomeQuickCreateKind,
    val title: String,
    val taskDeadlineDate: String? = null,
    val taskDeadlineAt: Long? = null,
    val eventStartsAt: Long? = null,
    val eventEndsAt: Long? = null,
    val workspaceId: String? = null,
)

sealed interface HomeQuickCreateOutcome {
    data class Saved(val message: String, val focusKey: String? = null) : HomeQuickCreateOutcome
    data class NeedsConflictConfirmation(val confirmation: FixedEventConfirmation) : HomeQuickCreateOutcome
    data class Failed(val message: String) : HomeQuickCreateOutcome
}

data class HomeUiState(
    val now: Long = System.currentTimeMillis(),
    val agenda: AgendaSnapshot? = null,
    val activeWorkspaces: List<Workspace> = emptyList(),
    /** 预留：后续由个人设置导入学期/课程周后填入，例如“第 10 教学周”。 */
    val teachingWeek: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val agenda: AgendaUseCase,
    private val ownerUserId: String,
    private val workspaces: WorkspaceDataSource? = null,
    private val taskUseCases: TaskUseCases? = null,
    private val fixedEventSchedule: FixedEventSchedule? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(now = clock()))
    val uiState = mutableState.asStateFlow()
    private var load: Job? = null

    fun refresh() {
        load?.cancel()
        val now = clock()
        val window = AgendaWindow.around(ShanghaiTime.localDateOf(now))
        mutableState.update { it.copy(now = now, loading = true, error = null) }
        load = viewModelScope.launch {
            try {
                val snapshot = agenda(ownerUserId, window)
                val activeSpaces = workspaces?.listWorkspaces(WorkspaceStatus.ACTIVE)
                    ?.filter { it.ownerUserId == ownerUserId }
                    ?.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
                    .orEmpty()
                mutableState.update { it.copy(agenda = snapshot, activeWorkspaces = activeSpaces, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "日程读取失败，请重试。") }
            }
        }
    }

    fun tick() {
        val now = clock()
        if (mutableState.value.agenda?.window?.today != ShanghaiTime.localDateOf(now)) refresh()
        else mutableState.update { it.copy(now = now) }
    }

    suspend fun createQuickItem(
        request: HomeQuickCreateRequest,
        confirmed: FixedEventConfirmation? = null,
    ): HomeQuickCreateOutcome {
        val trimmedTitle = request.title.trim()
        if (trimmedTitle.isEmpty()) return HomeQuickCreateOutcome.Failed("请先输入名称。")
        return try {
            when (request.kind) {
                HomeQuickCreateKind.TASK -> {
                    val useCases = taskUseCases
                        ?: return HomeQuickCreateOutcome.Failed("当前环境未接入本地任务存储。")
                    if (request.workspaceId != null) {
                        val validSpace = workspaces?.findWorkspaceById(request.workspaceId)
                        require(validSpace != null && validSpace.ownerUserId == ownerUserId && validSpace.status == WorkspaceStatus.ACTIVE) {
                            "所选工作区不存在或已归档。"
                        }
                    }
                    val saved = useCases.create(
                        Task(
                            title = trimmedTitle,
                            workspaceId = request.workspaceId,
                            createdBy = ownerUserId,
                            deadlineDate = request.taskDeadlineDate,
                            deadlineAt = request.taskDeadlineAt,
                        ),
                    )
                    refreshAndAwait()
                    HomeQuickCreateOutcome.Saved("任务已保存到本地", focusKey = "task:${saved.id}")
                }
                HomeQuickCreateKind.EVENT -> {
                    val schedule = fixedEventSchedule
                        ?: return HomeQuickCreateOutcome.Failed("当前环境未接入本地日程存储。")
                    val (startsAt, endsAt) = resolveEventRange(request.eventStartsAt, request.eventEndsAt, clock())
                    val result = schedule.create(
                        FixedEventDraft(
                            title = trimmedTitle,
                            startsAt = startsAt,
                            endsAt = endsAt,
                            workspaceId = request.workspaceId,
                        ),
                        confirmed = confirmed,
                    )
                    when (result) {
                        is FixedEventSaveResult.Saved -> {
                            refreshAndAwait()
                            val date = ShanghaiTime.localDateOf(result.event.startsAt)
                            HomeQuickCreateOutcome.Saved("日程已保存到本地", focusKey = "event:${result.event.id}@$date")
                        }
                        is FixedEventSaveResult.NeedsConfirmation ->
                            HomeQuickCreateOutcome.NeedsConflictConfirmation(result.confirmation)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            HomeQuickCreateOutcome.Failed(invalid.message ?: "输入有误，请检查后重试。")
        } catch (_: Exception) {
            HomeQuickCreateOutcome.Failed("保存失败，输入已保留，请重试。")
        }
    }

    private suspend fun refreshAndAwait() {
        val now = clock()
        val window = AgendaWindow.around(ShanghaiTime.localDateOf(now))
        val snapshot = agenda(ownerUserId, window)
        val activeSpaces = workspaces?.listWorkspaces(WorkspaceStatus.ACTIVE)
            ?.filter { it.ownerUserId == ownerUserId }
            ?.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
            .orEmpty()
        mutableState.update { it.copy(now = now, agenda = snapshot, activeWorkspaces = activeSpaces, loading = false, error = null) }
    }

    companion object {
        private const val HourMillis = 60L * 60L * 1000L

        /** 日程时间留空时默认取上海时区下一个整点起 1 小时，确保满足开始早于结束规则。 */
        fun defaultEventRange(now: Long): Pair<Long, Long> {
            val today = ShanghaiTime.localDateOf(now)
            val elapsedHours = ShanghaiTime.millisOfDay(now) / HourMillis
            val nextHour = elapsedHours + 1
            val start = if (nextHour < 24) {
                ShanghaiTime.atLocalDate(today, nextHour * HourMillis)
            } else {
                ShanghaiTime.atLocalDate(ShanghaiTime.plusDays(today, 1), 0L)
            }
            return start to (start + HourMillis)
        }

        private fun resolveEventRange(startsAt: Long?, endsAt: Long?, now: Long): Pair<Long, Long> {
            if (startsAt == null && endsAt == null) return defaultEventRange(now)
            require(startsAt != null && endsAt != null) { "请完整设置日程的开始与结束时间。" }
            require(endsAt > startsAt) { "结束时间必须晚于开始时间。" }
            return startsAt to endsAt
        }
    }
}
