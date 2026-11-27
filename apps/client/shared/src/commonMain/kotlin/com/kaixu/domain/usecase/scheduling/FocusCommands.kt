package com.kaixu.domain.usecase.scheduling

import com.kaixu.domain.model.scheduling.FocusSegment
import com.kaixu.domain.model.scheduling.FocusSession
import com.kaixu.domain.model.scheduling.FocusSummary
import com.kaixu.domain.repository.DirectTransaction
import com.kaixu.domain.repository.FocusRepository
import com.kaixu.domain.repository.LocalTransaction
import com.kaixu.domain.repository.WorkSessionRepository

/** 兼容入口：内部统一委托至 [StartFocusSessionCommandUseCase]。 */
class StartFocusSessionUseCase(
    plans: WorkSessionRepository,
    focus: FocusRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val delegate = StartFocusSessionCommandUseCase(focus, plans)

    suspend operator fun invoke(
        workSessionId: String,
        startedAt: Long,
        sessionId: String = newSchedulingId(),
    ): FocusSession = delegate(workSessionId, startedAt, sessionId)
}

/** 兼容入口：内部统一委托至 [PauseFocusSessionCommandUseCase]、[ResumeFocusSessionCommandUseCase] 与 [FinishFocusSessionCommandUseCase]。 */
class ControlFocusSessionUseCase(
    plans: WorkSessionRepository,
    focus: FocusRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val pauseCommand = PauseFocusSessionCommandUseCase(focus)
    private val resumeCommand = ResumeFocusSessionCommandUseCase(focus, plans)
    private val finishCommand = FinishFocusSessionCommandUseCase(focus)

    suspend fun pause(sessionId: String, finalSegment: FocusSegment? = null): FocusSession =
        pauseCommand(sessionId, finalSegment)

    suspend fun resume(sessionId: String): FocusSession =
        resumeCommand(sessionId)

    suspend fun finish(
        sessionId: String,
        endedAt: Long,
        feedback: String? = null,
        finalSegment: FocusSegment? = null,
        interrupted: Boolean = false,
    ): FocusSession = finishCommand(sessionId, endedAt, feedback, finalSegment, interrupted)
}

/** 兼容入口：内部统一委托至 [RecordFocusSegmentCommandUseCase]。 */
class RecordFocusSegmentUseCase(
    focus: FocusRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val delegate = RecordFocusSegmentCommandUseCase(focus)

    suspend operator fun invoke(segment: FocusSegment): FocusSegment = delegate(segment)
}

/** 兼容入口：内部统一委托至 [GetFocusSummaryQueryUseCase]。 */
class GetFocusSummaryUseCase(
    focus: FocusRepository,
    @Suppress("UNUSED_PARAMETER") transaction: LocalTransaction = DirectTransaction,
) {
    private val delegate = GetFocusSummaryQueryUseCase(focus)

    suspend operator fun invoke(workSessionId: String): FocusSummary = delegate(workSessionId)
}
