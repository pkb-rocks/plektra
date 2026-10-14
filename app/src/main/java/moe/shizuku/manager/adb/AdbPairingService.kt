/*
 * Vendored from Shizuku (https://github.com/RikkaApps/Shizuku) by RikkaApps.
 * Licensed under the Apache License, Version 2.0. See
 * app/src/main/java/moe/shizuku/manager/adb/NOTICE and third_party/licenses/Apache-2.0.txt.
 */
package moe.shizuku.manager.adb

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.BrokerAuditLog
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.ui.BrokerStatusMonitor
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import java.net.ConnectException

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "paring_code"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        /**
         * Clean stop-from-caller: delivers [stopAction], which runs
         * stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf so the pairing
         * notification is dismissed. Deliver with startService (foreground only)
         * or, when the caller can't be sure the service is running, stopService.
         */
        fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }
    }

    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Log.i(tag, "Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        EmbeddedAdbInit.ensure()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
                // The pairing code is time-limited and the user is standing in front of the phone
                // waiting for it, so Do Not Disturb swallowing it is never what anyone wants.
                // Android only honours this if the app has notification-policy access; when it
                // does not the flag is ignored, which is why DeviceSetupCard warns as well.
                setBypassDnd(true)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"

            val keyStore = EmbeddedAdbInit.keySource(this@AdbPairingService)
            // loadOrGenerate GENERATES AND STORES a key when none is held, and isPaired() is
            // nothing more than "a key is stored". So an attempt that never gets past the
            // pairing code would otherwise leave the app permanently believing it is paired.
            // Remember whether the key predates this attempt: only a key minted here may be
            // discarded on failure, because an existing one may back a working pairing.
            // Read through the same predicate the rest of the app gates on (it also drops a
            // legacy wrapped key, finding 1.3d, so this pairing mints a Keystore key).
            val keyExistedBeforeAttempt =
                EmbeddedAdbShell.isPaired(this@AdbPairingService)
            val key = try {
                try {
                    AdbKey.loadOrGenerate(keyStore, EmbeddedAdbInit.keyName(packageName))
                } catch (e: AdbKeyException) {
                    // Pairing is the one place a stored key may be replaced: the phone is about
                    // to be told the new public key anyway, so an unreadable old one is dropped.
                    Log.e(tag, "Stored ADB key unreadable, generating a new one for this pairing", e)
                    keyStore.delete()
                    AdbKey.loadOrGenerate(keyStore, EmbeddedAdbInit.keyName(packageName))
                }
            } catch (e: Throwable) {
                Log.e(tag, "Unable to load ADB key", e)
                BrokerAuditLog.record(
                    this@AdbPairingService, BrokerAuditLog.Kind.KEY, "generate", ok = false,
                    detail = e.javaClass.simpleName
                )
                discardUnprovenKey(keyStore, keyExistedBeforeAttempt)
                handleResult(false, e)
                return@launch
            }

            if (!keyExistedBeforeAttempt) {
                BrokerAuditLog.record(
                    this@AdbPairingService, BrokerAuditLog.Kind.KEY, "generated", ok = true,
                    detail = (keyStore as? AndroidKeyStoreAdbKeySource)?.backing
                )
            }

            runCatching {
                AdbPairingClient(host, port, code, key).use { client ->
                    val paired = client.start()
                    if (paired) {
                        // The pairing code just authenticated this server cert as adbd's. Record it
                        // before handleResult, whose privileged setup makes the first connect.
                        val pin = client.serverPin ?: error("adbd presented no certificate")
                        EmbeddedAdbInit.pinStore(this@AdbPairingService)
                            .recordPairing(pin)
                    }
                    paired
                }
            }.onFailure {
                discardUnprovenKey(keyStore, keyExistedBeforeAttempt)
                handleResult(false, it)
            }.onSuccess { paired ->
                if (!paired) discardUnprovenKey(keyStore, keyExistedBeforeAttempt)
                handleResult(paired, null)
            }
        }

        return workingNotification
    }

    /**
     * Forget a key this pairing attempt minted, once the attempt has failed.
     *
     * The phone only trusts a key it accepted during a completed pairing, so a key left behind by
     * a failed attempt is worthless — but it still satisfies [EmbeddedAdbShell.isPaired], which
     * gates every privileged step. A key
     * that predates the attempt is left alone: it may be backing a pairing that still works.
     */
    private fun discardUnprovenKey(keyStore: AdbKeySource, keyExistedBeforeAttempt: Boolean) {
        if (keyExistedBeforeAttempt) return
        Log.w(tag, "Pairing failed; discarding the key it generated so the app does not read as paired")
        keyStore.delete()
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Either way the broker's state just changed, so no screen may keep showing the verdict
        // it cached before this pairing ran.
        BrokerStatusMonitor.invalidate()

        val title: String
        val text: String?

        if (success) {
            Log.i(tag, "Pair succeed")
            BrokerAuditLog.record(this, BrokerAuditLog.Kind.PAIRING, "paired", ok = true)
            // One pairing, every privileged step. The user typing the code is the user action.
            PrivilegedSetup.applyAll(
                this,
                reason = "pairing_succeeded",
                trigger = BrokerTrigger.fromPairingCode(this)
            )

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch()
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is ConnectException -> {
                    getString(R.string.cannot_connect_port)
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong)
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)
                }
                else -> {
                    exception?.let { Log.getStackTraceString(it) }
                }
            }

            BrokerAuditLog.record(
                this, BrokerAuditLog.Kind.PAIRING, "paired", ok = false,
                detail = exception?.javaClass?.simpleName
            )
            if (exception != null) {
                Log.w(tag, "Pair failed", exception)
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.adb_pairing_notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(text)
                /*.apply {
                    if (!success) {
                        addAction(retryNotificationAction)
                    }
                }*/
                .build()
        )
        stopSelf()
    }

    private val stopNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    private val replyNotificationAction by lazy(LazyThreadSafetyMode.NONE) {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // Ensure pending intent is created
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    private val searchingNotification by lazy(LazyThreadSafetyMode.NONE) {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.adb_pairing_notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.adb_pairing_notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by lazy(LazyThreadSafetyMode.NONE) {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.adb_pairing_notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
