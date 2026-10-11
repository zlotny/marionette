package dev.marionette.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.BackEventCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.marionette.MarionetteApp
import dev.marionette.ui.activity.ActivityScreen
import dev.marionette.ui.home.HomeScreen
import dev.marionette.ui.onboarding.OnboardingScreen
import dev.marionette.ui.settings.SettingsNavigation
import dev.marionette.ui.settings.settingsGraph
import dev.marionette.ui.theme.MarionetteTheme
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceService
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object OnboardingRoute

@Serializable
object ActivityRoute

class MainActivity : ComponentActivity() {
    private val graph get() = MarionetteApp.graph

    private val talkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceService.send(this, VoiceService.ACTION_TALK, TriggerSource.BUTTON)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val startOnboarding = !graph.settings.current.onboarded
        enableEdgeToEdge()
        setContent {
            MarionetteTheme {
                AppNavHost(rememberNavController(), startOnboarding)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startVoiceIfReady()
    }

    fun startVoiceIfReady() {
        if (graph.settings.current.onboarded && hasMic() && !VoiceService.running) {
            VoiceService.send(this, VoiceService.ACTION_START)
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun talk() {
        if (hasMic()) {
            VoiceService.send(this, VoiceService.ACTION_TALK, TriggerSource.BUTTON)
        } else {
            talkPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Composable
    private fun AppNavHost(nav: NavHostController, startOnboarding: Boolean) {
        NavHost(
            navController = nav,
            startDestination = if (startOnboarding) OnboardingRoute else HomeRoute,
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
            predictivePopEnterTransition = { fadeIn() },
            predictivePopExitTransition = { edge -> slideOutHorizontally { if (edge == BackEventCompat.EDGE_RIGHT) -it else it } },
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(onDone = {
                    startVoiceIfReady()
                    nav.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
                })
            }
            composable<HomeRoute> {
                HomeScreen(
                    onOpenSettings = { nav.navigate(SettingsNavigation.Root) },
                    onOpenActivity = { nav.navigate(ActivityRoute) },
                    onTalk = ::talk,
                    onCancelVoice = { VoiceService.send(this@MainActivity, VoiceService.ACTION_CANCEL) },
                    onOpenPermissions = { nav.navigate(SettingsNavigation.Permissions) },
                    onOpenBackend = { nav.navigate(SettingsNavigation.Backend) },
                    onOpenWake = { nav.navigate(SettingsNavigation.Wake) },
                )
            }
            composable<ActivityRoute> { ActivityScreen(onBack = nav::navigateUp) }
            settingsGraph(nav)
        }
    }
}
