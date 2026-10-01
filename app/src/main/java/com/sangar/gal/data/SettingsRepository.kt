package com.sangar.gal.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sangar.gal.phrases.PhrasePack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    /** Setup switch: measure screen time and let Sidekick deliver roast cards. */
    val roastsEnabled: Boolean = false,
    /** Setup switch: the floating Sidekick you tap and talk to. */
    val voiceEnabled: Boolean = false,
    /** Home switch: whether roast cards appear. Screen time is measured either way while roasts are set up. */
    val enabled: Boolean = false,
    val thresholdMinutes: Int = Threshold.DEFAULT_MINUTES,
    val exclusions: Set<String> = emptySet(),
    val exclusionsSeeded: Boolean = false,
    val onboardingComplete: Boolean = false,
    val hasSeenLandingPage: Boolean = false,
    val batteryPromptShown: Boolean = false,
    val notificationPermissionRequested: Boolean = false,
    val microphonePermissionRequested: Boolean = false,
    /** Set when drawing the overlay failed at runtime. Nagging stays off until the user fixes it. */
    val overlayFailed: Boolean = false,
    /** The home screen tab: which set of lines the cards use. */
    val phrasePack: PhrasePack = PhrasePack.SPICY,
    /** Voice Sidekick: play a short scene when opening an app. Off means "Quick open": just open it. */
    val scenesEnabled: Boolean = true,
    /** Roast and hammer open a social app used a lot (needs Screen Time Roasts for the usage numbers). */
    val appRoastsEnabled: Boolean = true,
    /** Heavy use, any one of: opened this many times today... */
    val roastMinOpens: Int = AppRoastLimits.DEFAULT_OPENS,
    /** ...more than this many minutes today... */
    val roastMinMinutes: Int = AppRoastLimits.DEFAULT_MINUTES,
    /** ...or a seven day average over this many minutes a day. */
    val roastMinAverage: Int = AppRoastLimits.DEFAULT_AVERAGE,
    /** Apps the user counts as social for app roasts, on top of the ones Android or GAL already know. */
    val extraSocialApps: Set<String> = emptySet(),
    /** Short original sound effects in scenes. Off by default. */
    val sceneSounds: Boolean = false,
    /** A tap of vibration on landings and hits. */
    val sceneHaptics: Boolean = true,
) {
    /** Measuring runs once setup is finished with the roasts switch on, whether cards are on or off. */
    val roastsActive: Boolean get() = onboardingComplete && roastsEnabled

    val voiceActive: Boolean get() = onboardingComplete && voiceEnabled
}

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val ROASTS_ENABLED = booleanPreferencesKey("roasts_enabled")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val ENABLED = booleanPreferencesKey("enabled")
        val THRESHOLD_MINUTES = intPreferencesKey("threshold_minutes")
        val EXCLUSIONS = stringSetPreferencesKey("exclusions")
        val EXCLUSIONS_SEEDED = booleanPreferencesKey("exclusions_seeded")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val HAS_SEEN_LANDING_PAGE = booleanPreferencesKey("has_seen_landing_page")
        val BATTERY_PROMPT_SHOWN = booleanPreferencesKey("battery_prompt_shown")
        val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")
        val MICROPHONE_PERMISSION_REQUESTED = booleanPreferencesKey("microphone_permission_requested")
        val OVERLAY_FAILED = booleanPreferencesKey("overlay_failed")
        val RING_BUFFER = stringPreferencesKey("phrase_ring_buffer")
        val PHRASE_PACK = stringPreferencesKey("phrase_pack")
        val SCENES_ENABLED = booleanPreferencesKey("scenes_enabled")
        val APP_ROASTS_ENABLED = booleanPreferencesKey("app_roasts_enabled")
        val ROAST_MIN_OPENS = intPreferencesKey("roast_min_opens")
        val ROAST_MIN_MINUTES = intPreferencesKey("roast_min_minutes")
        val ROAST_MIN_AVERAGE = intPreferencesKey("roast_min_average")
        val EXTRA_SOCIAL_APPS = stringSetPreferencesKey("extra_social_apps")
        val SCENE_SOUNDS = booleanPreferencesKey("scene_sounds")
        val SCENE_HAPTICS = booleanPreferencesKey("scene_haptics")
    }

    private val preferences: Flow<Preferences> = store.data.catch { e ->
        // A corrupt or unreadable file should degrade to defaults, never crash the service.
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<Settings> = preferences.map { p ->
        Settings(
            roastsEnabled = p[Keys.ROASTS_ENABLED] ?: false,
            voiceEnabled = p[Keys.VOICE_ENABLED] ?: false,
            enabled = p[Keys.ENABLED] ?: false,
            thresholdMinutes = Threshold.clamp(p[Keys.THRESHOLD_MINUTES] ?: Threshold.DEFAULT_MINUTES),
            exclusions = p[Keys.EXCLUSIONS] ?: emptySet(),
            exclusionsSeeded = p[Keys.EXCLUSIONS_SEEDED] ?: false,
            onboardingComplete = p[Keys.ONBOARDING_COMPLETE] ?: false,
            hasSeenLandingPage = p[Keys.HAS_SEEN_LANDING_PAGE] ?: false,
            batteryPromptShown = p[Keys.BATTERY_PROMPT_SHOWN] ?: false,
            notificationPermissionRequested = p[Keys.NOTIFICATION_PERMISSION_REQUESTED] ?: false,
            microphonePermissionRequested = p[Keys.MICROPHONE_PERMISSION_REQUESTED] ?: false,
            overlayFailed = p[Keys.OVERLAY_FAILED] ?: false,
            phrasePack = PhrasePack.parse(p[Keys.PHRASE_PACK]),
            scenesEnabled = p[Keys.SCENES_ENABLED] ?: true,
            appRoastsEnabled = p[Keys.APP_ROASTS_ENABLED] ?: true,
            roastMinOpens = AppRoastLimits.clampOpens(p[Keys.ROAST_MIN_OPENS] ?: AppRoastLimits.DEFAULT_OPENS),
            roastMinMinutes = AppRoastLimits.clampMinutes(p[Keys.ROAST_MIN_MINUTES] ?: AppRoastLimits.DEFAULT_MINUTES),
            roastMinAverage = AppRoastLimits.clampAverage(p[Keys.ROAST_MIN_AVERAGE] ?: AppRoastLimits.DEFAULT_AVERAGE),
            extraSocialApps = p[Keys.EXTRA_SOCIAL_APPS] ?: emptySet(),
            sceneSounds = p[Keys.SCENE_SOUNDS] ?: false,
            sceneHaptics = p[Keys.SCENE_HAPTICS] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun setEnabled(enabled: Boolean) = store.edit { it[Keys.ENABLED] = enabled }

    /**
     * The first time roasts are switched on, cards are switched on with them, so the feature works without a
     * second trip to the home screen. After that the home switch is the user's to keep.
     */
    suspend fun setRoastsEnabled(on: Boolean) = store.edit {
        if (on && it[Keys.ROASTS_ENABLED] == null) it[Keys.ENABLED] = true
        it[Keys.ROASTS_ENABLED] = on
    }

    suspend fun setVoiceEnabled(on: Boolean) = store.edit { it[Keys.VOICE_ENABLED] = on }

    suspend fun setThresholdMinutes(minutes: Int) = store.edit {
        // 0 means "off" and is handled by the master switch, so it never reaches storage as a threshold.
        it[Keys.THRESHOLD_MINUTES] = Threshold.clamp(minutes)
    }

    suspend fun setExclusions(packages: Set<String>) = store.edit {
        it[Keys.EXCLUSIONS] = packages
        it[Keys.EXCLUSIONS_SEEDED] = true
    }

    /** Writes the runtime-resolved defaults once, so later user edits are never overwritten. */
    suspend fun seedExclusionsIfNeeded(defaults: () -> Set<String>) = store.edit {
        if (it[Keys.EXCLUSIONS_SEEDED] != true) {
            it[Keys.EXCLUSIONS] = (it[Keys.EXCLUSIONS] ?: emptySet()) + defaults()
            it[Keys.EXCLUSIONS_SEEDED] = true
        }
    }

    suspend fun setOnboardingComplete() = store.edit { it[Keys.ONBOARDING_COMPLETE] = true }

    suspend fun setHasSeenLandingPage() = store.edit { it[Keys.HAS_SEEN_LANDING_PAGE] = true }

    suspend fun markBatteryPromptShown() = store.edit { it[Keys.BATTERY_PROMPT_SHOWN] = true }

    suspend fun markNotificationPermissionRequested() =
        store.edit { it[Keys.NOTIFICATION_PERMISSION_REQUESTED] = true }

    suspend fun markMicrophonePermissionRequested() =
        store.edit { it[Keys.MICROPHONE_PERMISSION_REQUESTED] = true }

    suspend fun setOverlayFailed(failed: Boolean) = store.edit { it[Keys.OVERLAY_FAILED] = failed }

    suspend fun setPhrasePack(pack: PhrasePack) = store.edit { it[Keys.PHRASE_PACK] = pack.key }

    suspend fun setScenesEnabled(on: Boolean) = store.edit { it[Keys.SCENES_ENABLED] = on }

    suspend fun setAppRoastsEnabled(on: Boolean) = store.edit { it[Keys.APP_ROASTS_ENABLED] = on }

    suspend fun setRoastMinOpens(value: Int) = store.edit { it[Keys.ROAST_MIN_OPENS] = AppRoastLimits.clampOpens(value) }

    suspend fun setRoastMinMinutes(value: Int) = store.edit { it[Keys.ROAST_MIN_MINUTES] = AppRoastLimits.clampMinutes(value) }

    suspend fun setRoastMinAverage(value: Int) = store.edit { it[Keys.ROAST_MIN_AVERAGE] = AppRoastLimits.clampAverage(value) }

    suspend fun setExtraSocialApps(packages: Set<String>) = store.edit { it[Keys.EXTRA_SOCIAL_APPS] = packages }

    suspend fun setSceneSounds(on: Boolean) = store.edit { it[Keys.SCENE_SOUNDS] = on }

    suspend fun setSceneHaptics(on: Boolean) = store.edit { it[Keys.SCENE_HAPTICS] = on }

    suspend fun readRingBuffer(): List<Int> =
        preferences.first()[Keys.RING_BUFFER].orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    suspend fun writeRingBuffer(ids: List<Int>) = store.edit { it[Keys.RING_BUFFER] = ids.joinToString(",") }
}
