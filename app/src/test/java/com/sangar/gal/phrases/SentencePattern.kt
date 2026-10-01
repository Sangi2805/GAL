package com.sangar.gal.phrases

/**
 * The shape of a sentence: linking words and punctuation are kept, every other word becomes "_".
 * Mirrors pattern_of() in tools/phrases/build_phrases.py; keep the two in step.
 */
object SentencePattern {
    const val MAX_PER_PATTERN = 15

    private val keptWords = """
        a an the this that these those
        i me my mine you your yours he him his she her it its we us our they them their
        is am are was were be been being do does did done have has had having
        will would can could shall should may might must
        and or but nor so yet if then than as because while when where how what who whom which why
        of to in on at by for with from up down out off over under into onto about after before through
        not no nothing nobody none any some every each all more most less least very too just even only still
        please here there now today again ever never always
    """.trim().split(Regex("\\s+")).toSet()

    private val token = Regex("[A-Za-z']+|[^\\sA-Za-z']")
    private const val KEPT_PUNCTUATION = ".,?!:;"

    fun of(text: String): String = token.findAll(text.lowercase()).mapNotNull { match ->
        val tok = match.value
        when {
            tok[0].isLetter() || tok[0] == '\'' -> if (tok in keptWords) tok else "_"
            tok[0] in KEPT_PUNCTUATION -> tok
            else -> null
        }
    }.joinToString(" ")
}
