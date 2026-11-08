package com.kaixu.desktop.presentation.ui.resources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.domain.model.resources.Resource
import com.kaixu.domain.model.resources.ResourceKind
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CancellationException

private data class ResourceDetailsState(
    val loading: Boolean = true, val resource: Resource? = null, val workspace: Workspace? = null, val error: String? = null,
)

@Composable
fun ResourceRecordRoute(id: String, sources: LocalDataSources, onBack: () -> Unit) {
    var refresh by remember(id) { mutableIntStateOf(0) }
    var editing by remember(id) { mutableStateOf(false) }
    var openError by remember(id) { mutableStateOf<String?>(null) }
    val state by produceState(ResourceDetailsState(), id, refresh) {
        value = value.copy(loading = true, error = null)
        try {
            val resource = sources.resources.findResourceById(id)
            val workspace = resource?.let { sources.workspaces.findWorkspaceById(it.workspaceId) }
            value = ResourceDetailsState(loading = false, resource = resource, workspace = workspace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = ResourceDetailsState(loading = false, error = "读取资料详情失败，请重试。")
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回资料列表") }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.error != null -> {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(onClick = { refresh++ }) { Text("重试资料详情") }
            }
            !state.loading && (state.resource == null || state.workspace == null) -> Text("资料已不可用，请返回列表刷新。")
            state.resource != null && state.workspace != null -> {
                val resource = requireNotNull(state.resource)
                val workspace = requireNotNull(state.workspace)
                Text(resource.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("resource-record-title"))
                Text("${resourceKindLabel(resource.kind)} · ${workspace.name} · 已保存到本地")
                if (workspace.status == WorkspaceStatus.ARCHIVED) Text("工作区已归档，资料只读。")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (workspace.status == WorkspaceStatus.ACTIVE) {
                        Button(onClick = { editing = true }, enabled = !state.loading, modifier = Modifier.testTag("resource-edit")) { Text("编辑资料") }
                    }
                    TextButton(onClick = { refresh++ }, enabled = !state.loading) { Text("刷新资料详情") }
                }
                resource.description?.let { SelectionContainer { Text(it) } }
                if (resource.kind == ResourceKind.TEXT) {
                    SelectionContainer { Text(resource.textContent.orEmpty(), modifier = Modifier.testTag("resource-record-text")) }
                } else {
                    SelectionContainer { Text(resource.url.orEmpty(), modifier = Modifier.testTag("resource-record-url")) }
                    Text("链接由系统浏览器打开，目标网站是否可用取决于网络。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        openError = try {
                            val url = requireNotNull(resource.url)
                            require(isDesktopResourceUrl(url))
                            Desktop.getDesktop().browse(URI(url))
                            null
                        } catch (_: Exception) { "无法打开链接，请检查浏览器和链接地址。" }
                    }) { Text("在浏览器打开链接") }
                    openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (editing && state.resource != null && state.workspace != null) {
        ResourceEditorDialog(requireNotNull(state.workspace), sources, requireNotNull(state.resource),
            onCancel = { editing = false }) {
            editing = false
            openError = null
            refresh++
        }
    }
}
