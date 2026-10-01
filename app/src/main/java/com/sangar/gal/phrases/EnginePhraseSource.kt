package com.sangar.gal.phrases

import android.content.Context
import com.sangar.gal.service.NagLog
import com.sangar.gal.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Ring buffer persisted in DataStore so restarts do not bring back the same lines. */
class DataStoreRecentIdStore(private val settings: SettingsRepository) : RecentIdStore {
    override suspend fun load() = settings.readRingBuffer()
    override suspend fun save(ids: List<Int>) {
        settings.writeRingBuffer(ids)
    }
}

class EnginePhraseSource(
    private val context: Context,
    private val recentStore: RecentIdStore,
    private val signals: UsageSignalsProvider,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : PhraseSource {

    private val loadLock = Mutex()
    private var engine: PhraseEngine? = null

    override suspend fun next(request: NagRequest): ChosenPhrase {
        val usage = runCatching { signals.signals() }.getOrElse {
            NagLog.w(TAG, "usage signals unavailable, assuming flat", it)
            UsageSignals()
        }
        val now = clock()

        val tier = NagContext.tier(request.sessionMinutes, usage)
        val tags = NagContext.tags(request.sessionMinutes, request.nagsThisSession, now, usage)
        if (request.pack == PhrasePack.CRY) {
            return if (request.lastOfSession) {
                closingRoastAndLog(tags, greet = false, now)
            } else {
                roastAndLog(tags, greet = request.nagsThisSession == 0, now)
            }
        }
        
        // The spicy session's last card is a sign-off: the app is announcing that it is done
        // nagging this session.
        if (request.lastOfSession) return pickAndLog(NagContext.GIVE_UP_TAGS, tier)
        return pickAndLog(tags, tier)
    }

    /** For the test popup: forces a tier, with a session length that fits it and a flat trend. */
    suspend fun sample(tier: Int, nagsThisSession: Int = 0, pack: PhrasePack = PhrasePack.SPICY): ChosenPhrase {
        val minutes = when (tier) {
            1 -> 20L
            2 -> 60L
            else -> 150L
        }
        val now = clock()
        val tags = NagContext.tags(minutes, nagsThisSession, now, UsageSignals())
        if (pack == PhrasePack.CRY) return roastAndLog(tags, greet = nagsThisSession == 0, now)
        return pickAndLog(tags, tier)
    }

    /** How many lines a pack holds, for the home screen tabs. */
    suspend fun sizeOf(pack: PhrasePack): Int = engine().sizeOf(pack)

    suspend fun preview(pack: PhrasePack, count: Int): List<String> = engine().preview(pack, count).map { it.text }

    /** A one-off owl_mode reaction for a confirmed threshold over an hour. Never empty. */
    suspend fun owlLine(thresholdMinutes: Int): ChosenPhrase {
        val pick = runCatching { engine().pickOwl(thresholdMinutes) }.getOrElse {
            NagLog.e(TAG, "owl pick threw, using the hardcoded owl line", it)
            Pick(Phrase(PhraseEngine.DEFAULT_PHRASE_ID, OwlMode.DEFAULT_TEXT, OwlMode.tierFor(thresholdMinutes), setOf(Tags.OWL_MODE)), 0, 0, PoolSource.HARDCODED_DEFAULT)
        }
        NagLog.d(TAG, "owl line threshold=${thresholdMinutes}min tier=${pick.phrase.tier} pool=${pick.poolSize} source=${pick.source} id=${pick.phrase.id}")
        val phrase = pick.phrase.takeIf { it.text.isNotBlank() }
            ?: Phrase(PhraseEngine.DEFAULT_PHRASE_ID, OwlMode.DEFAULT_TEXT, pick.phrase.tier, setOf(Tags.OWL_MODE))
        return ChosenPhrase(phrase.id, phrase.text, phrase.tier, phrase.tags)
    }

    /** Called at app start so a broken phrases.json is reported immediately, not at the first nag. */
    suspend fun preload() {
        engine()
    }

    suspend fun resetRecent() {
        engine?.resetRecent() ?: recentStore.save(emptyList())
    }

    /** Roasts are always tier 3 on the card (the horrified face); the first of a session gets a greeting. */
    private suspend fun roastAndLog(tags: Set<String>, greet: Boolean, now: LocalDateTime): ChosenPhrase {
        val pick = runCatching { engine().pickRoast(tags, ROAST_TIER) }.getOrElse {
            NagLog.e(TAG, "roast pick threw, using the hardcoded default line", it)
            Pick(PhraseEngine.defaultPhrase(ROAST_TIER), 0, 0, PoolSource.HARDCODED_DEFAULT)
        }
        val roast = pick.phrase.takeIf { it.text.isNotBlank() } ?: PhraseEngine.defaultPhrase(ROAST_TIER)
        val text = RoastGreeting.compose(roast.text, if (greet) 0 else 1, now)
        NagLog.d(TAG, "roast pack=${roast.pack.key} pool=${pick.poolSize} window=${pick.effectiveWindow} source=${pick.source} id=${roast.id} greeting=$greet")
        return ChosenPhrase(roast.id, text, ROAST_TIER, roast.tags)
    }

    private suspend fun closingRoastAndLog(tags: Set<String>, greet: Boolean, now: LocalDateTime): ChosenPhrase {
        val pick = runCatching { engine().pickClosingRoast(tags, ROAST_TIER) }.getOrElse {
            NagLog.e(TAG, "closing roast pick threw, using the hardcoded default line", it)
            Pick(PhraseEngine.defaultPhrase(ROAST_TIER), 0, 0, PoolSource.HARDCODED_DEFAULT)
        }
        val roast = pick.phrase.takeIf { it.text.isNotBlank() } ?: PhraseEngine.defaultPhrase(ROAST_TIER)
        val text = RoastGreeting.compose(roast.text, if (greet) 0 else 1, now)
        NagLog.d(TAG, "closing roast pack=${roast.pack.key} pool=${pick.poolSize} window=${pick.effectiveWindow} source=${pick.source} id=${roast.id} greeting=$greet")
        return ChosenPhrase(roast.id, text, ROAST_TIER, roast.tags)
    }

    private suspend fun pickAndLog(tags: Set<String>, tier: Int): ChosenPhrase {
        val pick = runCatching { engine().pick(tags, tier) }.getOrElse {
            NagLog.e(TAG, "phrase pick threw, using the hardcoded default line", it)
            Pick(PhraseEngine.defaultPhrase(tier), 0, 0, PoolSource.HARDCODED_DEFAULT)
        }
        NagLog.d(TAG, "picked tags=$tags tier=$tier pool=${pick.poolSize} window=${pick.effectiveWindow} source=${pick.source} id=${pick.phrase.id} length=${pick.phrase.length.key}")
        // The engine already guarantees this; checked again because a blank card is the one thing that must never ship.
        val phrase = pick.phrase.takeIf { it.text.isNotBlank() } ?: PhraseEngine.defaultPhrase(tier).also {
            NagLog.e(TAG, "engine returned blank text for id ${NagLog.phrase(pick.phrase.id)}, using the hardcoded default line")
        }
        return ChosenPhrase(phrase.id, phrase.text, phrase.tier, phrase.tags)
    }

    private suspend fun engine(): PhraseEngine = loadLock.withLock {
        engine ?: withContext(Dispatchers.IO) {
            val text = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
                .onFailure { NagLog.e(TAG, "PHRASES FAILED TO LOAD: could not read assets/$ASSET", it) }
                .getOrNull()
            val phrases = PhraseCatalog.parseOrEmpty(text) {
                NagLog.e(TAG, "PHRASES FAILED TO PARSE: assets/$ASSET is invalid (${it.message}). Cards will use the built-in default line.", it)
            }
            PhraseEngine(phrases, recentStore, log = { NagLog.w(TAG, it) }).also {
                if (it.size > 0) NagLog.i(TAG, "loaded ${it.size} nag phrases and ${it.owlSize} owl_mode lines from assets/$ASSET")
            }
        }.also { engine = it }
    }

    companion object {
        private const val TAG = "Phrases"
        const val ASSET = "phrases.json"
        const val ROAST_TIER = 3
    }
}
