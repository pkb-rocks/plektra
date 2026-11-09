package rocks.pkb.plektra

/** Keep the originating editor with the request; local Kotlin function references may compare
 * equal despite capturing different targets. The editor is keyed by target/draft and its import
 * coroutine is cancelled when that owner leaves composition. */
internal class ThemePhotoImportRequest(
    private val original: KeyboardThemePreset,
    private val applyToOwner: (KeyboardThemePreset) -> Unit
) {
    fun complete(current: KeyboardThemePreset, image: KeyboardThemeTexture): Boolean {
        if (current != original) return false
        applyToOwner(original.copy(textures = original.textures + ("background" to image)))
        return true
    }
}
