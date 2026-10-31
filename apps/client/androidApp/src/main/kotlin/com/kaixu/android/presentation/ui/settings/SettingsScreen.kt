package com.kaixu.android.presentation.ui.settings

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

/** View 仅接收返回事件；导航控制器留在 AndroidNavigation。 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置与说明", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onBack) { Text("返回任务") }
        Text("当前为学习示例", style = MaterialTheme.typography.titleMedium)
        Text("任务仅保存在本次运行的内存中，关闭应用后会丢失。")
        Text("返回任务页后，可以继续编辑刚才未保存的标题。")
        Text("账号、云端同步和本地持久化尚未接入。")
    }
}
