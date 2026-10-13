package rocks.pkb.plektra.ring

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import rocks.pkb.plektra.SettingsManager

/**
 * The ring itself: a black, lock-screen-topping activity that turns the screen on and keeps it
 * on for the configured time.
 *
 * It ends on the first thing that means the user has noticed — a touch, a key, unlocking —
 * and on the screen going dark. When the time runs out it does not switch the screen off
 * (an app cannot); it lets go of the screen and blanks the ring, so the panel is black from that
 * moment, and the phone's own timeout switches it off, black to black, so nothing flashes.
 *
 * Brobata/physiboard#15: the hold is a wake lock of our own, without
 * [PowerManager.ON_AFTER_RELEASE], rather than FLAG_KEEP_SCREEN_ON. See [acquireHold].
 */
class NotificationRingActivity : Activity() {

    private lateinit var view: RingView
    private val handler = Handler(Looper.getMainLooper())
    private val sources = LinkedHashMap<String, RingSource>()
    private var demo = false
    private var breathing: ValueAnimator? = null
    private var hold: PowerManager.WakeLock? = null

    /** The ring as placed, kept so it can be drawn again after [blank]. */
    private var placed: RingGeometry.Ring? = null

    /** True between the end of the configured time and the next notification; the panel is black. */
    private var blanked = false

    private val expire = Runnable {
        // Let go of the screen; the system's own timeout takes it from here.
        releaseHold()
        if (demo) finish() else blank()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        NotificationRingLauncher.dismissAnnouncement(this)
        // The keyboard was darkened before this launch; from here its restore is ours.
        RingBacklight.holdForRing()

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            screenBrightness = SettingsManager.getNotificationRingBrightness(this@NotificationRingActivity)
                .screenBrightness
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        view = RingView(this)
        view.setOnApplyWindowInsetsListener { v, insets ->
            placeRing(insets.displayCutout?.boundingRects?.firstOrNull()?.let {
                val dpi = resources.displayMetrics.densityDpi
                if (RingGeometry.isTitanCutout(it.left, it.top, it.right, it.bottom, dpi)) {
                    RingGeometry.titanFitted(dpi)
                } else {
                    RingGeometry.aroundCutout(it.left, it.top, it.right, it.bottom, gapPx(), strokePx())
                }
            })
            insets
        }
        setContentView(view)

        demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        intent.toSource()?.let { addSource(it) }
        if (sources.isEmpty() && !demo) {
            finish()
            return
        }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        startBreathing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toSource()?.let { addSource(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Some launches deliver no insets callback; make sure the ring has a home either way.
        if (placed == null) placeRing(null)
    }

    private fun placeRing(fromCutout: RingGeometry.Ring?) {
        val ring = SettingsManager.getNotificationRingOverride(this)
            ?.let { if (it.stroke > 0f) it else it.copy(stroke = strokePx()) }
            ?: fromCutout
            ?: RingGeometry.titanFallback(resources.displayMetrics.densityDpi, gapPx(), strokePx())
        placed = ring
        if (!blanked) view.ring = ring
    }

    private fun gapPx() = 3f * resources.displayMetrics.density
    private fun strokePx() = 3f * resources.displayMetrics.density

    /** A new notification while the ring is up: recolour, add its icon, and restart the clock. */
    fun addSource(source: RingSource) {
        sources[source.key] = source
        if (blanked) unblank()
        view.color = source.color
        view.icons = sources.values.map { it.packageName }.distinct().takeLast(MAX_ICONS)
            .mapNotNull { appIcon(it) }
        armExpiry()
    }

    /** The notification was dismissed elsewhere; when nothing is left, so is the ring. */
    fun removeSource(key: String) {
        if (sources.remove(key) == null) return
        if (sources.isEmpty() && !demo) {
            finish()
            return
        }
        sources.values.lastOrNull()?.let { view.color = it.color }
        view.icons = sources.values.map { it.packageName }.distinct().takeLast(MAX_ICONS)
            .mapNotNull { appIcon(it) }
    }

    private fun appIcon(packageName: String): Drawable? {
        if (!SettingsManager.isNotificationRingIconsEnabled(this)) return null
        return runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun armExpiry() {
        handler.removeCallbacks(expire)
        val holdMs = RingDuration.holdMillis(SettingsManager.getNotificationRingMinutes(this), demo)
        acquireHold(holdMs)
        handler.postDelayed(expire, holdMs)
    }

    /**
     * Holds the screen on for [holdMs], with a wake lock of our own instead of FLAG_KEEP_SCREEN_ON.
     *
     * The window flag is backed by the platform's own SCREEN_BRIGHT lock created with
     * ON_AFTER_RELEASE (RootWindowContainer, `mHoldScreenWakeLock`), so letting go of it counts as
     * user activity and the screen then stays on for a whole further system screen timeout. The
     * ring and the keyboard light it keeps up lasted the setting PLUS that timeout, and the ring
     * never ended before the screen did (brobata/physiboard#15). This lock has no ON_AFTER_RELEASE,
     * so when it lapses the timeout is measured from the last real activity, which was the
     * moment the ring turned the screen on.
     */
    @Suppress("DEPRECATION")
    private fun acquireHold(holdMs: Long) {
        releaseHold()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        hold = runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(holdMs)
            }
        }.onFailure { Log.e(TAG, "could not hold the screen on", it) }.getOrNull()
    }

    private fun releaseHold() {
        hold?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        hold = null
    }

    /**
     * The time is up: stop drawing. The panel is black from here, which on this AMOLED screen means
     * off, and the keyboard backlight stays as the ring left it until the screen itself goes off
     * and [onDestroy] puts it back.
     */
    private fun blank() {
        blanked = true
        breathing?.cancel()
        view.ring = null
        view.icons = emptyList()
    }

    /** A notification arrived after [blank]: draw the ring again and start the clock over. */
    private fun unblank() {
        blanked = false
        view.ring = placed
        startBreathing()
    }

    private fun startBreathing() {
        breathing?.cancel()
        breathing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { view.breath = it.animatedValue as Float }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) finish()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key on the physical keyboard means the user is here.
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Every way a ring ends - expiry, a touch, a key, an unlock, the screen going off -
        // arrives here, so this is the one place the keyboard has to come back.
        RingBacklight.restore(this)
        handler.removeCallbacks(expire)
        releaseHold()
        breathing?.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        if (current === this) current = null
    }

