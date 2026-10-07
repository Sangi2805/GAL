package com.sangar.gal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sangar.gal.debug.StageDebug
import com.sangar.gal.overlay.TestCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only, for driving acceptance tests from adb:
 *
 *   adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver
 *       -a com.sangar.gal.debug.SET_THRESHOLD --ei minutes 1
 *   adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver
 *       -a com.sangar.gal.debug.TEST_CARD --ei tier 3 --ei nags 0
 *   adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver
 *       -a com.sangar.gal.debug.PLAYGROUND
 *   adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver
 *       -a com.sangar.gal.debug.DEMO_SCENE --es kind roast      (normal, roast or notfound)
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                when (intent.action) {
                    "com.sangar.gal.debug.SET_THRESHOLD" -> {
                        val minutes = intent.getIntExtra("minutes", -1)
                        if (minutes >= 1) {
                            context.container.settings.setThresholdMinutes(minutes)
                            Log.i(TAG, "threshold set to $minutes min")
                        }
                    }
                    "com.sangar.gal.debug.TEST_CARD" -> {
                        val result = TestCard.show(context, intent.getIntExtra("tier", 1), intent.getIntExtra("nags", 0))
                        Log.i(TAG, "test card: $result")
                    }
                    "com.sangar.gal.debug.PLAYGROUND" -> withContext(Dispatchers.Main) {
                        Log.i(TAG, "playground: ${StageDebug.playground(context)}")
                    }
                    "com.sangar.gal.debug.DEMO_SCENE" -> withContext(Dispatchers.Main) {
                        val kind = intent.getStringExtra("kind") ?: "normal"
                        Log.i(TAG, "demo scene $kind: ${StageDebug.demo(context, kind)}")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GAL.Debug"
    }
}
