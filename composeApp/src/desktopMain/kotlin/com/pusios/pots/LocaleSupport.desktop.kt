package com.pusios.pots

actual fun applyAppLocale(languageTag: String) {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(languageTag))
}
