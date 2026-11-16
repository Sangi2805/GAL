package com.sangar.gal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
    /** The intro: elephant, name, one line. */
    const val LANDING = "landing"

    /** The permissions the phone has to allow. */
    const val SETUP = "setup"

    /** Which apps, how long a day, which roast style. Also where home's setup icon leads. */
    const val PICK = "pick"
    const val HOME = "home"

    /**
     * Which screen a launch opens on. The intro belongs to the first launch after install and is never shown
     * again unless app data is cleared. Pure, so both paths are unit tested.
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
        // GAL is always dark, so the status and navigation bar icons are always the light ones.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
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
    // Decide the first screen only after DataStore has answered, so returning users never flash the intro.
    // Until it answers, hold the logo exactly where the splash drew it rather than showing a blank.
    val stored by produceState<Settings?>(initialValue = null) {
        value = context.container.settings.current()
    }
    val settings = stored ?: run {
        LandingLogoOnly()
        return
    }

    val nav = rememberNavController()
    val startDest = Routes.startDestination(settings.hasSeenLandingPage, settings.onboardingComplete)
    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.LANDING) {
            IntroScreen(
                onContinue = {
                    // Written as the user leaves, never on arrival, so a crash on this screen cannot skip it
                    // permanently. appScope so the write survives the screen going away underneath it.
                    context.container.appScope.launch { context.container.settings.setHasSeenLandingPage() }
                    nav.navigate(Routes.SETUP) { popUpTo(Routes.LANDING) { inclusive = true } }
                },
            )
        }
        composable(Routes.SETUP) {
            PermissionsScreen(onDone = {
                // Reached from home to fix a switched-off setting: go back there. Otherwise carry on with setup.
                if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                    nav.navigate(Routes.PICK) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            })
        }
        composable(Routes.PICK) {
            // With home underneath this is "change my setup"; without it, it is the last step of first-time setup.
            val editing = nav.previousBackStackEntry?.destination?.route == Routes.HOME
            PickScreen(editing = editing, onDone = {
                if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.PICK) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onEdit = { nav.navigate(Routes.PICK) { launchSingleTop = true } },
                onFixPermissions = { nav.navigate(Routes.SETUP) { launchSingleTop = true } },
            )
        }
    }
}
