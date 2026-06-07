package com.example.keyboard.model

enum class KeyType {
    CHARACTER,
    SPACE,
    DELETE,
    LANGUAGE_SWITCH,
    CLIPBOARD,
    VOICE,
    SHIFT,
    EMOJI,
    ENTER,
    UNKNOWN
}

data class KeyboardKey(
    val label: String,
    val type: KeyType,
    val output: String? = null
)

data class KeyboardLayout(
    val languageCode: String,
    val languageName: String,
    val rows: List<List<KeyboardKey>>
)
