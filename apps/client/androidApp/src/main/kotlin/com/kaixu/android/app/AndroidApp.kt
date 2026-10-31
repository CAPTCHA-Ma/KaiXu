package com.kaixu.android.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaixu.android.presentation.navigation.AndroidNavigation
import com.kaixu.android.presentation.theme.KaiXuTheme

/** 本端根界面：取得宿主依赖、应用主题，并进入本端导航。 */
@Composable
fun AndroidApp() {
    val container = viewModel { AndroidAppContainer() }
    KaiXuTheme {
        Surface(Modifier.fillMaxSize()) {
            AndroidNavigation(graph = container.graph)
        }
    }
}
