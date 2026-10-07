package com.sangar.gal.sidekick

import android.content.pm.ApplicationInfo

/** What kind of app was just opened, as far as the elephant cares. */
enum class AppKind { SOCIAL, PRODUCTIVE, NEUTRAL }

/**
 * Pure rules for how the elephant reacts. Kept free of Android views so they can be unit tested.
 *
 * Social apps make her say "nooo", useful apps make her proud, and a long session makes her cross.
 * Messaging apps count as neutral: talking to real people is not the problem.
 */
object Reactions {

    /** Feeds and short videos. Matched by package prefix. */
    val SOCIAL_PACKAGES = listOf(
        "com.instagram.",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok in some regions
        "com.snapchat.",
        "com.twitter.",
        "com.reddit.",
        "com.pinterest",
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.tumblr",
        "com.linkedin.android",
        "com.instagram.barcelona", // Threads
        "tv.twitch.android",
        "com.netflix.mediaclient",
        "in.mohalla.sharechat",
        "com.kwai.",
        "com.bereal.",
        "org.joinmastodon.",
        "com.bsky.",
        "xyz.blueskyweb.",
    )

    /** Not "social" for our purposes even when the store files them that way. */
    val MESSAGING_PACKAGES = listOf(
        "com.whatsapp",
        "org.telegram.",
        "org.thoughtcrime.securesms", // Signal
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.facebook.orca", // Messenger
        "com.discord",
        "com.google.android.dialer",
        "com.android.",
    )

    /** Work, study, reading, fitness and the like. */
    val PRODUCTIVE_PACKAGES = listOf(
        "com.google.android.apps.docs", // Docs, Sheets, Slides, Drive
        "com.google.android.keep",
        "com.google.android.calendar",
        "com.google.android.apps.tasks",
        "com.google.android.gm", // Gmail
        "com.microsoft.office.",
        "com.microsoft.todos",
        "com.microsoft.teams",
        "com.microsoft.skydrive",
        "notion.id",
        "com.todoist",
        "com.ticktick.",
        "com.evernote",
        "md.obsidian",
        "com.duolingo",
        "org.khanacademy.",
        "org.coursera.",
        "com.udemy.",
        "com.amazon.kindle",
        "com.google.android.apps.books",
        "com.audible.",
        "com.google.android.apps.fitness",
        "com.strava",
        "com.headspace.",
        "com.calm.",
        "com.anki",
        "com.ankidroid",
        "com.Slack",
        "com.adobe.reader",
        "cc.forestapp",
        "org.lichess.",
        "com.chess",
    )

    /** Social first, then messaging, then the known useful list, then what the store category says. */
    fun classify(packageName: String?, category: Int?): AppKind {
        if (packageName.isNullOrBlank()) return AppKind.NEUTRAL
        if (SOCIAL_PACKAGES.any { packageName.startsWith(it) }) return AppKind.SOCIAL
        if (MESSAGING_PACKAGES.any { packageName.startsWith(it) }) return AppKind.NEUTRAL
        if (PRODUCTIVE_PACKAGES.any { packageName.startsWith(it) }) return AppKind.PRODUCTIVE
        return when (category) {
            ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_VIDEO -> AppKind.SOCIAL
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppKind.PRODUCTIVE
            else -> AppKind.NEUTRAL
        }
    }

    /**
     * 0..1 anger from how long this phone session has run against the roast threshold. Calm up to half the
     * threshold, starts to redden after that, has steam about the time the threshold is reached, and is
     * fully furious at twice the threshold.
     */
    fun anger(activeMillis: Long, thresholdMinutes: Int, sessionOpen: Boolean): Float {
        if (!sessionOpen || thresholdMinutes <= 0 || activeMillis <= 0L) return 0f
        val ratio = activeMillis / (thresholdMinutes * 60_000f)
        return ((ratio - 0.5f) / 1.5f).coerceIn(0f, 1f)
    }

    /** From this much anger up, she wears the angry face too, not just the red tint. */
    const val ANGRY_FACE_AT = 1f / 3f
}
