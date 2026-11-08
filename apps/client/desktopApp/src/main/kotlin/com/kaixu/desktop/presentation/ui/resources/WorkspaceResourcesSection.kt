package com.kaixu.desktop.presentation.ui.resources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.domain.model.resources.Resource
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class ResourceListState(
    val records: List<Resource> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val error: String? = null, val query: String = "",
)

private class ResourceListViewModel(private val sources: LocalDataSources) : ViewModel() {
    private val state = MutableStateFlow(ResourceListState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun search(query: String) { state.update { it.copy(query = query) } }

    fun refresh(workspaceId: String) {
        val requested = ++generation
        job?.cancel()
        state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val records = sources.resources.listResourcesByWorkspace(workspaceId)
                if (requested == generation) state.update { it.copy(records = records, loaded = true, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requested == generation) state.update { it.copy(loading = false, error = "读取资料失败，请重试。") }
            }
        }
    }
}

/** 同一导航条目保留上次列表和搜索，详情返回时重读原记录，不在加载中清空滚动内容。 */
@Composable
fun WorkspaceResourcesSection(
    workspace: Workspace,
    sources: LocalDataSources,
    onOpenResource: (String) -> Unit,
    onOpenAll: () -> Unit,
    summary: Boolean,
) {
    val model = viewModel<ResourceListViewModel>(key = "workspace-resources-${workspace.id}") { ResourceListViewModel(sources) }
    val state by model.uiState.collectAsState()
    var creating by remember(workspace.id) { mutableStateOf(false) }
    LaunchedEffect(workspace.id) { model.refresh(workspace.id) }
    val filtered = if (summary) state.records else state.records.filter { it.name.contains(state.query.trim(), ignoreCase = true) }
    val shown = if (summary) filtered.take(3) else filtered
    Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("workspace-resources")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.loaded) "资料 · ${state.records.size}" else "资料", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("resource-count"))
                TextButton(onClick = { model.refresh(workspace.id) }, enabled = !state.loading) { Text("刷新资料") }
                if (summary) TextButton(onClick = onOpenAll) { Text("全部资料") }
                else if (workspace.status == WorkspaceStatus.ACTIVE) {
                    Button(onClick = { creating = true }, modifier = Modifier.testTag("resource-create")) { Text("新建资料") }
                }
            }
            if (!summary) {
                OutlinedTextField(value = state.query, onValueChange = model::search, label = { Text("按资料名称查找") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("resource-search"))
                if (workspace.status == WorkspaceStatus.ARCHIVED) Text("工作区已归档，资料只读。")
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (state.loaded) Text("以下为上次读取的资料。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.refresh(workspace.id) }) { Text("重试资料") }
            }
            if (state.loaded && !state.loading && state.error == null && shown.isEmpty()) {
                Text(if (state.records.isEmpty()) "暂无资料，可添加链接或文本。" else "没有符合名称的资料。")
            }
            shown.forEach { resource ->
                OutlinedButton(onClick = { onOpenResource(resource.id) },
                    modifier = Modifier.fillMaxWidth().testTag("resource-${resource.id}")) {
                    Column(Modifier.weight(1f)) {
                        Text(resource.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(resourceKindLabel(resource.kind), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (creating) ResourceEditorDialog(workspace, sources, original = null, onCancel = { creating = false }) { saved ->
        creating = false
        model.refresh(workspace.id)
        onOpenResource(saved.id)
    }
}
