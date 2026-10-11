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
    object Instructions

    @Serializable
    object Credentials

    @Serializable
    object Wake

    @Serializable
    object WakeSensitivity

    @Serializable
    object StopPhrases

    @Serializable
    object Recognition

    @Serializable
    object Speech

    @Serializable
    object Conversation

    @Serializable
    object HoldPhrases

    @Serializable
    object Context

    @Serializable
    object Tools

    @Serializable
    object Places

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
    composable<SettingsNavigation.Credentials> { CredentialsScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Instructions> { InstructionsScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Wake> { WakeScreen(onBack = nav::navigateUp, onOpenSensitivity = { nav.navigate(SettingsNavigation.WakeSensitivity) }) }
    composable<SettingsNavigation.WakeSensitivity> { WakeSensitivityScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.StopPhrases> { StopPhrasesScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Recognition> { RecognitionScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Speech> { SpeechScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Conversation> { ConversationScreen(onBack = nav::navigateUp, onOpenHoldPhrases = { nav.navigate(SettingsNavigation.HoldPhrases) }) }
    composable<SettingsNavigation.HoldPhrases> { HoldPhrasesScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Context> { ContextScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Tools> { ToolsScreen(onBack = nav::navigateUp, onOpenPlaces = { nav.navigate(SettingsNavigation.Places) }) }
    composable<SettingsNavigation.Places> { PlacesScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.Permissions> { PermissionsScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.ScreenControl> { ScreenControlScreen(onBack = nav::navigateUp) }
    composable<SettingsNavigation.About> { AboutScreen(onBack = nav::navigateUp) }
}
