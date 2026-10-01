package com.sangar.gal.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sangar.gal.Permissions
import com.sangar.gal.overlay.ShowResult
import com.sangar.gal.overlay.TestCard
import kotlinx.coroutines.launch

/**
 * A manual overlay trigger in Settings, for checking a real card on a real phone. Debug builds only:
 * the release source set has an empty one of these, so none of it ships.
 */
@Composable
fun TestPopupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testTier by remember { mutableIntStateOf(1) }

    SectionCard {
        Text("Test popup", style = MaterialTheme.typography.titleMedium)
        Text(
            "Shows a real card right now, with a line and a face for the tier you pick. Nothing is logged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1 to "Mild", 2 to "Pointed", 3 to "Brutal").forEach { (tier, label) ->
                if (testTier == tier) {
                    Button(onClick = { testTier = tier }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { testTier = tier }) { Text(label) }
                }
            }
        }
        Button(onClick = {
            if (!Permissions.canDrawOverlays(context)) {
                Toast.makeText(context, "Allow \"Display over other apps\" first.", Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    val result = runCatching { TestCard.show(context, testTier) }.getOrDefault(ShowResult.FAILED)
                    if (result != ShowResult.SHOWN) {
                        Toast.makeText(context, "The card could not be drawn.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }) { Text("Show test popup") }
    }
}
