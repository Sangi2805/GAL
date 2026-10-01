package com.sangar.gal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.data.SessionCardCap
import com.sangar.gal.data.Threshold
import com.sangar.gal.overlay.Mascot
import com.sangar.gal.phrases.ChosenPhrase
import com.sangar.gal.phrases.OwlMode
import kotlinx.coroutines.launch

/**
 * "Nag me after": a snapping slider from 1 minute to 8 hours plus an exact-minutes field.
 * Entering 0 switches nagging off through the master switch instead of storing 0.
 * Confirming a threshold over an hour earns an owl_mode line, shown here in the app, never as an overlay.
 */
@Composable
fun ThresholdCard(storedMinutes: Int, onCommit: (Int) -> Unit, onSwitchOff: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var index by remember { mutableIntStateOf(Threshold.indexOf(storedMinutes)) }
    var shownMinutes by remember { mutableIntStateOf(storedMinutes) }
    var dragging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var fieldText by remember { mutableStateOf(storedMinutes.toString()) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var owl by remember { mutableStateOf<ChosenPhrase?>(null) }

    // Follow the stored value when it changes elsewhere, unless the user is in the middle of changing it here,
    // so the card never shows a different threshold from the one the service is using.
    LaunchedEffect(storedMinutes) {
        if (!dragging) {
            index = Threshold.indexOf(storedMinutes)
            shownMinutes = storedMinutes
        }
        if (!editing) fieldText = storedMinutes.toString()
    }

    fun confirm(minutes: Int) {
        val previous = storedMinutes
        onCommit(minutes)
        shownMinutes = minutes
        index = Threshold.indexOf(minutes)
        fieldText = minutes.toString()
        fieldError = null
        note = null
        when {
            // Shown once per confirmation of a new long threshold, not on every drag frame or re-save.
            OwlMode.applies(minutes) && minutes != previous -> scope.launch { owl = context.container.phrases.owlLine(minutes) }
            !OwlMode.applies(minutes) -> owl = null
        }
    }

    fun applyField() {
        when (val input = Threshold.parse(fieldText)) {
            Threshold.Input.Off -> {
                onSwitchOff()
                fieldText = storedMinutes.toString()
                fieldError = null
                owl = null
                note = "0 means off, so nagging is switched off. Your threshold stays at ${Threshold.describe(storedMinutes)}."
            }
            is Threshold.Input.Minutes -> confirm(input.minutes)
            is Threshold.Input.Invalid -> fieldError = input.message
        }
        focus.clearFocus()
    }

    SectionCard {
        Text("Nag me after", style = MaterialTheme.typography.titleMedium)
        Text(
            Threshold.describe(shownMinutes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "of continuous use, then again every ${Threshold.describe(shownMinutes)}. " +
                "${SessionCardCap.PER_SESSION} cards a session, and the last one gives up on you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = {
                dragging = true
                index = it.toInt().coerceIn(0, Threshold.stops.lastIndex)
                shownMinutes = Threshold.minutesAt(index)
            },
            onValueChangeFinished = {
                dragging = false
                confirm(Threshold.minutesAt(index))
            },
            valueRange = 0f..Threshold.stops.lastIndex.toFloat(),
            steps = Threshold.stops.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                // 41 stops would be a row of dots; the big number already says where you are.
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.semantics { contentDescription = "Time before the first nag" },
        )
        // The stops are not evenly spaced in time, so "1 h" sits where 60 minutes really is on the track.
        val hourFraction = Threshold.indexOf(60).toFloat() / Threshold.stops.lastIndex
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Threshold.describe(Threshold.MIN_MINUTES), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(hourFraction))
            Text("1 h", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f - hourFraction))
            Text(Threshold.describe(Threshold.MAX_MINUTES), style = MaterialTheme.typography.labelMedium)
        }

        OutlinedTextField(
            value = fieldText,
            onValueChange = { text ->
                fieldText = text.filter(Char::isDigit).take(3)
                fieldError = null
            },
            label = { Text("Exact minutes") },
            singleLine = true,
            isError = fieldError != null,
            supportingText = {
                Text(fieldError ?: "${Threshold.MIN_MINUTES} to ${Threshold.MAX_MINUTES}, or 0 to switch nagging off")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { applyField() }),
            trailingIcon = { TextButton(onClick = { applyField() }) { Text("Set") } },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { editing = it.isFocused },
        )

        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        owl?.let { line -> OwlLine(line) { owl = null } }
    }
}

@Composable
private fun OwlLine(line: ChosenPhrase, onDismiss: () -> Unit) {
    val mascot = Mascot.forNag(line.tier, nagsThisSession = 0, tags = line.tags)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(12.dp))
            Image(painterResource(mascot.drawable), contentDescription = null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Owl mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondary)
            }
            ActionIconTinted(R.drawable.ic_close, "Dismiss", MaterialTheme.colorScheme.onSecondary, onDismiss)
        }
    }
}
