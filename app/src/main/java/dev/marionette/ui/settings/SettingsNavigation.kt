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

    @Serializable
    object Wake

    @Serializable
    object Voice

    @Serializable
    object Context

    @Serializable
    object Tools

    @Serializable
    object Permissions

    @Serializable
    object ScreenControl

    @Serializable
    object About
}

fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    composable<SettingsNavigation.Root> {
        SettingsScreen(onBack = nav::navigateUp, onOpen = { nav.navigate(it) })
    }
    composable<SettingsNavigation.Backend> { BackendScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Wake> { WakeScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Voice> { VoiceScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Context> { ContextScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Tools> { ToolsScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Permissions> { PermissionsScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.ScreenControl> { ScreenControlScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.About> { AboutScreen(onBack = nav::navigateUp) }
}
