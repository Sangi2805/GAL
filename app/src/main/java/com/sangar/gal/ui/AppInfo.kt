package com.sangar.gal.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(val packageName: String, val label: String)

/** Labels and icons for package names. Everything tolerates uninstalled or invisible packages. */
object AppInfo {

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrDefault(packageName)

    /** Small cache, so scrolling the app picker up and down does not redraw every icon from scratch. */
    private val icons = android.util.LruCache<String, ImageBitmap>(80)

    fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        return runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()?.also { icons.put(key, it) }
    }

    /** Apps with a launcher entry, which is what people think of as "their apps". */
    fun launchable(context: Context): List<InstalledApp> = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
