package rocks.pkb.plektra.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

internal fun plektraReleasesApiUrl(): String =
    "https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases?per_page=20"

internal fun plektraReleasesPage(): String =
    "https://github.com/${BuildConfig.GITHUB_REPOSITORY}/releases"

private val client = OkHttpClient()
private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

internal data class UpdateCheckResult(
    val successful: Boolean,
    val hasAnnouncement: Boolean = false,
    val releaseTag: String? = null,
    val displayName: String? = null,
    val releasePageUrl: String? = null,
    val downloadUrl: String? = null,
    val isNightlyUpdate: Boolean = false,
    val isPlektraStableUpdate: Boolean = false
)

private enum class ReleaseFeed {
    PLEKTRA_STABLE,
    PLEKTRA_NIGHTLY
}

internal fun checkForUpdate(
    context: Context,
    releaseChannel: String,
    ignoreDismissedReleases: Boolean = true,
    callback: (UpdateCheckResult) -> Unit
) = checkRelease(
    context,
    releaseChannel,
    ignoreDismissedReleases,
    if (releaseChannel.equals("nightly", ignoreCase = true)) {
        ReleaseFeed.PLEKTRA_NIGHTLY
    } else {
        ReleaseFeed.PLEKTRA_STABLE
    },
    callback
)

private fun checkForPlektraStableUpdate(
    context: Context,
    ignoreDismissedReleases: Boolean,
    callback: (UpdateCheckResult) -> Unit
) = checkRelease(
    context,
    "stable",
    ignoreDismissedReleases,
    ReleaseFeed.PLEKTRA_STABLE,
    callback
)


internal fun checkForNightlyUpdate(
    context: Context,
    ignoreDismissedReleases: Boolean = true,
    callback: (UpdateCheckResult) -> Unit
) {
    if (BuildConfig.RELEASE_CHANNEL != "nightly") {
        postResult(callback, UpdateCheckResult(successful = true))
        return
    }
    checkRelease(context, "nightly", ignoreDismissedReleases, ReleaseFeed.PLEKTRA_NIGHTLY, callback)
}

internal fun checkForUpdateNotices(
    context: Context,
    releaseChannel: String,
    ignoreDismissedReleases: Boolean = true,
    callback: (UpdateCheckResult) -> Unit
) {
    checkForUpdate(context, releaseChannel, ignoreDismissedReleases, callback)
}

private fun checkRelease(
    context: Context,
    releaseChannel: String,
    ignoreDismissedReleases: Boolean,
    feed: ReleaseFeed,
    callback: (UpdateCheckResult) -> Unit
) {
    if (!shouldUseGithubUpdateChecks(context)) {
        postResult(callback, UpdateCheckResult(successful = true))
        return
    }

    val request = Request.Builder()
        .url(plektraReleasesApiUrl())
        .header("Accept", "application/vnd.github+json")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            postResult(callback, UpdateCheckResult(successful = false))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { res ->
                if (!res.isSuccessful) {
                    postResult(callback, UpdateCheckResult(successful = false))
                    return
                }

                val body = res.body?.string().orEmpty()
                if (body.isBlank()) {
                    postResult(callback, UpdateCheckResult(successful = false))
                    return
                }

                val latestRelease = try {
                    val releases = parseGitHubReleases(JSONArray(body), BuildConfig.APK_VARIANT)
                    when (feed) {
                        ReleaseFeed.PLEKTRA_NIGHTLY -> findNewerNightlyRelease(releases, BuildConfig.VERSION_NAME)
                        ReleaseFeed.PLEKTRA_STABLE -> findNewerStableRelease(releases, BuildConfig.VERSION_NAME)
                    }
                } catch (_: Exception) {
                    postResult(callback, UpdateCheckResult(successful = false))
                    return
                }
                if (latestRelease == null) {
                    postResult(callback, UpdateCheckResult(successful = true))
                    return
                }

                val releaseTag = latestRelease.tagName
                if (ignoreDismissedReleases) {
                    val dismissalKey = when (feed) {
                        ReleaseFeed.PLEKTRA_NIGHTLY -> "plektra-nightly:$releaseTag"
                        ReleaseFeed.PLEKTRA_STABLE -> "plektra-stable:$releaseTag"
                    }
                    val isDismissed = SettingsManager.isReleaseDismissed(context, dismissalKey)
                    if (isDismissed) {
                        // Release was dismissed, don't show update
                        postResult(callback, UpdateCheckResult(successful = true))
                        return
                    }
                }
                
                postResult(
                    callback,
                    UpdateCheckResult(
                        successful = true,
                        hasAnnouncement = true,
                        releaseTag = releaseTag,
                        displayName = latestRelease.displayName,
                        releasePageUrl = latestRelease.releasePageUrl,
                        downloadUrl = latestRelease.downloadUrl,
                        isNightlyUpdate = feed == ReleaseFeed.PLEKTRA_NIGHTLY,
                        isPlektraStableUpdate = feed == ReleaseFeed.PLEKTRA_STABLE
                    )
                )
            }
        }
    })
}

