package rocks.pkb.plektra.inputmethod

import rocks.pkb.plektra.R

/**
 * Where Plektra offers its IME switcher, the button that opens Android's input method picker.
 * One decision for every surface; the surfaces only place the result.
 *
 * The ☰ menu always offers the switcher. Switching keyboards is rare, so by default that is all.
 * The button appears directly on the visible surfaces (button row, or the suggestions row
 * without one; SYM pages, emoji picker and clipboard while they replace the bar) when
 *
 * - Android requests it (API 36, `onCustomImeSwitcherButtonRequestedVisible(true)`) because
 *   Plektra hid the caption bar with the system's own switcher. Android then requires the IME
 *   to "show a button within its UI"; an entry in a closed menu is not shown, so the menu
 *   preference yields here; or
 * - the user prefers it in the bar.
 *
 * A surface that the per-app or global hardware-only setting blocks shows no Plektra control at
 * all, the switcher included. The system keeps its own switching paths there.
 */
internal object ImeSwitcherPolicy {
    /** The switcher's glyph on every surface: a small globe, low in emphasis by size. */
    val ICON: Int = R.drawable.ic_ime_switcher

    fun showOnSurfaces(androidRequested: Boolean, preferInBar: Boolean, surfacesBlocked: Boolean): Boolean =
        !surfacesBlocked && (androidRequested || preferInBar)
}
