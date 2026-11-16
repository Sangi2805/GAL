package com.sangar.gal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sangar.gal.R
import com.sangar.gal.ui.theme.TextSoft

/** The first screen: the elephant, the name, one line. Nothing to read, one button. It never scrolls. */
@Composable
fun IntroScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ElephantBadge(200.dp)
            Spacer(Modifier.height(28.dp))
            Text("GetALife", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(10.dp))
            Text("Scroll less. Live more.", fontSize = 20.sp, color = TextSoft)
        }
        StepDots(active = 0)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Get started", onContinue)
    }
}

/**
 * The elephant alone, in the middle, for the frame or two it takes to learn which screen comes next. It sits
 * where Android draws the splash icon, so the splash and the app read as one.
 */
@Composable
fun LandingLogoOnly() {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = null,
            modifier = Modifier.size(288.dp).align(Alignment.Center),
        )
    }
}
