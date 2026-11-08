package com.kaixu.desktop.presentation.ui.resources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.domain.model.resources.Resource
import com.kaixu.domain.model.resources.ResourceKind
import com.kaixu.domain.model.resources.isResourceHttpUrl
import com.kaixu.domain.model.workspaces.Workspace
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun resourceKindLabel(kind: ResourceKind) = if (kind == ResourceKind.LINK) "链接" else "文本"

internal fun isDesktopResourceUrl(value: String): Boolean = isResourceHttpUrl(value) && try {
    val uri = URI(value)
    !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in -1..65535
} catch (_: Exception) { false }

@Composable
internal fun ResourceEditorDialog(
    workspace: Workspace,
    sources: LocalDataSources,
    original: Resource?,
    onCancel: () -> Unit,
    onSaved: (Resource) -> Unit,
) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var description by remember { mutableStateOf(original?.description.orEmpty()) }
    var kind by remember { mutableStateOf(original?.kind ?: ResourceKind.LINK) }
    var url by remember { mutableStateOf(original?.url.orEmpty()) }
    var content by remember { mutableStateOf(original?.textContent.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(if (original == null) "新建资料" else "编辑资料") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("所属工作区：${workspace.name}")
                Text("独立本地 · 仅自己可见", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("资料名称") }, enabled = !saving,
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("resource-name"))
                OutlinedTextField(description, { description = it; error = null }, label = { Text("说明（可选）") },
                    enabled = !saving, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("resource-description"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResourceKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, enabled = !saving, onClick = { kind = option; error = null },
                            label = { Text(resourceKindLabel(option)) }, modifier = Modifier.testTag("resource-kind-${option.name}"))
                    }
                }
                if (kind == ResourceKind.LINK) OutlinedTextField(url, { url = it; error = null }, label = { Text("http(s) 链接") },
                    enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("resource-url"))
                else OutlinedTextField(content, { content = it; error = null }, label = { Text("文本内容") },
                    enabled = !saving, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("resource-text"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("resource-save-error")) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, modifier = Modifier.testTag("resource-save"), onClick = {
                if (!saving) {
                    error = when {
                        name.isBlank() -> "资料名称不能为空。"
                        kind == ResourceKind.LINK && !isDesktopResourceUrl(url.trim()) -> "请输入有效的 http:// 或 https:// 链接。"
                        kind == ResourceKind.TEXT && content.isBlank() -> "文本内容不能为空。"
                        else -> null
                    }
                    if (error == null) {
                        saving = true
                        scope.launch {
                            try {
                                val link = url.trim().takeIf { kind == ResourceKind.LINK }
                                val text = content.takeIf { kind == ResourceKind.TEXT }
                                val note = description.takeIf { it.isNotBlank() }
                                val saved = if (original == null) sources.resources.createResource(workspace.id, name, note, kind, link, text)
                                else sources.resources.updateResource(original.id, name, note, kind, link, text, original.localRevision)
                                onSaved(saved)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: IllegalArgumentException) {
                                error = failure.message ?: "资料字段无效，请检查。"
                            } catch (failure: IllegalStateException) {
                                error = failure.message ?: "资料暂不可编辑，请刷新后重试。"
                            } catch (_: Exception) {
                                error = "保存失败，输入已保留，请重试。"
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            }) { Text(if (saving) "正在保存…" else "保存资料") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("取消") } },
    )
}
