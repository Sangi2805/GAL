package com.sangar.gal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sangar.gal.container
import com.sangar.gal.phrases.Tone

/**
 * "How honest should she be?": Calm, Honest or Brutal, with a few example lines. The choice decides every
 * card from then on. Used on home and at the end of setup.
 */
@Composable
fun ToneTabs(selected: Tone, onSelect: (Tone) -> Unit, title: String = "How honest should she be?") {
    val context = LocalContext.current
    val phrases = context.container.phrases
    var shuffle by remember { mutableIntStateOf(0) }
    val samples by produceState(initialValue = emptyList<String>(), selected, shuffle) {
        value = phrases.preview(selected.pack, PREVIEW_COUNT, selected.maxTier)
    }

    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            Tone.entries.forEach { tone ->
                Tab(
                    selected = tone == selected,
                    onClick = { if (tone != selected) onSelect(tone) },
                    text = { Text(tone.title, fontWeight = if (tone == selected) FontWeight.Bold else FontWeight.Normal) },
                )
            }
        }
        Text(
            selected.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            samples.forEach { line ->
                Text("“$line”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { shuffle++ }) { Text("More examples") }
        }
    }
}

private const val PREVIEW_COUNT = 3
