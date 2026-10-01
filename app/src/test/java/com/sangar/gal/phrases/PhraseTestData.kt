package com.sangar.gal.phrases

import java.io.File

/** Loads the real asset. Gradle runs unit tests with the module directory as the working directory. */
object PhraseTestData {
    val rawJson: String by lazy {
        val file = listOf(File("src/main/assets/phrases.json"), File("app/src/main/assets/phrases.json"))
            .firstOrNull { it.exists() } ?: error("phrases.json not found from ${File(".").absolutePath}")
        file.readText()
    }

    val phrases: List<Phrase> by lazy { PhraseCatalog.parse(rawJson) }
}
