package com.kaixu.desktop.presentation.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaixu.presentation.viewmodel.tasks.TaskEditorViewModel

/** 连接页面状态、业务事件和导航回调；不持有导航控制器。 */
@Composable
fun TaskEditorRoute(
    viewModel: TaskEditorViewModel,
    onOpenSettings: () -> Unit,
    onOpenTask: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KaiXu 个人任务", style = MaterialTheme.typography.headlineMedium)
        Text("任务保存在本机，重启后可继续查看")
        TextButton(onClick = onOpenSettings) { Text("打开设置") }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TaskEditorScreen(
                state = state,
                onTitleChanged = viewModel::onTitleChanged,
                onSaveClicked = viewModel::onSaveClicked,
                onOpenTask = onOpenTask,
            )
        }
    }
}
