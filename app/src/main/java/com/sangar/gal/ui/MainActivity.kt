package com.sangar.gal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.ui.theme.GalTheme
import kotlinx.coroutines.launch

object Routes {
    const val LANDING = "landing"
    const val PRIVACY = "privacy"
    const val SETUP = "setup"
    const val HOME = "home"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    /**
     * Which screen a launch opens on. The landing page belongs to the first launch after install and is
     * never shown again unless app data is cleared; Settings > Privacy keeps the message reachable.
     * Pure, so both paths are unit tested.
     */
    fun startDestination(hasSeenLandingPage: Boolean, onboardingComplete: Boolean): String = when {
        !hasSeenLandingPage -> LANDING
        onboardingComplete -> HOME
        else -> SETUP
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GalTheme {
                Surface(Modifier.fillMaxSize()) { AppNavigation() }
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val context = LocalContext.current
    // Decide the first screen only after DataStore has answered, so returning users never flash the landing
    // page. Until it answers, hold the logo exactly where the splash drew it rather than showing a blank.
    val stored by produceState<Settings?>(initialValue = null) {
        value = context.container.settings.current()
    }
    val settings = stored ?: run {
        LandingLogoOnly()
        return
    }
    val done = settings.onboardingComplete

    val nav = rememberNavController()
    val startDest = Routes.startDestination(settings.hasSeenLandingPage, done)
    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.LANDING) {
            LandingScreen(onContinue = {
                // Written as the user leaves, never on arrival, so a crash on this screen cannot skip it
                // permanently. appScope so the write survives the screen going away underneath it.
                context.container.appScope.launch { context.container.settings.setHasSeenLandingPage() }
                nav.navigate(if (done) Routes.HOME else Routes.SETUP) {
                    popUpTo(Routes.LANDING) { inclusive = true }
                }
            })
        }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETUP) {
            SetupScreen(onDone = {
                // Coming back from home to fix something should return there, not stack a second home.
                if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSetup = { nav.navigate(Routes.SETUP) { launchSingleTop = true } },
                onOpenStats = { nav.navigate(Routes.STATS) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.STATS) { StatsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenPrivacy = { nav.navigate(Routes.PRIVACY) { launchSingleTop = true } },
            )
        }
    }
}
