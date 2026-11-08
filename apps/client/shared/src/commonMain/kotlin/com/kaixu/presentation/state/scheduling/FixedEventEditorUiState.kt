package com.kaixu.presentation.state.scheduling

import com.kaixu.domain.model.scheduling.FixedEvent

/** 固定事件编辑页的只读状态；草稿与持久化字段分离。 */
data class FixedEventEditorUiState(
    val events: List<FixedEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    /** 非空表示正在编辑该事件；null 表示新建。 */
    val editingId: String? = null,
    val draftTitle: String = "",
    /** 输入格式固定为 "yyyy-MM-dd HH:mm"，由 ViewModel 转成 UTC 毫秒。 */
    val draftStart: String = "",
    val draftEnd: String = "",
    val draftDescription: String = "",
    /** 非空表示删除确认框打开。 */
    val pendingDeleteId: String? = null,
    val errorMessage: String? = null,
    val savedMessage: String? = null,
) {
    val isEditing: Boolean get() = editingId != null
}
