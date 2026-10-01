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
import com.sangar.gal.phrases.PhrasePack

/**
 * The two tabs on the home screen. The selected tab is the style every card uses from then on:
 * Spicy (the tiered, moment-aware lines) or You may cry (one-line roasts, greeting on the first card).
 */
@Composable
fun CardStyleTabs(selected: PhrasePack, onSelect: (PhrasePack) -> Unit) {
    val context = LocalContext.current
    val phrases = context.container.phrases
    var shuffle by remember { mutableIntStateOf(0) }
    val size by produceState(initialValue = 0, selected) { value = phrases.sizeOf(selected) }
    val samples by produceState(initialValue = emptyList<String>(), selected, shuffle) {
        value = phrases.preview(selected, PREVIEW_COUNT)
    }

    SectionCard {
        Text("Card style", style = MaterialTheme.typography.titleMedium)
        PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            PhrasePack.entries.forEach { pack ->
                Tab(
                    selected = pack == selected,
                    onClick = { if (pack != selected) onSelect(pack) },
                    text = { Text(tabLabel(pack), fontWeight = if (pack == selected) FontWeight.Bold else FontWeight.Normal) },
                )
            }
        }
        Text(
            when (selected) {
                PhrasePack.SPICY -> "Sarcastic lines that get sharper as the session drags on and your week trends up. $size lines."
                PhrasePack.CRY -> "One-line roasts, mean on purpose. The first card of every session greets you properly. $size lines."
            },
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

private fun tabLabel(pack: PhrasePack) = when (pack) {
    PhrasePack.SPICY -> "🌶️ Spicy"
    PhrasePack.CRY -> "😭 You may cry"
}

private const val PREVIEW_COUNT = 3
