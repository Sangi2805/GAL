package com.sangar.gal.phrases

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random

/** Where recently shown phrase ids live between app restarts. Oldest first. */
interface RecentIdStore {
    suspend fun load(): List<Int>
    suspend fun save(ids: List<Int>)
}

class InMemoryRecentIdStore(initial: List<Int> = emptyList()) : RecentIdStore {
    private var ids = initial
    override suspend fun load() = ids
    override suspend fun save(ids: List<Int>) {
        this.ids = ids
    }
}

/** Which step of the fallback chain produced the phrase. */
enum class PoolSource { MATCHED, GENERAL_SAME_TIER, GENERAL_ANY_TIER, HARDCODED_DEFAULT }

data class Pick(
    val phrase: Phrase,
    val poolSize: Int,
    val effectiveWindow: Int,
    val source: PoolSource,
)

/**
 * Chooses the next phrase, and can never return an empty one.
 *
 * Fallback chain, first non-empty pool wins:
 * 1. the requested tier, phrases carrying any of the requested tags
 * 2. the requested tier, phrases carrying `general`
 * 3. any tier, phrases carrying `general`
 * 4. a hardcoded default line
 *
 * Phrases with blank text or an unknown tier are dropped when the engine is built, so no pool can hold one.
 *
 * Recently shown ids are skipped using a window sized to the pool that was actually used:
 * `min(150, poolSize / 2)`. A fixed window of 150 would exclude every member of a small pool and starve it.
 *
 * Length comes last: among the candidates left after tags, tier and the recent window, a length is drawn
 * with [LengthMix] weights for the moment (short for everyday cards, longer for marathons and new records),
 * renormalised over the lengths actually available, then a phrase of that length. Tags always decide first.
 */
