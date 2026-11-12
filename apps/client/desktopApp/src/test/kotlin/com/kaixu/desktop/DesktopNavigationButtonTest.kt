package com.kaixu.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.kaixu.desktop.presentation.components.DesktopNavigationButton
import com.kaixu.desktop.presentation.components.DesktopTitleBar
import com.kaixu.desktop.presentation.navigation.DesktopDestination
import com.kaixu.desktop.presentation.theme.KaiXuTheme
import com.kaixu.desktop.window.WindowsTitleBarState
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class DesktopNavigationButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun previewAndNavigationUseTheDestinationEvenWhenTheButtonHasADifferentLabel() {
        val current = mutableStateOf<DesktopDestination>(DesktopDestination.Settings)
        val target = mutableStateOf<DesktopDestination>(DesktopDestination.Tasks)
        val preview = mutableStateOf<Pair<String, DesktopDestination>?>(null)
        val onPreview: (String, DesktopDestination?) -> Unit = { source, destination ->
            if (destination != null) preview.value = source to destination
            else if (preview.value?.first == source) preview.value = null
        }
        val host = WindowsTitleBarState()
        compose.setContent {
            KaiXuTheme {
                Column {
                    DesktopTitleBar(
                        host = host,
                        currentDestination = current.value,
                        previewDestination = preview.value?.second,
                        backDestination = null,
                        forwardDestination = null,
                        onNavigate = { current.value = it },
                        onBack = {},
                        onForward = {},
                        onPreview = onPreview,
                    )
                    DesktopNavigationButton(
                        destination = target.value,
                        label = "打开这个页面",
                        tag = "page-link",
                        onNavigate = { current.value = it },
                        onPreview = onPreview,
                        modifier = Modifier.size(120.dp, 36.dp),
                    ) { color -> Text("打开这个页面", color = color) }
                }
            }
        }
        val destinations = listOf(
            DesktopDestination.Tasks, DesktopDestination.Schedule, DesktopDestination.Workspaces,
            DesktopDestination.Settings, DesktopDestination.TaskRecord("task-42"),
            DesktopDestination.EventRecord("event-42"), DesktopDestination.SessionRecord("session-42"),
            DesktopDestination.ResourceRecord("resource-42"),
        )
        val caption = compose.onNodeWithTag("titlebar-caption")
        for (destination in destinations) {
            caption.performMouseInput { moveTo(center) }
            compose.runOnIdle { target.value = destination }
            compose.onNodeWithTag("page-link").assertContentDescriptionEquals("打开这个页面")
                .performMouseInput { moveTo(center) }
            caption.assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "预览：${destination.title}",
            ))
            compose.onNodeWithTag("page-link").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertSame(destination, current.value) }
            caption.assertContentDescriptionEquals(destination.title)
            caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
        }
    }
}
