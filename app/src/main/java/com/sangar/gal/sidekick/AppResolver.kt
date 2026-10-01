package com.sangar.gal.sidekick

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Maps a spoken phrase like "open g mail" onto an installed package.
 *
 * The index is built from queryIntentActivities(ACTION_MAIN / CATEGORY_LAUNCHER),
 * which is the only reliable definition of "app the user can see in their
 * launcher" — iterating getInstalledPackages() also returns headless packages
 * with no launchable activity.
 */
class AppResolver(private val context: Context) {

    data class AppEntry(
        /** Human label as shown in the launcher, e.g. "Google Maps". */
        val label: String,
        val packageName: String,
        /** Lowercased, punctuation stripped, e.g. "google maps". */
        val normalized: String,
        /** [normalized] with spaces removed, e.g. "googlemaps". */
        val squashed: String,
    )

    data class Match(val entry: AppEntry, val score: Double)

    /** Written from a background thread, read from the main thread. */
    @Volatile
    private var apps: List<AppEntry> = emptyList()

    val isEmpty: Boolean get() = apps.isEmpty()

    /** Rebuilds the label -> package index. Blocking; call off the main thread. */
    fun refresh() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivities failed", t)
            emptyList()
        }

        // One entry per package: some apps expose several launcher aliases.
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?.trim().orEmpty()
            if (label.isEmpty()) continue
            val normalized = normalize(label)
            if (normalized.isEmpty()) continue
            byPackage.putIfAbsent(
                pkg,
                AppEntry(label, pkg, normalized, normalized.replace(" ", "")),
            )
        }

        apps = byPackage.values.sortedBy { it.normalized }
        Log.i(TAG, "Indexed ${apps.size} launchable apps")
    }

    /**
     * Picks the best match across the recogniser's n-best list. Returns null when
     * nothing clears [MATCH_THRESHOLD].
     */
    fun resolve(spokenCandidates: List<String>): Match? =
        spokenCandidates
            .mapNotNull { resolveOne(it) }
            .maxByOrNull { it.score }

    fun resolveOne(spoken: String): Match? {
        val index = apps
        if (index.isEmpty()) return null

        val query = stripFillers(normalize(spoken))
        if (query.isEmpty()) return null
        val squashed = query.replace(" ", "")

        var best: Match? = null
        for (entry in index) {
            val score = score(query, squashed, entry)
            if (score >= MATCH_THRESHOLD && (best == null || score > best.score)) {
                best = Match(entry, score)
            }
        }
        return best
    }

    fun launch(entry: AppEntry): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for ${entry.packageName}")
            return false
        }
        // NEW_TASK is mandatory when starting from a Service context. Apps holding
        // SYSTEM_ALERT_WINDOW are exempt from the background-activity-start block.
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to launch ${entry.packageName}", t)
            false
        }
    }

    // ---- Matching ---------------------------------------------------------

    private fun score(query: String, querySquashed: String, entry: AppEntry): Double {
        if (entry.normalized == query || entry.squashed == querySquashed) return 1.0

        // Ignoring spaces is what rescues "g mail" -> "gmail" and
        // "insta gram" -> "instagram", where the recogniser split one word.
        var best = similarity(querySquashed, entry.squashed)

        if (entry.squashed.startsWith(querySquashed) || querySquashed.startsWith(entry.squashed)) {
            // Weight by coverage so a 2-letter query does not win every long name.
            val cover = min(querySquashed.length, entry.squashed.length).toDouble() /
                max(querySquashed.length, entry.squashed.length)
            best = max(best, 0.72 + 0.28 * cover)
        } else if (querySquashed.length >= 4 && entry.squashed.contains(querySquashed)) {
            val cover = querySquashed.length.toDouble() / entry.squashed.length
            best = max(best, 0.62 + 0.30 * cover)
        }

        // Token overlap handles reordering and extra words: "maps google".
        val queryTokens = query.split(' ').filter { it.isNotEmpty() }.toSet()
        val entryTokens = entry.normalized.split(' ').filter { it.isNotEmpty() }.toSet()
        if (queryTokens.isNotEmpty() && entryTokens.isNotEmpty()) {
            val shared = queryTokens.count { it in entryTokens }
            if (shared > 0) {
                val jaccard = shared.toDouble() /
                    (queryTokens.size + entryTokens.size - shared)
                best = max(best, 0.55 + 0.40 * jaccard)
            }
        }

        return best
    }

    /** 1.0 for identical strings, 0.0 for nothing in common. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    /** Two-row Levenshtein: O(min(a,b)) memory, which is all we need here. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                // Fold separators to spaces so "wi-fi" tokenises as "wi fi".
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(WHITESPACE, " ")
    }

    /**
     * Drops command words so "open up the gmail app please" reduces to "gmail".
     * If every token is a filler we keep the original — the user may genuinely
     * have an app called "Go".
     */
    private fun stripFillers(normalized: String): String {
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        val kept = tokens.filterNot { it in FILLER_WORDS }
        return if (kept.isEmpty()) normalized else kept.joinToString(" ")
    }

    private companion object {
        const val TAG = "AppResolver"

        /** Below this the guess is worse than admitting we did not understand. */
        const val MATCH_THRESHOLD = 0.72

        val WHITESPACE = Regex("\\s+")

        val FILLER_WORDS = setOf(
            "open", "launch", "start", "run", "fire", "bring", "pull", "load",
            "go", "to", "up", "the", "a", "an", "my", "me", "for", "now",
            "please", "app", "apps", "application", "hey", "yo", "ok", "okay",
            "boss", "sidekick", "can", "you", "i", "want", "need", "show",
        )
    }
}
