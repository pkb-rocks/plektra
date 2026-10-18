package rocks.pkb.plektra

import android.content.Context

/**
 * Screen trackpad and Trackpad Gesture Suggestions never run together: both off, or exactly
 * one on. Both turn touches into cursor movement and would fight each other.
 *
 * Every path that turns either feature on goes through [TrackpadFeatureExclusivity]: the two
 * settings screens (which ask first, see [enablingNeedsConfirmation]), the tutorial's preset,
 * and a backup restore or an upgrade from a build that allowed both (see [reconcile]).
 */
enum class TrackpadFeature {
    SCREEN_TRACKPAD,
    TRACKPAD_GESTURES;

    val other: TrackpadFeature
        get() = if (this == SCREEN_TRACKPAD) TRACKPAD_GESTURES else SCREEN_TRACKPAD
}

object TrackpadFeatureExclusivity {

    /**
     * Pure rule: turning [feature] on must be confirmed by the user when the other feature is
     * on, because confirming also turns that one off. Turning a feature off never asks.
     */
    fun enablingNeedsConfirmation(feature: TrackpadFeature, otherEnabled: Boolean): Boolean =
        otherEnabled

    /**
     * Pure rule for stored state with both on (a restored backup, or prefs written by a build
     * that only warned): the feature that stays on. Screen trackpad wins because it is inert
     * until its trigger key is held, so keeping it never moves the cursor unexpectedly, while
     * Trackpad Gestures act on every touch with no key held.
     */
    fun featureKeptWhenBothOn(screenTrackpadEnabled: Boolean, trackpadGesturesEnabled: Boolean): TrackpadFeature? =
        if (screenTrackpadEnabled && trackpadGesturesEnabled) TrackpadFeature.SCREEN_TRACKPAD else null

    fun isEnabled(context: Context, feature: TrackpadFeature): Boolean = when (feature) {
        TrackpadFeature.SCREEN_TRACKPAD -> SettingsManager.isScreenTrackpadEnabled(context)
        TrackpadFeature.TRACKPAD_GESTURES -> SettingsManager.getTrackpadGesturesEnabled(context)
    }

    /** Turns [feature] on, switching the other one off first, or turns [feature] off. */
    fun setEnabled(context: Context, feature: TrackpadFeature, enabled: Boolean) {
        if (enabled && isEnabled(context, feature.other)) store(context, feature.other, false)
        store(context, feature, enabled)
    }

    /**
     * Repairs stored state that has both features on. Returns the feature that was turned
     * off, or null when nothing changed. Safe to call on every IME start.
     */
    fun reconcile(context: Context): TrackpadFeature? {
        val kept = featureKeptWhenBothOn(
            screenTrackpadEnabled = SettingsManager.isScreenTrackpadEnabled(context),
            trackpadGesturesEnabled = SettingsManager.getTrackpadGesturesEnabled(context)
        ) ?: return null
        store(context, kept.other, false)
        return kept.other
    }

    private fun store(context: Context, feature: TrackpadFeature, enabled: Boolean) {
        when (feature) {
            TrackpadFeature.SCREEN_TRACKPAD -> SettingsManager.setScreenTrackpadEnabled(context, enabled)
            TrackpadFeature.TRACKPAD_GESTURES -> SettingsManager.setTrackpadGesturesEnabled(context, enabled)
        }
    }
}
