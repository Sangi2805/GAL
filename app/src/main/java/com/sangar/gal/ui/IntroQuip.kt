package com.sangar.gal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sangar.gal.R
import kotlinx.coroutines.delay

/**
 * The first-run joke. GAL asks you to describe yourself in one word, gives you [ASK_MILLIS] to do it, and then,
 * typed or not, cuts you off: "Never mind. We don't care." The point is the app's whole personality in five
 * seconds: it is here to keep you off your phone, not to get to know you. Whatever was typed is thrown away;
 * it is never stored or sent anywhere.
 */
@Composable
fun IntroQuip(onDone: () -> Unit) {
    var answered by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A smooth bar so the cut-off feels deliberate, not like a glitch.
        val steps = 60
        repeat(steps) {
            delay(ASK_MILLIS / steps)
            progress = (it + 1) / steps.toFloat()
        }
        keyboard?.hide()
        word = ""
        answered = true
    }

    if (!answered) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Before we start", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Describe yourself in one word.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it.take(30) },
                        singleLine = true,
                        placeholder = { Text("One word") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            },
            confirmButton = {},
        )
    } else {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = {
                Image(painterResource(R.drawable.mascot_bored), contentDescription = null, modifier = Modifier.size(72.dp))
            },
            title = {
                Text(
                    "Never mind. We don't care.",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Text(
                    "This app has one job: keeping you off your phone. Getting to know you would only keep you on " +
                        "it longer.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Fair enough", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.CenterVertically))
                }
            },
        )
    }
}

/** How long the "describe yourself" box stays up, typed in or not. */
const val ASK_MILLIS = 3_000L
