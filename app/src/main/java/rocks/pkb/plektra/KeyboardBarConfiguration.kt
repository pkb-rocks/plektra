package rocks.pkb.plektra

import android.content.Context

enum class KeyboardBarArrangement { SUGGESTIONS_FIRST, ACTIONS_FIRST, SHARED }

data class KeyboardBarProfile(
    val suggestions: Boolean,
    val actions: Boolean,
    val bottomIndicators: Boolean,
    val androidIndicators: Boolean,
    val inlineIndicators: Boolean
)

/** Content and order are independent of the geometry of the current device. */
data class KeyboardBarConfiguration(
    val suggestions: Boolean,
    val actions: Boolean,
    val bottomIndicators: Boolean,
    val androidIndicators: Boolean,
    val inlineIndicators: Boolean,
    val arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST,
    val slimProfile: KeyboardBarProfile = KeyboardBarProfile(
        suggestions, false, bottomIndicators, androidIndicators, inlineIndicators)
) {
    /**
     * Slim always uses the combined row: its menu buttons live in that row, so a separate actions
     * row would stack a second, full-size bar with the normal profile's buttons.
     */
    fun presentation(slim: Boolean): KeyboardBarPresentation {
        val profile = if (slim) slimProfile else KeyboardBarProfile(
            suggestions, actions, bottomIndicators, androidIndicators, inlineIndicators)
        return KeyboardBarPresentation(profile.suggestions, profile.actions, profile.bottomIndicators,
            profile.inlineIndicators && profile.suggestions, profile.androidIndicators,
            if (slim) KeyboardBarArrangement.SHARED else arrangement)
    }

    companion object {
        private const val PREFIX = "keyboard_bar_slim_"
        fun read(context: Context): KeyboardBarConfiguration {
            val prefs = SettingsManager.getPreferences(context)
            val suggestions = SettingsManager.getSuggestionsEnabled(context)
            val bottom = SettingsManager.getModifierIndicatorShowsBottomStrip(context)
            val android = SettingsManager.getModifierIndicatorShowsMenuBar(context)
            val inline = SettingsManager.getModifierIndicatorShowsStatusBar(context)
            if (!prefs.contains(PREFIX + "suggestions")) {
                setSlimProfile(context, KeyboardBarProfile(suggestions, false, bottom, android, inline))
            }
            // Migration preserves the effective legacy Slim configuration.
            return KeyboardBarConfiguration(suggestions, SettingsManager.areStatusBarVariationsEnabled(context),
                bottom, android, inline,
                KeyboardBarArrangement.entries.firstOrNull {
                    it.name == prefs.getString("keyboard_bar_arrangement", null)
                } ?: KeyboardBarArrangement.SUGGESTIONS_FIRST,
                KeyboardBarProfile(prefs.getBoolean(PREFIX + "suggestions", suggestions),
                    prefs.getBoolean(PREFIX + "actions", false),
                    prefs.getBoolean(PREFIX + "bottom", bottom),
                    prefs.getBoolean(PREFIX + "android", android),
                    prefs.getBoolean(PREFIX + "inline", inline)))
        }

        fun setArrangement(context: Context, arrangement: KeyboardBarArrangement) {
            SettingsManager.getPreferences(context).edit()
                .putString("keyboard_bar_arrangement", arrangement.name).apply()
        }

        fun setSlimProfile(context: Context, profile: KeyboardBarProfile) {
            SettingsManager.getPreferences(context).edit()
                .putBoolean(PREFIX + "suggestions", profile.suggestions)
                .putBoolean(PREFIX + "actions", profile.actions)
                .putBoolean(PREFIX + "bottom", profile.bottomIndicators)
                .putBoolean(PREFIX + "android", profile.androidIndicators)
                .putBoolean(PREFIX + "inline", profile.inlineIndicators).apply()
        }
    }
}

data class KeyboardBarPresentation(
    val suggestions: Boolean,
    val actions: Boolean,
    val bottomIndicators: Boolean,
    val inlineIndicators: Boolean,
    val androidIndicators: Boolean = false,
    val arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST
) {
    val hasPersistentContent: Boolean get() = suggestions || actions || bottomIndicators
}
