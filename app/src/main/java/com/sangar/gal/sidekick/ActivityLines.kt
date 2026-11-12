package com.sangar.gal.sidekick

import com.sangar.gal.phrases.Tone
import kotlin.random.Random

/** The short things GAL says about her own activities, in each tone. Kept to a few words: they sit in a bubble. */
object ActivityLines {

    private val football = mapOf(
        Tone.CALM to listOf(
            "Fancy a kickabout later?",
            "Goal! Want to play outside after this?",
            "That felt good. Your turn to move?",
            "One goal for me. A short walk for you?",
            "The ball is real, you know. So is outside.",
            "I scored. You could stretch, at least.",
        ),
        Tone.HONEST to listOf(
            "At least I am playing a real game.",
            "Goal. What did you do in the last minute?",
            "One nil to me. You have not moved.",
            "Look, exercise. Heard of it?",
            "I scored. You scrolled.",
            "A real game. No ads, no feed.",
            "Football: 1. Your thumb: 400 swipes.",
        ),
        Tone.BRUTAL to listOf(
            "I have scored more today than you have achieved.",
            "Goal. That is one more than your whole week.",
            "Even I get out more than you do.",
            "I play. You watch other people live.",
            "At least one of us touched a ball today.",
            "Scored. Your only stat today is screen time.",
        ),
    )

    private val no = mapOf(
        Tone.CALM to listOf("Maybe not this one?", "Again? Just a quick look, then.", "Hmm. Are you sure?"),
        Tone.HONEST to listOf("Nooo. Not this again.", "This app? Really?", "Oh no. Here we go."),
        Tone.BRUTAL to listOf("Nooo. Have some dignity.", "This again? Embarrassing.", "Put it down. Now."),
    )

    private val cheer = mapOf(
        Tone.CALM to listOf("Nice choice.", "That is a good one.", "I like this for you."),
        Tone.HONEST to listOf("Finally, something useful.", "Now we are talking.", "Look at you, being productive."),
        Tone.BRUTAL to listOf("A useful app? Who are you?", "Shocking. Keep going.", "Miracles happen."),
    )

    private val reading = mapOf(
        Tone.CALM to listOf("A few pages before bed. Join me?", "Books are nice at this hour.", "Quiet time. Want to try it?"),
        Tone.HONEST to listOf("This has an ending. Your feed does not.", "Reading. It is like scrolling, but it finishes.", "Chapter three. You: video three hundred."),
        Tone.BRUTAL to listOf("I read books. You read comments.", "A whole chapter while you watched strangers.", "Words on paper. Scary, I know."),
    )

    private val skipping = mapOf(
        Tone.CALM to listOf("Morning stretch. Coming?", "A little skip to start the day.", "Feels good. Try a walk?"),
        Tone.HONEST to listOf("Thirty jumps. You: zero.", "Morning exercise. One of us is doing it.", "Your thumb is the only thing moving."),
        Tone.BRUTAL to listOf("I have moved more this morning than you will all day.", "Jumping rope. You are jumping apps.", "Up for five minutes and already on your phone."),
    )

    private val bedtime = mapOf(
        Tone.CALM to listOf("I am off to bed. You too, soon?", "Goodnight. The phone can wait.", "Sleepy. Shall we both stop?"),
        Tone.HONEST to listOf("I am going to bed. One of us should.", "It is late. Even I know that.", "Goodnight. Try it some time."),
        Tone.BRUTAL to listOf("Bed. Tomorrow you will regret this hour.", "I sleep. You scroll. Guess who wins tomorrow.", "Still up? Nothing here is worth it."),
    )

    private val stretching = mapOf(
        Tone.CALM to listOf("A good stretch. Your turn?", "Your neck would like this too.", "Stand up with me for a moment?"),
        Tone.HONEST to listOf("Your neck called. It wants this.", "Stretching. You have not moved in a while.", "Stand up. I will wait."),
        Tone.BRUTAL to listOf("You are turning into the shape of your chair.", "Move. Something. Anything.", "Your spine sent me to complain."),
    )

    private val sweeping = mapOf(
        Tone.CALM to listOf("Just tidying. Maybe close this one?", "A little clean-up. This app again?", "Sweeping up. Shall we move on?"),
        Tone.HONEST to listOf("Tidying up. Starting with this app.", "Third time today. Out it goes.", "Sweeping the feed away. You are welcome."),
        Tone.BRUTAL to listOf("Taking out the rubbish. This app first.", "Again? I am sweeping you out with it.", "Three visits. Nothing new. Out."),
    )

    private val plane = mapOf(
        Tone.CALM to listOf("For you. Well done.", "A little reward.", "Outside is that way, later."),
        Tone.HONEST to listOf("Have a plane. You earned it.", "Outside is that way.", "Useful app, small prize."),
        Tone.BRUTAL to listOf("A prize. Do not get used to it.", "Rare event. Have a plane.", "Outside is that way. Try it."),
    )

    private val dropped = mapOf(
        Tone.CALM to listOf("Oh. Hello.", "Here is fine too.", "A lift. Thank you."),
        Tone.HONEST to listOf("Excuse me.", "I was standing there.", "Rude."),
        Tone.BRUTAL to listOf("Hands off.", "Do you grab everyone like that?", "Put that energy into something useful."),
    )

    fun stretching(tone: Tone, random: Random = Random.Default): String = pick(stretching, tone, random)
    fun sweeping(tone: Tone, random: Random = Random.Default): String = pick(sweeping, tone, random)
    fun plane(tone: Tone, random: Random = Random.Default): String = pick(plane, tone, random)
    fun dropped(tone: Tone, random: Random = Random.Default): String = pick(dropped, tone, random)

    /** The question when a social app opens, the reply to an answer, and the call when the time is up. */
    fun howLong(tone: Tone): String = when (tone) {
        Tone.CALM -> "How long would you like here?"
        Tone.HONEST -> "Nooo. Fine. How long this time?"
        Tone.BRUTAL -> "This again. How long will you waste?"
    }

    fun counting(tone: Tone, minutes: Int): String = when (tone) {
        Tone.CALM -> "$minutes minutes. I will let you know."
        Tone.HONEST -> "$minutes minutes. I am counting."
        Tone.BRUTAL -> "$minutes minutes. Not one more."
    }

    fun timeUp(tone: Tone, minutes: Int): String = when (tone) {
        Tone.CALM -> "That was your $minutes minutes. Shall we stop?"
        Tone.HONEST -> "Your $minutes minutes are up. You said so yourself."
        Tone.BRUTAL -> "$minutes minutes, gone. Out. You promised."
    }

    fun leftEarly(tone: Tone): String = when (tone) {
        Tone.CALM -> "You left early. Lovely."
        Tone.HONEST -> "Out before time. Respect."
        Tone.BRUTAL -> "Left early? Who are you?"
    }

    fun reading(tone: Tone, random: Random = Random.Default): String = pick(reading, tone, random)
    fun skipping(tone: Tone, random: Random = Random.Default): String = pick(skipping, tone, random)
    fun bedtime(tone: Tone, random: Random = Random.Default): String = pick(bedtime, tone, random)

    fun football(tone: Tone, random: Random = Random.Default): String = pick(football, tone, random)
    fun sayNo(tone: Tone, random: Random = Random.Default): String = pick(no, tone, random)
    fun cheer(tone: Tone, random: Random = Random.Default): String = pick(cheer, tone, random)

    private fun pick(lines: Map<Tone, List<String>>, tone: Tone, random: Random): String =
        lines.getValue(tone).random(random)
}
