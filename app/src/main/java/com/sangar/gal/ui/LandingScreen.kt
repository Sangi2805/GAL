package com.sangar.gal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sangar.gal.R
import kotlinx.coroutines.delay

/**
 * The landing page draws the splash icon itself (res/drawable/splash_icon.xml, the elephant) at the size Android 12+
 * draws it: 288 dp, with the art inside the middle 192 dp circle. Same drawable, same size, same place, so the
 * splash and this screen read as one.
 */
val LandingLogoSize = 288.dp

/** Empty canvas under the art in splash_icon.xml: (288 - 192) / 2. */
private val LogoTrim = 48.dp

/**
 * Shown once, on the first launch after install, and afterwards from Settings > Privacy.
 *
 * The logo sits at the centre of the window because that is where Android 12+ draws its splash icon, and
 * that position is not ours to move. Landing on the same pixels is what makes the splash and this screen
 * read as one screen where the text simply appears under the logo.
 */
@Composable
fun LandingScreen(onContinue: () -> Unit, showQuip: Boolean = false, onQuipDone: () -> Unit = {}) {
    var quip by remember { mutableStateOf(showQuip) }
    LandingContent {
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Let's Go") }
    }
    // First launch only: the "describe yourself" joke, a moment after the page appears.
    if (quip) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(QUIP_DELAY_MILLIS)
            visible = true
        }
        if (visible) {
            IntroQuip(onDone = {
                quip = false
                onQuipDone()
            })
        }
    }
}

private const val QUIP_DELAY_MILLIS = 700L

/** The same message, reachable from Settings once the landing page has stopped appearing. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    LandingContent {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/**
 * The logo alone, on the same pixels, for the frame or two it takes DataStore to say which screen comes
 * next. Without it the elephant would blink out between the splash and the landing page. It waits for real
 * work and adds no delay of its own.
 */
@Composable
fun LandingLogoOnly() {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = null,
            modifier = Modifier.size(LandingLogoSize).align(Alignment.Center),
        )
    }
}

@Composable
private fun LandingContent(footer: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Half the screen less half the logo puts the logo's centre on the window's centre, where the
        // system splash drew it. Measured against the whole window, not the inset content area, because
        // that is what the platform centres against.
        // The splash art sits inside the middle 192 dp of its 288 dp canvas, so the bottom 48 dp of the drawable
        // is empty. The logo's box is that much shorter (the image overhangs it evenly, 24 dp top and bottom),
        // which pulls the text up under the elephant without moving her off the splash's pixels.
        val aboveLogo = maxHeight / 2 - LandingLogoSize / 2 + LogoTrim / 2
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(aboveLogo.coerceAtLeast(0.dp)))

            Box(Modifier.fillMaxWidth().height(LandingLogoSize - LogoTrim), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.splash_icon),
                    contentDescription = "A pink cartoon elephant",
                    modifier = Modifier.requiredSize(LandingLogoSize),
                )
            }

            Text(
                "Welcome to GAL",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "This app is not connected to the internet. It does not collect or share your data with anyone.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Our pink elephant roasts you when you have been on your phone too long. Less phone, more life.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            footer()

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}