class PhraseEngine(
    phrases: List<Phrase>,
    private val recentStore: RecentIdStore,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val valid: List<Phrase> = phrases.filter { it.text.isNotBlank() && it.tier in Tags.TIERS }

    /**
     * Everything a spicy card may use. owl_mode, app_roast and roast lines are excluded here, so no fallback can
     * reach them.
     */
    private val usable: List<Phrase> =
        valid.filter { Tags.OWL_MODE !in it.tags && Tags.APP_ROAST !in it.tags && it.pack == PhrasePack.SPICY }

    /** Sidekick's lines for opening a social app you use a lot. Tiered, never on a card. */
    private val appRoasts: List<Phrase> = valid.filter { Tags.APP_ROAST in it.tags }
    private val appRoastsByTier: Map<Int, List<Phrase>> = appRoasts.groupBy { it.tier }

    /** The "You may cry" roasts: one flat pool, no tiers or moment tags. Their sign-offs are kept apart. */
    private val roasts: List<Phrase> =
        valid.filter { Tags.OWL_MODE !in it.tags && Tags.GIVE_UP !in it.tags && it.pack == PhrasePack.CRY }

    /** The "You may cry" give-up lines: only the session's last card may draw one. */
    private val cryGiveUps: List<Phrase> = valid.filter { Tags.GIVE_UP in it.tags && it.pack == PhrasePack.CRY }
    private val owlLines: List<Phrase> = valid.filter { Tags.OWL_MODE in it.tags }
    private val byTier: Map<Int, List<Phrase>> = usable.groupBy { it.tier }
    private val mutex = Mutex()
    private var recent: ArrayDeque<Int>? = null
    private val recentOwl = ArrayDeque<Int>()

    init {
        val dropped = phrases.size - valid.size
        if (dropped > 0) log("dropped $dropped unusable phrase(s) with blank text or an invalid tier")
        if (usable.isEmpty()) log("no usable phrases at all; every pick will use the hardcoded default line")
    }

    val size: Int get() = usable.size

    val owlSize: Int get() = owlLines.size

    val roastSize: Int get() = roasts.size

    fun sizeOf(pack: PhrasePack): Int = if (pack == PhrasePack.CRY) roasts.size else usable.size

    /** A few random lines from a pack for the home screen, without touching the recent-id window. */
    fun preview(pack: PhrasePack, count: Int): List<Phrase> =
        (if (pack == PhrasePack.CRY) roasts else usable).shuffled(random).take(count)

    /**
     * The next roast. Same recent-id window and length mix as [pick]; if the roast pool is missing (a broken
     * or old phrases.json), falls back to a normal spicy pick so the card is still never empty.
     */
    suspend fun pickRoast(tags: Set<String>, tier: Int): Pick {
        val cleanTags = tags - Tags.GIVE_UP
        if (roasts.isEmpty()) {
            log("no roast lines loaded; using a spicy line instead")
            return pick(cleanTags, tier)
        }
        return mutex.withLock { drawFrom(roasts, cleanTags, PoolSource.MATCHED) }
    }

    /**
     * The "You may cry" session's last card: the app giving up on you, in the roast pack's voice. Draws from
     * the pack's own give_up lines with the usual recent-id window. If the pack has none (an old or broken
     * phrases.json) it falls back to a spicy give_up line, then to the hardcoded default, so the last card is
     * never empty and never an ordinary roast pretending to be a sign-off.
     */
    suspend fun pickCryGiveUp(tier: Int): Pick {
        if (cryGiveUps.isEmpty()) {
            log("no You may cry give_up lines loaded; using a spicy give_up line instead")
            return pick(NagContext.GIVE_UP_TAGS, tier)
        }
        return mutex.withLock { drawFrom(cryGiveUps, NagContext.GIVE_UP_TAGS, PoolSource.MATCHED) }
    }

    val cryGiveUpSize: Int get() = cryGiveUps.size

    val appRoastSize: Int get() = appRoasts.size

    /**
     * A line for smashing open a social app used a lot, still holding the {app} placeholder. Same tier if there
     * is one, any tier otherwise, and a hardcoded line if the file has none, so it is never empty.
     */
    suspend fun pickAppRoast(tier: Int): Pick {
        val sameTier = appRoastsByTier[tier].orEmpty()
        val (pool, source) = when {
            sameTier.isNotEmpty() -> sameTier to PoolSource.MATCHED
            appRoasts.isNotEmpty() -> appRoasts to PoolSource.GENERAL_ANY_TIER
            else -> return Pick(Phrase(DEFAULT_PHRASE_ID, DEFAULT_APP_ROAST, tier, setOf(Tags.APP_ROAST)), 0, 0, PoolSource.HARDCODED_DEFAULT)
        }
        if (source != PoolSource.MATCHED) log("app roast fallback $source fired for tier=$tier")
        return mutex.withLock { drawFrom(pool, setOf(Tags.APP_ROAST), source) }
    }

    /**
     * An owl_mode line for a confirmed threshold. Tier comes from the threshold. Falls back to any owl line,
     * then to a hardcoded one, so it is never empty either. Avoids repeating the last few lines shown.
     */
    suspend fun pickOwl(thresholdMinutes: Int): Pick = mutex.withLock {
        val tier = OwlMode.tierFor(thresholdMinutes)
        val sameTier = owlLines.filter { it.tier == tier }
        val (pool, source) = when {
            sameTier.isNotEmpty() -> sameTier to PoolSource.MATCHED
            owlLines.isNotEmpty() -> owlLines to PoolSource.GENERAL_ANY_TIER
            else -> emptyList<Phrase>() to PoolSource.HARDCODED_DEFAULT
        }
        if (source != PoolSource.MATCHED) log("owl fallback $source fired for threshold=${thresholdMinutes}min tier=$tier")
        if (pool.isEmpty()) {
            return@withLock Pick(Phrase(DEFAULT_PHRASE_ID, OwlMode.DEFAULT_TEXT, tier, setOf(Tags.OWL_MODE)), 0, 0, source)
        }
        val window = min(OWL_WINDOW, pool.size / 2)
        val excluded = recentOwl.takeLast(window).toHashSet()
        val phrase = pool.filter { it.id !in excluded }.ifEmpty { pool }.let { it[random.nextInt(it.size)] }
        recentOwl.addLast(phrase.id)
        while (recentOwl.size > OWL_WINDOW) recentOwl.removeFirst()
        Pick(phrase, pool.size, window, source)
    }

    /** The pool the filter step produces, before any fallback. Exposed for tests and diagnostics. */
    fun matchedPool(tags: Set<String>, tier: Int): List<Phrase> =
        byTier[tier].orEmpty().filter { phrase -> phrase.tags.any { it in tags } }

    fun effectiveWindow(poolSize: Int): Int = min(MAX_WINDOW, poolSize / 2)

    suspend fun pick(tags: Set<String>, tier: Int): Pick = mutex.withLock {
        val (pool, source) = choosePool(tags, tier)
        if (source != PoolSource.MATCHED) {
            log("fallback $source fired for tags=$tags tier=$tier (pool ${pool.size})")
        }
        if (pool.isEmpty()) {
            return@withLock Pick(defaultPhrase(tier), poolSize = 0, effectiveWindow = 0, source = PoolSource.HARDCODED_DEFAULT)
        }
        drawFrom(pool, tags, source)
    }

    /** Recent-id window, then a length for the moment, then a phrase. Call with [mutex] held. */
    private suspend fun drawFrom(pool: List<Phrase>, tags: Set<String>, source: PoolSource): Pick {
        return drawUnseen(pool, tags, source, excludeFullBuffer = false) ?: run {
            val buffer = recent!! // Guaranteed non-null by drawUnseen
            val candidates = pool
            val byLength = candidates.groupBy { it.length }
            val length = drawLength(LengthMix.weightsFor(tags), byLength.keys)
            val sameLength = length?.let { byLength[it] } ?: candidates
            val phrase = sameLength[random.nextInt(sameLength.size)]

            buffer.addLast(phrase.id)
            while (buffer.size > MAX_WINDOW) buffer.removeFirst()
            runCatching { recentStore.save(buffer.toList()) }.onFailure { log("could not persist the ring buffer: ${it.message}") }

            Pick(phrase, pool.size, effectiveWindow(pool.size), source)
        }
    }

    /** 
     * Draws a phrase from [pool] excluding seen items. Returns null if the pool is exhausted.
     * When [excludeFullBuffer] is true, the exclusion window ignores scaling and excludes all recent items.
     */
    private suspend fun drawUnseen(pool: List<Phrase>, tags: Set<String>, source: PoolSource, excludeFullBuffer: Boolean): Pick? {
        val buffer = recent ?: ArrayDeque(runCatching { recentStore.load() }.getOrDefault(emptyList()).takeLast(MAX_WINDOW))
            .also { recent = it }
        val window = if (excludeFullBuffer) MAX_WINDOW else effectiveWindow(pool.size)
        val excluded = buffer.takeLast(window).toHashSet()
        val candidates = pool.filter { it.id !in excluded }
        if (candidates.isEmpty()) return null

        val byLength = candidates.groupBy { it.length }
        val length = drawLength(LengthMix.weightsFor(tags), byLength.keys)
        val sameLength = length?.let { byLength[it] } ?: candidates
        val phrase = sameLength[random.nextInt(sameLength.size)]

        buffer.addLast(phrase.id)
        while (buffer.size > MAX_WINDOW) buffer.removeFirst()
        runCatching { recentStore.save(buffer.toList()) }.onFailure { log("could not persist the ring buffer: ${it.message}") }

        return Pick(phrase, pool.size, window, source)
    }

    /** Forgets everything shown so far, e.g. after "wipe all data". */
    suspend fun resetRecent() = mutex.withLock {
        recent = ArrayDeque()
        runCatching { recentStore.save(emptyList()) }
    }

    /** A length present in [available], by weight. Null when no available length has any weight. */
    private fun drawLength(weights: LengthMix.Weights, available: Set<PhraseLength>): PhraseLength? {
        val options = PhraseLength.entries.filter { it in available && weights.of(it) > 0.0 }
        val total = options.sumOf { weights.of(it) }
        if (total <= 0.0) return null
        var roll = random.nextDouble() * total
        for (option in options) {
            roll -= weights.of(option)
            if (roll < 0.0) return option
        }
        return options.last()
    }

    private fun choosePool(tags: Set<String>, tier: Int): Pair<List<Phrase>, PoolSource> {
        matchedPool(tags, tier).takeIf { it.isNotEmpty() }?.let { return it to PoolSource.MATCHED }
        byTier[tier].orEmpty().filter { Tags.GENERAL in it.tags }.takeIf { it.isNotEmpty() }
            ?.let { return it to PoolSource.GENERAL_SAME_TIER }
        usable.filter { Tags.GENERAL in it.tags }.takeIf { it.isNotEmpty() }
            ?.let { return it to PoolSource.GENERAL_ANY_TIER }
        return emptyList<Phrase>() to PoolSource.HARDCODED_DEFAULT
    }

    companion object {
        const val MAX_WINDOW = 150
        private const val OWL_WINDOW = 10
        const val DEFAULT_PHRASE_ID = 0
        const val DEFAULT_TEXT = "Hey. Look up for a second. The rest of the world is still there."
        const val DEFAULT_APP_ROAST = "Are you married to {app}?"

        fun defaultPhrase(tier: Int) = Phrase(
            id = DEFAULT_PHRASE_ID,
            text = DEFAULT_TEXT,
            tier = tier.coerceIn(Tags.TIERS.first, Tags.TIERS.last),
            tags = setOf(Tags.GENERAL),
        )
    }
}
