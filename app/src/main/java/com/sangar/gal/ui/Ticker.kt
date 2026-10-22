package com.sangar.gal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * A counter that goes up every [periodMillis] while the screen is visible, and not at all in the background.
 * Read it inside the one place that has to move with the clock, so only that part recomposes.
 */
@Composable
fun rememberTicker(periodMillis: Long): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(0L, lifecycle, periodMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value++
                delay(periodMillis)
            }
        }
    }
}

/** Today's date that rolls over at midnight, so a screen left open overnight does not keep showing yesterday. */
@Composable
fun rememberToday(today: () -> LocalDate): State<LocalDate> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(today(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = today()
                val zone = ZoneId.systemDefault()
                val nextMidnight = value.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                delay((nextMidnight - System.currentTimeMillis()).coerceIn(1_000L, 60 * 60_000L))
            }
        }
    }
}
