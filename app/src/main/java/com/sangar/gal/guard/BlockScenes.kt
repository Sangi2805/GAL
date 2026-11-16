package com.sangar.gal.guard

import com.sangar.gal.phrases.Tone
import kotlin.random.Random

/** What GAL is busy doing on the pause screen while you wait. */
enum class BlockScene { SKIP, FOOTBALL, READ, STRETCH, SWEEP, BED }

object BlockScenes {

    private val DAYTIME = listOf(BlockScene.SKIP, BlockScene.FOOTBALL, BlockScene.READ, BlockScene.STRETCH, BlockScene.SWEEP)

    /** Late at night she always goes to bed. Otherwise any scene except the one you saw last. */
    fun pick(hour: Int, previous: BlockScene?, random: Random = Random.Default): BlockScene {
        if (hour >= 23 || hour < 5) return BlockScene.BED
        val options = DAYTIME.filter { it != previous }
        return options[random.nextInt(options.size)]
    }
}

/**
 * The one line on the pause screen. Short on purpose: testers stopped reading long roasts after a few. Each
 * line is about what she is doing, so it changes with the scene, and none of them depend on the time of day.
 */
object BlockLines {

    private val lines: Map<BlockScene, Map<Tone, List<String>>> = mapOf(
        BlockScene.SKIP to mapOf(
            Tone.CALM to listOf(
                "A little skip. Join me?",
                "Feels good to move.",
                "Ten jumps. Your turn?",
                "Up, down, breathe.",
            ),
            Tone.HONEST to listOf(
                "I'm skipping rope. You're skipping life.",
                "Thirty jumps. You: zero.",
                "One of us is moving.",
                "Only your thumb got exercise.",
            ),
            Tone.BRUTAL to listOf(
                "I jump rope. You jump apps.",
                "I moved more in 10 seconds than you did all day.",
                "Your thumb is fit. The rest, not so much.",
                "Skipping. Try it before your legs forget.",
            ),
        ),
        BlockScene.FOOTBALL to mapOf(
            Tone.CALM to listOf(
                "Fancy a kickabout later?",
                "The ball is real. So is outside.",
                "Play with me for a bit?",
                "A short walk after this?",
            ),
            Tone.HONEST to listOf(
                "At least I am playing a real game.",
                "I scored. You scrolled.",
                "A real game. No ads, no feed.",
                "Football: 1. Your thumb: 400 swipes.",
            ),
            Tone.BRUTAL to listOf(
                "I play. You watch other people live.",
                "One of us touched a ball today.",
                "Your only stat today is screen time.",
                "Even I get out more than you.",
            ),
        ),
        BlockScene.READ to mapOf(
            Tone.CALM to listOf(
                "A few quiet pages. Join me?",
                "Books are nice too.",
                "This one has an ending.",
                "Quiet time. Want some?",
            ),
            Tone.HONEST to listOf(
                "This has an ending. Your feed does not.",
                "Like scrolling, but it finishes.",
                "Chapter three. You: video three hundred.",
                "Words on paper. Remember those?",
            ),
            Tone.BRUTAL to listOf(
                "I read books. You read comments.",
                "A whole chapter while you watched strangers.",
                "Nobody ever regretted closing this app.",
                "Reading. Scary, I know.",
            ),
        ),
        BlockScene.STRETCH to mapOf(
            Tone.CALM to listOf(
                "A good stretch. Your turn?",
                "Your neck would like this too.",
                "Stand up with me?",
                "Shoulders down. Breathe.",
            ),
            Tone.HONEST to listOf(
                "Your neck called. It wants this.",
                "Stand up. I will wait.",
                "You have not moved in a while.",
                "Look up. There is a room around you.",
            ),
            Tone.BRUTAL to listOf(
                "You are turning into your chair.",
                "Your spine sent me to complain.",
                "Move. Something. Anything.",
                "That posture is a cry for help.",
            ),
        ),
        BlockScene.SWEEP to mapOf(
            Tone.CALM to listOf(
                "Just tidying. Shall we move on?",
                "A little clean-up. Done here?",
                "Sweeping up. Maybe close this one?",
                "Time to tidy the day.",
            ),
            Tone.HONEST to listOf(
                "Tidying up. Starting with this app.",
                "Sweeping the feed away. You're welcome.",
                "Time's up. Out it goes.",
                "Nothing new in there. I checked.",
            ),
            Tone.BRUTAL to listOf(
                "Taking out the rubbish. This app first.",
                "I am sweeping you out with it.",
                "Same feed, same you. Out.",
                "You were told. Out.",
            ),
        ),
        BlockScene.BED to mapOf(
            Tone.CALM to listOf(
                "I am off to bed. You too, soon?",
                "Goodnight. The phone can wait.",
                "Sleepy. Shall we both stop?",
                "Tomorrow will thank you.",
            ),
            Tone.HONEST to listOf(
                "I am going to bed. One of us should.",
                "It is late. Even I know that.",
                "Goodnight. Try it some time.",
                "Nothing good gets posted at this hour.",
            ),
            Tone.BRUTAL to listOf(
                "Bed. You will regret this hour tomorrow.",
                "I sleep. You scroll. Guess who wins.",
                "Still up? Nothing here is worth it.",
                "Your alarm is laughing at you.",
            ),
        ),
    )

    fun all(scene: BlockScene, tone: Tone): List<String> = lines.getValue(scene).getValue(tone)

    fun line(scene: BlockScene, tone: Tone, random: Random = Random.Default): String = all(scene, tone).random(random)

    /** The examples on the setup screen, one per tone. */
    fun example(tone: Tone): String = when (tone) {
        Tone.CALM -> "Time for a little break?"
        Tone.HONEST -> "30 minutes gone. Was it worth it?"
        Tone.BRUTAL -> "Your thumb ran a marathon. You did not."
    }
}
