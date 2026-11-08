package rocks.pkb.plektra.update

import java.util.concurrent.TimeUnit

/** How often an available update is announced until it is installed. */
enum class UpdateReminderInterval(val persistedValue: String, val repeatMs: Long?) {
    /** One notification per release, when it is first seen. */
    ONCE("once", null),
    DAILY("daily", TimeUnit.DAYS.toMillis(1)),
    EVERY_THREE_DAYS("every_3_days", TimeUnit.DAYS.toMillis(3)),
    WEEKLY("weekly", TimeUnit.DAYS.toMillis(7)),
    OFF("off", null);

    companion object {
        fun fromPersistedValue(value: String?): UpdateReminderInterval? = entries.firstOrNull { it.persistedValue == value }

        /** Nightly builds follow each other quickly, so the next build is the reminder. */
        fun defaultFor(releaseChannel: String): UpdateReminderInterval =
            if (releaseChannel == "nightly") ONCE else EVERY_THREE_DAYS
    }
}

object UpdateReminderPolicy {
    /** Background checks run every few hours; a reminder that is due within this slack is sent now. */
    val CHECK_SLACK_MS: Long = TimeUnit.HOURS.toMillis(2)

    fun shouldNotify(
        interval: UpdateReminderInterval,
        releaseTag: String,
        lastNotifiedTag: String?,
        lastNotifiedAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (interval == UpdateReminderInterval.OFF) return false
        if (releaseTag != lastNotifiedTag) return true
        val repeatMs = interval.repeatMs ?: return false
        return nowMs - lastNotifiedAtMs >= repeatMs - CHECK_SLACK_MS
    }
}
