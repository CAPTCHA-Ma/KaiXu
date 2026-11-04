package com.kaixu.desktop.presentation.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    onSimulateSaveFailure: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KaiXu 个人任务", style = MaterialTheme.typography.headlineMedium)
        Text(if (onSimulateSaveFailure == null) "任务保存在本机，重启后可继续查看" else "内存测试示例")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onOpenSettings) { Text("打开设置") }
            onSimulateSaveFailure?.let { simulate ->
                TextButton(onClick = simulate, enabled = !state.isSaving && !state.isLoading) {
                    Text("模拟下一次保存失败")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TaskEditorScreen(
                state = state,
                onTitleChanged = viewModel::onTitleChanged,
                onSaveClicked = viewModel::onSaveClicked,
                onCancelSaveClicked = if (onSimulateSaveFailure != null) viewModel::onCancelSaveClicked else null,
            )
        }
    }
}
