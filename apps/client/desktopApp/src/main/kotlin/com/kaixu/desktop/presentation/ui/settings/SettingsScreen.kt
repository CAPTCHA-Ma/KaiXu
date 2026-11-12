package com.kaixu.desktop.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** View 仅接收返回事件；导航控制器留在 DesktopNavigation。 */
@Composable
fun SettingsScreen(onBack: () -> Unit, backLabel: String = "返回任务") {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置与说明", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onBack) { Text(backLabel) }
        Text("当前为学习示例", style = MaterialTheme.typography.titleMedium)
        Text("已保存任务存放在本机数据库中，关闭应用后仍会保留；未保存草稿不会持久化。")
        Text("返回上一页后，可以继续之前的操作。")
        Text("账号与云端同步尚未接入。")
    }
}
