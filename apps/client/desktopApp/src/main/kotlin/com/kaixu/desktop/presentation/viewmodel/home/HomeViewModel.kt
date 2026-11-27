package com.kaixu.desktop.presentation.viewmodel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaixu.core.platform.LocalZone
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.model.tasks.TaskDeadline
import com.kaixu.domain.model.tasks.TaskDraft
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import com.kaixu.domain.usecase.scheduling.FixedEventConfirmation
import com.kaixu.domain.usecase.scheduling.FixedEventSaveResult
import com.kaixu.domain.usecase.scheduling.FixedEventUseCases
import com.kaixu.domain.usecase.scheduling.HomeAgendaFeed
import com.kaixu.domain.usecase.scheduling.QueryHomeAgendaUseCase
import com.kaixu.domain.usecase.tasks.TaskUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

private data class HomeReloadRequest(
    val today: String,
    val token: Long,
)

private sealed interface HomeFeedResult {
    data class Ready(val feed: HomeAgendaFeed) : HomeFeedResult
    data object Failed : HomeFeedResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val queryHomeAgenda: QueryHomeAgendaUseCase,
    private val taskUseCases: TaskUseCases,
    private val fixedEventUseCases: FixedEventUseCases,
    private val zone: LocalZone = LocalZone.of("Asia/Shanghai"),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(now = clock()))
    val uiState = mutableState.asStateFlow()

    private val reloadTrigger = MutableStateFlow(
        HomeReloadRequest(today = zone.localDateOf(mutableState.value.now), token = 0L),
    )

    init {
        viewModelScope.launch {
            reloadTrigger
                .flatMapLatest { request ->
                    queryHomeAgenda.observeFeed(flowOf(request.today), zone)
                        .map<HomeAgendaFeed, HomeFeedResult> { HomeFeedResult.Ready(it) }
                        .catch { failure ->
                            if (failure is CancellationException) throw failure
                            emit(HomeFeedResult.Failed)
                        }
                }
                .collect { result ->
                    when (result) {
                        is HomeFeedResult.Ready -> mutableState.update {
                            it.copy(
                                agenda = result.feed.agenda,
                                activeWorkspaces = result.feed.activeWorkspaces,
                                loading = false,
                                error = null,
                            )
                        }
                        HomeFeedResult.Failed -> mutableState.update {
                            it.copy(loading = false, error = "日程读取失败，请重试。")
                        }
                    }
                }
        }
    }

    fun refresh() {
        val now = clock()
        val today = zone.localDateOf(now)
        mutableState.update { it.copy(now = now, loading = true, error = null) }
        reloadTrigger.update { current -> HomeReloadRequest(today = today, token = current.token + 1L) }
    }

    fun tick() {
        val now = clock()
        if (mutableState.value.agenda?.window?.today != zone.localDateOf(now)) refresh()
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
                    val deadline = when {
                        request.taskDeadlineDate != null && request.taskDeadlineAt != null ->
                            throw IllegalArgumentException("日期截止与精确截止不能同时填写")
                        request.taskDeadlineDate != null -> TaskDeadline.DateOnly(request.taskDeadlineDate)
                        request.taskDeadlineAt != null -> TaskDeadline.ExactTime(request.taskDeadlineAt)
                        else -> TaskDeadline.None
                    }
                    val saved = taskUseCases.createFromDraft(
                        TaskDraft(
                            title = trimmedTitle,
                            workspaceId = request.workspaceId,
                            deadline = deadline,
                        ),
                    )
                    refreshAndAwait()
                    HomeQuickCreateOutcome.Saved("任务已保存到本地", focusKey = "task:${saved.id}")
                }
                HomeQuickCreateKind.EVENT -> {
                    val (startsAt, endsAt) = resolveEventRange(request.eventStartsAt, request.eventEndsAt, clock(), zone)
                    val draft = FixedEventDraft(
                        title = trimmedTitle,
                        startsAt = startsAt,
                        endsAt = endsAt,
                        workspaceId = request.workspaceId,
                    )
                    when (val result = fixedEventUseCases.create(draft = draft, confirmed = confirmed, zone = zone)) {
                        is FixedEventSaveResult.Saved -> {
                            refreshAndAwait()
                            val date = zone.localDateOf(result.event.startsAt)
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
        val today = zone.localDateOf(now)
        val feed = queryHomeAgenda.loadFeed(today, zone)
        mutableState.update {
            it.copy(
                now = now,
                agenda = feed.agenda,
                activeWorkspaces = feed.activeWorkspaces,
                loading = false,
                error = null,
            )
        }
    }

    companion object {
        private const val HourMillis = 60L * 60L * 1000L

        /** 日程时间留空时默认取所选时区下一个整点起 1 小时，确保满足开始早于结束规则。 */
        fun defaultEventRange(
            now: Long,
            zone: LocalZone = LocalZone.of("Asia/Shanghai"),
        ): Pair<Long, Long> {
            val today = zone.localDateOf(now)
            val elapsedHours = zone.localTimeOfDayMillis(now) / HourMillis
            val nextHour = elapsedHours + 1L
            val start = if (nextHour < 24L) {
                zone.atLocalDateTimeMillis(today, nextHour * HourMillis)
            } else {
                zone.startOfNextDayMillis(today)
            }
            return start to (start + HourMillis)
        }

        private fun resolveEventRange(
            startsAt: Long?,
            endsAt: Long?,
            now: Long,
            zone: LocalZone,
        ): Pair<Long, Long> {
            if (startsAt == null && endsAt == null) return defaultEventRange(now, zone)
            require(startsAt != null && endsAt != null) { "请完整设置日程的开始与结束时间。" }
            require(endsAt > startsAt) { "结束时间必须晚于开始时间。" }
            return startsAt to endsAt
        }
    }
}
