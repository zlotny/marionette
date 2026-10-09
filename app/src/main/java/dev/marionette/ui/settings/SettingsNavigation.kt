package dev.marionette.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

object SettingsNavigation {
    @Serializable
    object Root

    @Serializable
    object Backend
}

fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    composable<SettingsNavigation.Root> {
        SettingsScreen(onBack = nav::navigateUp, onOpen = { nav.navigate(it) })
    }
    composable<SettingsNavigation.Backend> { BackendScreen(onBack = nav::navigateUp) }
}
