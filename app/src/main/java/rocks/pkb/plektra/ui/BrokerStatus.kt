package rocks.pkb.plektra.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one place the app decides whether the ADB broker actually works.
 *
 * Every screen used to answer this for itself, and the answers disagreed: the home tile could read
 * "needs pairing" while the toolbox on the very next screen said the tools could reach the system.
 * Two independent checks against a device whose state moves between them will always be able to
 * drift, so there is one verdict here and every surface shows it.
 *
 * Checking costs an mDNS discovery plus a connect, so a verdict is reused for [FRESH_FOR_MS] and
 * concurrent callers await the in-flight check instead of starting their own. That sharing is what
 * keeps two screens consistent — being cheaper is the side benefit.
 */
object BrokerStatusMonitor {

    /** How long a verdict is treated as current before another request re-checks. */
    private const val FRESH_FOR_MS = 10_000L

    private val _status = MutableStateFlow<EmbeddedAdbShell.BrokerStatus?>(null)

    /** The current verdict, or null before the first check has landed. */
    val status: StateFlow<EmbeddedAdbShell.BrokerStatus?> = _status.asStateFlow()

    private val refreshMutex = Mutex()

    private val _invalidations = MutableStateFlow(0)

    /**
     * Counts [invalidate] calls. A screen keys its re-check on this: an invalidation can land while
     * the user is away from the app (pairing finishes on Android's Wireless debugging page), the
     * refresh then has no resumed screen to run for, and nothing else would change to make the
     * screen ask again.
     */
    val invalidations: StateFlow<Int> = _invalidations.asStateFlow()

    @Volatile
    private var lastCheckedAt = 0L

    @Volatile
    private var seeded = false

    /**
     * Show the last persisted verdict immediately, so a screen never opens blank. Only a problem
     * verdict: a stored OK is exactly what a screen must not claim before a check has run (the
     * phone may have been unpaired or adbd restarted since), so OK stays "checking".
     */
    @Synchronized
    fun seed(context: Context) {
        if (seeded) return
        seeded = true
        if (_status.value == null) {
            _status.value = PrivilegedDiagnostics.lastBrokerStatus(context)?.first
                ?.takeIf { it != EmbeddedAdbShell.BrokerStatus.OK }
        }
    }

    /**
     * Re-check unless a recent verdict can be reused. Suspends while a check is in flight, so a
     * second screen asking at the same moment receives that same answer rather than racing it.
     */
    suspend fun refresh(context: Context, trigger: BrokerTrigger?, force: Boolean = false) {
        val appContext = context.applicationContext
        // No trigger (the screen is not resumed): keep the last verdict rather than connect.
        trigger ?: return
        refreshMutex.withLock {
            val age = System.currentTimeMillis() - lastCheckedAt
            if (!force && lastCheckedAt != 0L && age < FRESH_FOR_MS) return
            val generation = _invalidations.value
            val result = withContext(Dispatchers.IO) { EmbeddedAdbShell.verify(appContext, trigger) }
            // Invalidated while the check ran: it describes the state from before, so publishing it
            // would put a stale verdict back. The status stays unknown, and the bump of
            // [invalidations] already makes every open screen ask again.
            if (_invalidations.value != generation) return
            lastCheckedAt = System.currentTimeMillis()
            PrivilegedDiagnostics.recordBrokerStatus(appContext, result)
            _status.value = result
        }
    }

    /**
     * Runs [block] (forgetting the pairing) while no check can connect. A check started in the
     * middle of an unpair talks to adbd with a key it has just removed, fails the TLS handshake and
     * audits that failure as if something were wrong; waiting here, it runs after the key is gone
     * and answers NOT_PAIRED without connecting. [block] must not call [refresh].
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = refreshMutex.withLock { block() }

    /**
     * Drop the cached verdict after something that invalidates it — forgetting a pairing, pairing
     * again, or a changed permission. Without this the stale answer would
     * stand for up to [FRESH_FOR_MS]. Every screen re-checks as soon as it is resumed.
     */
    fun invalidate() {
        lastCheckedAt = 0L
        _status.value = null
        _invalidations.update { it + 1 }
    }
}

/**
 * The verified broker status for any screen that shows it, shared through [BrokerStatusMonitor].
 *
 * Never read [EmbeddedAdbShell.isPaired] to decide what to tell the user: it answers "is a key
 * stored", which is written the moment a pairing is ATTEMPTED and survives Wireless debugging
 * being turned off, so it reports a working setup in two cases where nothing can connect at all.
 *
 * @param refreshKey change it to force a fresh check (e.g. after a re-pair).
 */
@Composable
fun rememberVerifiedBrokerStatus(refreshKey: Any = Unit): State<EmbeddedAdbShell.BrokerStatus?> {
    val context = LocalContext.current
    // Wireless debugging is the setting most likely to change while a screen is open, and the one
    // that silently breaks an otherwise good pairing, so it is polled and drives a re-check.
    var wirelessOn by remember { mutableStateOf(EmbeddedAdbShell.isWirelessDebuggingEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            wirelessOn = EmbeddedAdbShell.isWirelessDebuggingEnabled(context)
        }
    }
    // Keyed on [BrokerStatusMonitor.invalidations] too, and run only while RESUMED: a pairing
    // invalidates the verdict while the user is on another app's page, where a check
    // has no screen to run for. Without the resume hook the card stayed on "Checking…" forever.
    val invalidations by BrokerStatusMonitor.invalidations.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(refreshKey, wirelessOn, invalidations) {
        // Seeding is a side effect, so it belongs here rather than in a remember block.
        BrokerStatusMonitor.seed(context)
        var changed = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A changed key always re-verifies; a bare return to the screen only when there is no
            // verdict (the refresh was dropped while away), else the fresh one is reused.
            val force = changed || BrokerStatusMonitor.status.value == null
            changed = false
            // Only while the broker's screen is open and resumed, and only to verify.
            val trigger = awaitScreenTrigger(context, "broker_screen")
            BrokerStatusMonitor.refresh(context, trigger, force = force)
        }
    }
    return BrokerStatusMonitor.status.collectAsState()
}

/**
 * A [BrokerTrigger.Kind.SCREEN] trigger for a read a screen runs while it is open (read-only commands
 * only, see [rocks.pkb.plektra.inputmethod.PrivilegedCommand.readOnly]). The
 * first frame can land just before onResume, so this waits up to 3 s for it; null when the screen
 * never resumed (then the read does not run). [source] is a constant naming the screen.
 */
suspend fun awaitScreenTrigger(context: Context, source: String): BrokerTrigger? {
    var trigger = BrokerTrigger.forScreen(context, source)
    var waitedMs = 0L
    while (trigger == null && waitedMs < 3_000) {
        delay(100)
        waitedMs += 100
        trigger = BrokerTrigger.forScreen(context, source)
    }
    return trigger
}