private fun postResult(
    callback: (UpdateCheckResult) -> Unit,
    result: UpdateCheckResult
) {
    mainHandler.post {
        callback(result)
    }
}

fun showUpdateDialog(
    context: Context,
    releaseTag: String,
    displayName: String,
    releasePageUrl: String?,
    onClosed: () -> Unit = {}
) {
    val dialogTheme = rocks.pkb.plektra.ui.theme.AppThemeResolver.platformAlertDialogThemeRes(
        rocks.pkb.plektra.ui.theme.AppThemeResolver.resolve(context).useDarkTheme
    )
    val dialog = AlertDialog.Builder(context, dialogTheme)
        .setTitle(R.string.successor_dialog_title)
        .setMessage(context.getString(R.string.successor_dialog_message, displayName))
        .setPositiveButton(R.string.successor_dialog_open_release) { _, _ ->
            openUrl(context, normalizeGitHubReleaseUrl(releasePageUrl ?: plektraReleasesPage()))
            onClosed()
        }
        .setNeutralButton(R.string.successor_dialog_later) { _, _ ->
            SettingsManager.addDismissedRelease(context, releaseTag)
            onClosed()
        }
        .create()
    dialog.setOnCancelListener { onClosed() }
    dialog.show()
}

private fun showPlektraStableUpdateDialog(
    context: Context,
    releaseTag: String,
    displayName: String,
    releasePageUrl: String?,
    onClosed: () -> Unit
) {
    val dialogTheme = rocks.pkb.plektra.ui.theme.AppThemeResolver.platformAlertDialogThemeRes(
        rocks.pkb.plektra.ui.theme.AppThemeResolver.resolve(context).useDarkTheme
    )
    val dialog = AlertDialog.Builder(context, dialogTheme)
        .setTitle(R.string.plektra_stable_update_title)
        .setMessage(context.getString(R.string.plektra_stable_update_message, displayName))
        .setPositiveButton(R.string.plektra_stable_update_open) { _, _ ->
            openUrl(context, normalizeGitHubReleaseUrl(releasePageUrl ?: plektraReleasesPage()))
            onClosed()
        }
        .setNeutralButton(R.string.successor_dialog_later) { _, _ ->
            SettingsManager.addDismissedRelease(context, "plektra-stable:$releaseTag")
            onClosed()
        }
        .create()
    dialog.setOnCancelListener { onClosed() }
    dialog.show()
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun showReleaseNotice(context: Context, result: UpdateCheckResult) {
    val tag = result.releaseTag ?: return
    val name = result.displayName ?: return
    val showFollowUp = {}
    if (result.isPlektraStableUpdate) {
        showPlektraStableUpdateDialog(context, tag, name, result.releasePageUrl, showFollowUp)
        return
    }
    if (!result.isNightlyUpdate) {
        showUpdateDialog(context, tag, name, result.releasePageUrl, showFollowUp)
        return
    }
    val dialogTheme = rocks.pkb.plektra.ui.theme.AppThemeResolver.platformAlertDialogThemeRes(
        rocks.pkb.plektra.ui.theme.AppThemeResolver.resolve(context).useDarkTheme
    )
    val builder = AlertDialog.Builder(context, dialogTheme)
        .setTitle(R.string.nightly_update_title)
        .setMessage(context.getString(R.string.nightly_update_message, name))
        .setPositiveButton(R.string.nightly_update_open) { _, _ ->
            openUrl(context, normalizeGitHubReleaseUrl(result.releasePageUrl ?: plektraReleasesPage()))
            showFollowUp()
        }
        .setNeutralButton(R.string.successor_dialog_later) { _, _ ->
            SettingsManager.addDismissedRelease(context, "plektra-nightly:$tag")
            showFollowUp()
        }
    result.downloadUrl?.let { url ->
        builder.setNegativeButton(R.string.nightly_update_download) { _, _ ->
            openUrl(context, url)
            showFollowUp()
        }
    }
    val dialog = builder.create()
    dialog.setOnCancelListener { showFollowUp() }
    dialog.show()
}
