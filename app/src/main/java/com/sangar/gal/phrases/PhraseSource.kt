package com.sangar.gal.phrases

/** Everything known about the moment a nag is about to be shown. */
data class NagRequest(
    val sessionMinutes: Long,
    /** How many cards this session has already shown. 0 means this is the first. */
    val nagsThisSession: Int,
    val foregroundPackage: String?,
    val pack: PhrasePack = PhrasePack.SPICY,
    /**
     * This is the last card the session's cap allows, so it gets a [Tags.GIVE_UP] sign-off instead of
     * another nag. Session trigger only, and only when the cap is actually reached: a session that ends
     * early never gets one.
     */
    val lastOfSession: Boolean = false,
)



data class ChosenPhrase(
    val id: Int,
    val text: String,
    val tier: Int,
    val tags: Set<String>,
)

fun interface PhraseSource {
    suspend fun next(request: NagRequest): ChosenPhrase
}
