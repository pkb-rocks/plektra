package rocks.pkb.plektra.update

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.os.Looper
import android.util.TypedValue
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.NotificationHelper
import rocks.pkb.plektra.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class UpdateAnnouncementUiTest {

    @Test
    fun dialogNamesPlektraAndOnlyOpensTheReleasePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val releaseUrl = "https://github.com/pkb-rocks/plektra-updater-e2e-test/releases/tag/v0.87-e2e.1"

        showUpdateDialog(activity, "v0.87-e2e.1", "TEST ONLY - Plektra successor announcement", releaseUrl)

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.successor_dialog_title), shadowOf(dialog).title)
        assertTrue(shadowOf(dialog).message.toString().contains("Plektra"))
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.isNullOrEmpty())

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(releaseUrl, shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun laterDismissesOnlyTheReleaseTag() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        showUpdateDialog(activity, "v0.87-e2e.1", "Named release", "https://example.com/release")
        ShadowAlertDialog.getLatestAlertDialog()
            .getButton(AlertDialog.BUTTON_NEUTRAL)
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(SettingsManager.isReleaseDismissed(activity, "v0.87-e2e.1"))
        assertFalse(SettingsManager.isReleaseDismissed(activity, "Named release"))
    }

    @Test
    fun notificationNamesPlektraAndOpensTheReleasePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val releaseUrl = "https://example.com/plektra-release"

        NotificationHelper.showUpdateAvailableNotification(
            context = activity,
            displayName = "Plektra 0.87",
            releasePageUrl = releaseUrl
        )

        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(activity.getString(R.string.notification_successor_release_title), notification.extras.getString("android.title"))
        assertTrue(notification.extras.getString("android.text").orEmpty().contains("Plektra 0.87"))

        notification.contentIntent.send()
        assertEquals(releaseUrl, shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun allReleaseDialogsEncodeNightlyTagsAndKeepFallbacks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefix = "https://github.com/pkb-rocks/plektra/releases/tag/"
        val cases = listOf(
            "${prefix}nightly/v26.10?source=dialog#assets" to "${prefix}nightly%2Fv26.10?source=dialog#assets",
            "${prefix}nightly%2Fv26.10" to "${prefix}nightly%2Fv26.10",
            "${prefix}v26.10" to "${prefix}v26.10",
            null to plektraReleasesPage()
        )
        for ((url, expected) in cases) {
            for (kind in listOf("successor", "stable", "nightly")) {
                showReleaseNotice(
                    activity,
                    UpdateCheckResult(
                        successful = true,
                        hasAnnouncement = true,
                        releaseTag = "nightly/v26.10",
                        displayName = "Plektra release",
                        releasePageUrl = url,
                        isNightlyUpdate = kind == "nightly",
                        isPlektraStableUpdate = kind == "stable"
                    )
                )
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals("$kind: $url", expected, intent.data.toString())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            }
        }
    }

    @Test
    fun updateNotificationPendingIntentsEncodeNightlyTagsAndKeepFallbacks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefix = "https://github.com/pkb-rocks/plektra/releases/tag/"
        val cases = listOf(
            "${prefix}nightly/v26.10?source=notification#assets" to "${prefix}nightly%2Fv26.10?source=notification#assets",
            "${prefix}nightly%2Fv26.10" to "${prefix}nightly%2Fv26.10",
            "${prefix}v26.10" to "${prefix}v26.10",
            null to plektraReleasesPage()
        )
        for ((url, expected) in cases) {
            for (kind in listOf("successor", "stable", "nightly")) {
                manager.cancelAll()
                NotificationHelper.showUpdateAvailableNotification(
                    activity, "Plektra release", url,
                    isNightlyUpdate = kind == "nightly",
                    isPlektraStableUpdate = kind == "stable"
                )
                val pendingIntent = shadowOf(manager).allNotifications.single().contentIntent
                assertTrue(shadowOf(pendingIntent).isImmutable)
                pendingIntent.send()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals("$kind: $url", expected, intent.data.toString())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            }
        }
    }

    @Test
    fun nightlyDownloadUrlIsPassedThroughUnchanged() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val downloadUrl = "https://github.com/pkb-rocks/plektra/releases/download/nightly/v26.10/Plektra.apk?download=1#asset"
        showReleaseNotice(
            activity,
            UpdateCheckResult(
                successful = true,
                hasAnnouncement = true,
                releaseTag = "nightly/v26.10",
                displayName = "Plektra Nightly",
                isNightlyUpdate = true,
                downloadUrl = downloadUrl
            )
        )
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(downloadUrl, shadowOf(activity).nextStartedActivity.data.toString())
    }

    private fun isLightDialogTheme(context: Context): Boolean {
        val value = TypedValue()
        assertTrue(context.theme.resolveAttribute(android.R.attr.isLightTheme, value, true))
        return value.data != 0
    }

    @Test
    @Config(qualifiers = "notnight")
    fun updateDialogsFollowForcedDarkAppThemeOnLightOs() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsManager.getPreferences(activity).edit().clear().commit()
        SettingsManager.setAppThemeMode(activity, AppThemeMode.DARK)

        showUpdateDialog(activity, "v26.10", "Plektra 26.10", "https://example.com/release")
        assertFalse(isLightDialogTheme(ShadowAlertDialog.getLatestAlertDialog().context))

        showReleaseNotice(
            activity,
            UpdateCheckResult(
                successful = true,
                hasAnnouncement = true,
                releaseTag = "v26.10-stable",
                displayName = "Plektra 26.10",
                releasePageUrl = "https://example.com/stable",
                isPlektraStableUpdate = true
            )
        )
        assertFalse(isLightDialogTheme(ShadowAlertDialog.getLatestAlertDialog().context))

        showReleaseNotice(
            activity,
            UpdateCheckResult(
                successful = true,
                hasAnnouncement = true,
                releaseTag = "v26.10-nightly",
                displayName = "Plektra Nightly",
                releasePageUrl = "https://example.com/nightly",
                isNightlyUpdate = true
            )
        )
        assertFalse(isLightDialogTheme(ShadowAlertDialog.getLatestAlertDialog().context))
    }

    @Test
    @Config(qualifiers = "night")
    fun updateDialogsFollowForcedLightAppThemeOnDarkOs() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsManager.getPreferences(activity).edit().clear().commit()
        SettingsManager.setAppThemeMode(activity, AppThemeMode.LIGHT)

        showUpdateDialog(activity, "v26.10", "Plektra 26.10", "https://example.com/release")
        assertTrue(isLightDialogTheme(ShadowAlertDialog.getLatestAlertDialog().context))

        showReleaseNotice(
            activity,
            UpdateCheckResult(
                successful = true,
                hasAnnouncement = true,
                releaseTag = "v26.10-stable",
                displayName = "Plektra 26.10",
                releasePageUrl = "https://example.com/stable",
                isPlektraStableUpdate = true
            )
        )
        assertTrue(isLightDialogTheme(ShadowAlertDialog.getLatestAlertDialog().context))
    }
}
