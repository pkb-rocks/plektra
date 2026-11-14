package rocks.pkb.plektra.ring

import rocks.pkb.plektra.SettingsManager

/**
 * How long the ring holds the screen on, kept free of Android so it can be tested as plain code.
 *
 * new in the port. PhysiBoard multiplied the stored minutes inline; a restored backup can
 * carry any int, and a zero or negative value would have expired the ring the moment it appeared.
 */
object RingDuration {

    /** How long "Try it" holds the screen, whatever the setting says. */
    const val DEMO_MS = 8_000L

    private const val MS_PER_MINUTE = 60_000L

    fun holdMillis(minutes: Int, demo: Boolean): Long =
        if (demo) DEMO_MS else clampMinutes(minutes) * MS_PER_MINUTE

    fun clampMinutes(minutes: Int): Int =
        minutes.coerceIn(
            SettingsManager.NOTIFICATION_RING_MIN_MINUTES,
            SettingsManager.NOTIFICATION_RING_MAX_MINUTES
        )
}
