package com.sangar.gal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sangar.gal.debug.StageDebug
import com.sangar.gal.overlay.TestCard
import com.sangar.gal.sidekick.SidekickStatus
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
 *   adb shell am broadcast -n com.sangar.gal/.DebugCommandReceiver
 *       -a com.sangar.gal.debug.HEAR --es spoken "instagram" --ez roast true
 *
 * HEAR runs a command through Voice Sidekick as if it had heard it: the real app, scene and launch. Sidekick
 * must be on screen. "roast true" forces the roast scene without the usage rules and is not counted.
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
                    "com.sangar.gal.debug.HEAR" -> withContext(Dispatchers.Main) {
                        val spoken = intent.getStringExtra("spoken").orEmpty()
                        val hear = SidekickStatus.debugHear
                        when {
                            spoken.isBlank() -> Log.w(TAG, "hear: pass --es spoken \"app name\"")
                            hear == null -> Log.w(TAG, "hear: Voice Sidekick is not on screen")
                            else -> {
                                hear(spoken, intent.getBooleanExtra("roast", false))
                                Log.i(TAG, "hear: sent")
                            }
                        }
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