    private fun Intent.toSource(): RingSource? {
        val key = getStringExtra(EXTRA_KEY) ?: return null
        return RingSource(
            key = key,
            packageName = getStringExtra(EXTRA_PACKAGE) ?: packageName,
            color = getIntExtra(EXTRA_COLOR, NotificationRingPolicy.DEFAULT_COLOR)
        )
    }

    companion object {
        private const val TAG = "NotificationRing"
        private const val WAKE_LOCK_TAG = "Plektra:NotificationRing"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_COLOR = "color"
        private const val EXTRA_DEMO = "demo"
        private const val MAX_ICONS = 3
        private const val BREATH_MS = 1_800L

        /** The ring on screen right now, if any. Main-thread reads only. */
        @Volatile
        var current: NotificationRingActivity? = null
            private set

        fun intent(context: Context, source: RingSource): Intent =
            Intent(context, NotificationRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_KEY, source.key)
                .putExtra(EXTRA_PACKAGE, source.packageName)
                .putExtra(EXTRA_COLOR, source.color)

        /** A ring for the settings screen's "Try it": Plektra's own icon, a few seconds. */
        fun demoIntent(context: Context): Intent =
            intent(context, RingSource("demo", context.packageName, NotificationRingPolicy.DEFAULT_COLOR))
                .putExtra(EXTRA_DEMO, true)
    }
}
