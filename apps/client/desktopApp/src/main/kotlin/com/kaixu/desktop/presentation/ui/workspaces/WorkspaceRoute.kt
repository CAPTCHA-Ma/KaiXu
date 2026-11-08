package com.kaixu.desktop.presentation.ui.workspaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaixu.domain.model.workspaces.Workspace
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import com.kaixu.presentation.state.workspaces.WorkspaceUiState
import com.kaixu.presentation.viewmodel.workspaces.WorkspaceViewModel

private val WorkspaceBlue = Color(0xFF3157C8)
private val WorkspaceBackground = Color(0xFFF5F7FB)

@Composable
fun WorkspaceRoute(
    viewModel: WorkspaceViewModel,
    overviewContent: (@Composable (Workspace) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = WorkspaceBlue, background = WorkspaceBackground,
            surface = Color.White, onSurface = Color(0xFF19243B),
            surfaceVariant = Color(0xFFE8ECF3),
            secondaryContainer = Color(0xFFEAF0FF),
            onSecondaryContainer = WorkspaceBlue,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().background(WorkspaceBackground).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工作区", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Text("独立本地", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.loadError?.let { message ->
                WorkspaceError(message, onRetry = viewModel::refresh)
            }
            state.actionError?.let { message ->
                WorkspaceError(message, onRetry = viewModel::clearActionError, action = "知道了")
            }
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val selected = state.selectedWorkspace
            if (selected != null) {
                WorkspaceDetail(selected, state, viewModel, Modifier.weight(1f), overviewContent)
            } else {
                WorkspaceDirectory(state, viewModel, gridState, Modifier.weight(1f))
            }
        }
        if (state.draft != null) WorkspaceEditor(state, viewModel)
        state.archiveConfirmationId?.let {
            AlertDialog(
                onDismissRequest = viewModel::cancelArchive,
                title = { Text("归档工作区？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("归档后可查看已有内容，并可通过已归档筛选找回和恢复。课程与项目的关联会保留。")
                        state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmArchive, enabled = !state.isSaving) {
                        Text(if (state.isSaving) "正在归档…" else "确认归档")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelArchive, enabled = !state.isSaving) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun WorkspaceError(message: String, onRetry: () -> Unit, action: String = "重试") {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceDirectory(
    state: WorkspaceUiState,
    vm: WorkspaceViewModel,
    gridState: LazyGridState,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((status, label) in listOf(
                null to "全部 (${state.allWorkspaces.size})",
                WorkspaceStatus.ACTIVE to "进行中 (${state.activeCount})",
                WorkspaceStatus.ARCHIVED to "已归档 (${state.archivedCount})",
            )) {
                FilterChip(selected = state.statusFilter == status,
                    onClick = { vm.onStatusFilterChanged(status) }, label = { Text(label) })
            }
            Spacer(Modifier.width(8.dp))
            for ((kind, label) in listOf(null to "全部类型", WorkspaceKind.COURSE to "课程", WorkspaceKind.PROJECT to "个人项目")) {
                FilterChip(selected = state.kindFilter == kind,
                    onClick = { vm.onKindFilterChanged(kind) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                state.searchQuery, vm::onSearchQueryChanged, Modifier.weight(1f).testTag("workspace-search"),
                label = { Text("搜索工作区名称") }, singleLine = true,
            )
            Button(onClick = { vm.beginCreate() }, enabled = !state.isLoading && !state.isSaving) { Text("新建工作区") }
        }
        if (!state.isLoading && state.loadError == null && state.visibleWorkspaces.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.allWorkspaces.isEmpty()) "建立你的第一个工作区" else "没有符合条件的工作区",
                    style = MaterialTheme.typography.titleMedium)
                if (state.allWorkspaces.isEmpty()) {
                    TextButton(onClick = { vm.beginCreate() }) { Text("创建课程或个人项目") }
                } else {
                    TextButton(onClick = vm::clearFilters) { Text("清除筛选") }
                }
            }
        } else if (state.allWorkspaces.isNotEmpty()) {
            Text("找到 ${state.visibleWorkspaces.size} 个工作区", style = MaterialTheme.typography.bodySmall)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp), state = gridState, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.visibleWorkspaces, key = { it.id }) { space ->
                    WorkspaceCard(space) { vm.openWorkspace(space.id) }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(space: Workspace, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("workspace-card-${space.id}").clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFE6EAF1)),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(
                if (space.status == WorkspaceStatus.ARCHIVED) Color(0xFFBEC5D6) else WorkspaceBlue))
            Column(Modifier.weight(1f).padding(20.dp).heightIn(min = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${kindLabel(space.kind)} · ${statusLabel(space.status)}", color = WorkspaceBlue,
                    style = MaterialTheme.typography.labelMedium)
                Text(space.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                if (space.kind == WorkspaceKind.PROJECT && !space.description.isNullOrBlank()) {
                    Text(space.description.orEmpty(), maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                Text("进入概览 →", color = WorkspaceBlue, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun WorkspaceDetail(
    space: Workspace, state: WorkspaceUiState, vm: WorkspaceViewModel, modifier: Modifier,
    overviewContent: (@Composable (Workspace) -> Unit)?,
) {
    var tab by rememberSaveable(space.id, state.detailNavigationRevision) { mutableStateOf("概览") }
    val tabs = if (space.kind == WorkspaceKind.COURSE) listOf("概览", "关联项目", "设置") else listOf("概览", "设置")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::backToDirectory, enabled = !state.isSaving) { Text("← 返回工作区") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${kindLabel(space.kind)} · ${statusLabel(space.status)}", color = WorkspaceBlue)
            Spacer(Modifier.weight(1f))
            Text("仅自己可见", style = MaterialTheme.typography.bodySmall)
        }
        Text(space.name, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        TabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), containerColor = WorkspaceBackground) {
            tabs.forEach { label -> Tab(selected = tab == label, onClick = { tab = label }, text = { Text(label) }) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().testTag("workspace-detail-scroll").verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                "概览" -> {
                    if (overviewContent == null) Surface(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("工作区信息", style = MaterialTheme.typography.titleMedium)
                            Text("类型  ${kindLabel(space.kind)}")
                            Text("状态  ${statusLabel(space.status)}")
                            if (space.kind == WorkspaceKind.PROJECT) {
                                val course = state.linkedCourse
                                if (course != null) {
                                    TextButton(onClick = { vm.openWorkspace(course.id) }) {
                                        Text("关联课程  ${course.name}" + if (course.status == WorkspaceStatus.ARCHIVED) "（已归档）" else "")
                                    }
                                } else if (space.linkedCourseId != null) Text("关联课程暂不可读取，请重试加载。")
                            }
                        }
                    }
                    overviewContent?.invoke(space)
                    if (overviewContent != null && space.kind == WorkspaceKind.PROJECT) {
                        state.linkedCourse?.let { course ->
                            TextButton(onClick = { vm.openWorkspace(course.id) }) {
                                Text("关联课程  ${course.name}" + if (course.status == WorkspaceStatus.ARCHIVED) "（已归档）" else "")
                            }
                        }
                    }
                    if (space.kind == WorkspaceKind.COURSE) WorkspaceProjects(space, state, vm)
                    space.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                "关联项目" -> WorkspaceProjects(space, state, vm)
                "设置" -> Surface(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("基本信息", style = MaterialTheme.typography.titleMedium)
                        if (space.status == WorkspaceStatus.ACTIVE) {
                            OutlinedButton(onClick = { vm.beginEdit(space.id) }, enabled = !state.isSaving) { Text("编辑工作区") }
                            TextButton(onClick = { vm.requestArchive(space.id) }, enabled = !state.isSaving) { Text("归档工作区") }
                        } else {
                            Text("已归档，当前为只读。恢复后可继续编辑。")
                            Button(onClick = { vm.restoreWorkspace(space.id) }, enabled = !state.isSaving) {
                                Text(if (state.isSaving) "正在恢复…" else "恢复工作区")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceProjects(space: Workspace, state: WorkspaceUiState, vm: WorkspaceViewModel) {
    Surface(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课程关联项目", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (space.status == WorkspaceStatus.ACTIVE) {
                    TextButton(onClick = { vm.beginCreate(space.id) }, enabled = !state.isSaving) { Text("创建关联项目") }
                }
            }
            if (state.relatedProjects.isEmpty()) Text("暂无关联项目", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.relatedProjects.forEach { project ->
                OutlinedButton(onClick = { vm.openWorkspace(project.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(project.name, Modifier.weight(1f))
                    Text(statusLabel(project.status))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEditor(state: WorkspaceUiState, vm: WorkspaceViewModel) {
    val draft = state.draft ?: return
    var courseMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = vm::cancelDraft,
        title = { Text(if (draft.workspaceId == null) "新建工作区" else "编辑工作区") },
        modifier = Modifier.widthIn(min = 440.dp, max = 600.dp),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceKind.entries.forEach { kind ->
                        FilterChip(selected = draft.kind == kind, onClick = { vm.onDraftKindChanged(kind) },
                            modifier = Modifier.testTag("workspace-kind-$kind"),
                            enabled = draft.workspaceId == null && !state.isSaving, label = { Text(kindLabel(kind)) })
                    }
                }
                OutlinedTextField(draft.name, vm::onDraftNameChanged, Modifier.fillMaxWidth().testTag("workspace-name"),
                    label = { Text("名称") }, enabled = !state.isSaving, singleLine = true,
                    isError = draft.nameError != null,
                    supportingText = { Text(draft.nameError ?: "最多 100 字符，同类型名称不能重复") })
                OutlinedTextField(draft.description, vm::onDraftDescriptionChanged,
                    Modifier.fillMaxWidth().testTag("workspace-description"), label = { Text("说明（选填）") },
                    enabled = !state.isSaving, minLines = 2, maxLines = 4)
                if (draft.kind == WorkspaceKind.PROJECT) {
                    Text("关联课程", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { courseMenu = true }, enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth().testTag("workspace-course")) {
                            val course = state.selectedDraftCourse
                            Text(when {
                                course != null -> course.name + if (course.status == WorkspaceStatus.ARCHIVED) "（已归档，保留原关联）" else ""
                                draft.linkedCourseId != null -> "原关联课程暂不可读取"
                                else -> "独立项目"
                            })
                        }
                        DropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                            DropdownMenuItem(text = { Text("独立项目") }, onClick = { vm.onDraftCourseChanged(null); courseMenu = false })
                            state.activeCourses.forEach { course ->
                                DropdownMenuItem(text = { Text(course.name) },
                                    onClick = { vm.onDraftCourseChanged(course.id); courseMenu = false })
                            }
                        }
                    }
                    draft.courseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                draft.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = vm::saveDraft, enabled = !state.isSaving) { Text(if (state.isSaving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = vm::cancelDraft, enabled = !state.isSaving) { Text("取消") } },
    )
}

private fun kindLabel(kind: WorkspaceKind) = if (kind == WorkspaceKind.COURSE) "课程" else "个人项目"
private fun statusLabel(status: WorkspaceStatus) = if (status == WorkspaceStatus.ACTIVE) "进行中" else "已归档"
