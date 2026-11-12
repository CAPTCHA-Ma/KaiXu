package com.kaixu.data.datasource.local.dao

import androidx.room.*
import com.kaixu.data.datasource.local.entity.*

@Dao
abstract class FocusSegmentDao {
    @Query("SELECT * FROM focus_segments WHERE session_id = :sessionId ORDER BY starts_at, id")
    abstract suspend fun listBySession(sessionId: String): List<FocusSegmentEntity>

    @Query("SELECT * FROM focus_segments WHERE session_id = :sessionId AND client_segment_id = :clientId")
    protected abstract suspend fun findSubmission(sessionId: String, clientId: String): FocusSegmentEntity?

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    protected abstract suspend fun findSession(id: String): FocusSessionEntity?

    @Query("""SELECT COUNT(*) FROM focus_segments g JOIN focus_sessions s ON s.id = g.session_id
        WHERE s.owner_user_id = :ownerId AND g.starts_at < :end AND g.ends_at > :start""")
    protected abstract suspend fun overlappingCount(ownerId: String, start: Long, end: Long): Int

    @Insert
    protected abstract suspend fun insertRow(value: FocusSegmentEntity)

    /** 稳定提交键重试返回原记录；不同内容复用键或有效区间重叠均拒绝，不重复累计。 */
    @Transaction
    open suspend fun append(value: FocusSegmentEntity): FocusSegmentEntity {
        val existing = findSubmission(value.sessionId, value.clientSegmentId)
        if (existing != null) {
            require(existing == value.copy(id = existing.id)) { "同一计时片段提交标识不能对应不同内容" }
            return existing
        }
        val session = requireNotNull(findSession(value.sessionId)) { "计时会话不存在" }
        require(value.startsAt >= session.startedAt && (session.endedAt == null || value.endsAt <= session.endedAt)) {
            "有效片段必须位于实际计时会话内"
        }
        require(overlappingCount(session.ownerUserId, value.startsAt, value.endsAt) == 0) { "本人已确认的有效计时不能重叠" }
        // 不比较 work_session.starts_at / ends_at：计划窗口外的实际执行同样计入。
        insertRow(value)
        return value
    }

    @Query("""SELECT g.* FROM focus_segments g JOIN focus_sessions s ON s.id = g.session_id
        WHERE s.owner_user_id = :ownerId AND s.work_session_id = :workSessionId ORDER BY g.starts_at, g.id""")
    abstract suspend fun listByWorkSession(ownerId: String, workSessionId: String): List<FocusSegmentEntity>
}
