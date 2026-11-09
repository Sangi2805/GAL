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

    fun football(tone: Tone, random: Random = Random.Default): String = pick(football, tone, random)
    fun sayNo(tone: Tone, random: Random = Random.Default): String = pick(no, tone, random)
    fun cheer(tone: Tone, random: Random = Random.Default): String = pick(cheer, tone, random)

    private fun pick(lines: Map<Tone, List<String>>, tone: Tone, random: Random): String =
        lines.getValue(tone).random(random)
}
