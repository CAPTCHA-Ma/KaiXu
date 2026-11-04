package com.kaixu.android.presentation.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaixu.presentation.state.tasks.TaskEditorUiState

/** View：只消费状态与回调；没有 Repository、HTTP 或数据库依赖。 */
@Composable
fun TaskEditorScreen(
    state: TaskEditorUiState,
    onTitleChanged: (String) -> Unit,
    onSaveClicked: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.draftTitle,
            onValueChange = onTitleChanged,
            label = { Text("任务标题") },
            placeholder = { Text("例如：整理软件工程访谈问题") },
            enabled = !state.isSaving && !state.isLoading,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = state.errorMessage != null,
        )
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSaveClicked, enabled = !state.isSaving && !state.isLoading) {
                Text(if (state.isSaving) "保存中…" else "新增任务")
            }
            if (state.isSaving) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("个人任务（${state.tasks.size}）", style = MaterialTheme.typography.titleMedium)
        when {
            state.isLoading -> Text("正在读取任务…")
            state.tasks.isEmpty() -> Text("还没有任务，输入标题即可创建。")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tasks, key = { it.id }) { task ->
                ListItem(headlineContent = { Text(task.title) },
                    supportingContent = { Text("仅自己可见") })
            }
        }
    }
}

