package com.kaixu.desktop.presentation.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.domain.validation.tasks.TaskTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TaskRecordEditor(
    task: Task,
    sources: LocalDataSources,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description.orEmpty()) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var titleError by remember(task.id) { mutableStateOf<String?>(null) }
    var saveError by remember(task.id) { mutableStateOf<String?>(null) }
    var saving by remember(task.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.widthIn(min = 440.dp, max = 600.dp),
        title = { Text("编辑任务") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        if (!saving) {
                            title = it
                            titleError = null
                            saveError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("task-edit-title"),
                    label = { Text("标题") },
                    singleLine = true,
                    enabled = !saving,
                    isError = titleError != null,
                    supportingText = { Text(titleError ?: "最多 ${TaskTitle.MAX_LENGTH} 个字符") },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        if (!saving) {
                            description = it
                            saveError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("task-edit-description"),
                    label = { Text("说明（选填）") },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !saving,
                )
                if (task.status == TaskStatus.COMPLETED) {
                    Text("已完成", style = MaterialTheme.typography.labelLarge)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf(TaskStatus.TODO to "待办", TaskStatus.IN_PROGRESS to "进行中")) {
                            FilterChip(
                                selected = status == value,
                                onClick = {
                                    if (!saving) {
                                        status = value
                                        saveError = null
                                    }
                                },
                                label = { Text(label) },
                                enabled = !saving,
                                modifier = Modifier.testTag("task-edit-status-${value.name}"),
                            )
                        }
                    }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                modifier = Modifier.testTag("task-edit-save"),
                onClick = {
                    titleError = TaskTitle.errorOrNull(title)
                    if (titleError == null && !saving) {
                        val titleToSave = title
                        val descriptionToSave = description
                        val statusToSave = status
                        saving = true
                        saveError = null
                        scope.launch {
                            var committed = false
                            try {
                                saveLocalTaskRecordEdit(task, titleToSave, descriptionToSave, statusToSave, sources)
                                committed = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: IllegalArgumentException) {
                                saveError = error.message ?: "保存失败，输入已保留。请重试。"
                            } catch (error: IllegalStateException) {
                                saveError = error.message ?: "保存失败，输入已保留。请重试。"
                            } catch (_: Exception) {
                                saveError = "保存失败，输入已保留。请重试。"
                            } finally {
                                saving = false
                            }
                            if (committed) onSaved()
                        }
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存修改") }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!saving) onDismiss() },
                enabled = !saving,
                modifier = Modifier.testTag("task-edit-cancel"),
            ) { Text("取消") }
        },
    )
}

/** 保存前重读原记录，只合并本次修改的字段；完成命令由 UC11 处理。 */
internal suspend fun saveLocalTaskRecordEdit(
    original: Task,
    draftTitle: String,
    draftDescription: String,
    draftStatus: TaskStatus,
    sources: LocalDataSources,
) {
    val titleError = TaskTitle.errorOrNull(draftTitle)
    require(titleError == null) { titleError.orEmpty() }
    sources.writeTransaction {
        val latest = checkNotNull(sources.tasks.findTaskById(original.id)) { "任务已不可用，请关闭后刷新列表。" }
        require(latest.createdBy == LOCAL_ACTOR_ID) { "当前只能编辑本人创建的本地任务。" }
        require(latest.workspaceId == original.workspaceId && latest.createdBy == original.createdBy) {
            "任务归属已变化，请关闭后重新打开。"
        }
        latest.workspaceId?.let { workspaceId ->
            val workspace = sources.workspaces.findWorkspaceById(workspaceId)
            require(workspace != null && workspace.ownerUserId == LOCAL_ACTOR_ID && workspace.status == WorkspaceStatus.ACTIVE) {
                "所属工作区已归档或不可用，请关闭后刷新列表。"
            }
        }
        val title = draftTitle.trim()
        val titleChanged = title != original.title
        val descriptionChanged = draftDescription != original.description.orEmpty()
        val description = draftDescription.takeUnless { it.isBlank() }
        val statusChanged = draftStatus != original.status
        require(!titleChanged || latest.title == original.title || latest.title == title) {
            "任务标题已被修改，请关闭后重新打开。"
        }
        require(!descriptionChanged || latest.description == original.description || latest.description == description) {
            "任务说明已被修改，请关闭后重新打开。"
        }
        require(!statusChanged || original.status != TaskStatus.COMPLETED && draftStatus != TaskStatus.COMPLETED) {
            "完成状态请通过任务完成流程处理。"
        }
        require(!statusChanged || latest.status == original.status || latest.status == draftStatus) {
            "任务状态已变化，请关闭后重新打开。"
        }
        val edited = latest.copy(
            title = if (titleChanged) title else latest.title,
            description = if (descriptionChanged) description else latest.description,
            status = if (statusChanged) draftStatus else latest.status,
        )
        check(sources.tasks.updateTask(edited)) { "任务已不可用，请关闭后刷新列表。" }
    }
}
