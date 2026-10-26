package rocks.pkb.plektra.inputmethod

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import rocks.pkb.plektra.SettingsManager

/** Shared optional key filter: configured launcher chords and existing Clicks remapping.
 * No screen-content retrieval is requested. Unmatched events retain their previous route.
 */
class ClicksLauncherButtonAccessibilityService : AccessibilityService() {
    private val mapper = ClicksLauncherAccessibilityKeyMapper()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val activeIme=android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val unlocked=(getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked!=true
        if(activeIme==packageName && unlocked && rocks.pkb.plektra.KeyboardLauncherBridge.dispatch(event)) return true

        val device = event.device
        val result = mapper.map(
            event = event,
            isClicksPowerKeyboard = device?.let(DeviceSpecific::isClicksPowerKeyboard) == true,
            redButtonMode = SettingsManager.getClicksButtonMode(this),
            launcherButtonMode = SettingsManager.getClicksMetaButtonMode(this),
            altButtonMode = SettingsManager.getClicksAltButtonMode(this),
            microphoneButtonMode = SettingsManager.getClicksMicrophoneButtonMode(this)
        )
        result.directAction?.let { ClicksButtonDirectActionExecutor.execute(this, it) }
        val forwardedEvent = result.forwardedEvent
        if (forwardedEvent != null && !ClicksAccessibilityKeyBridge.dispatch(forwardedEvent)) {
            // Fail open when Plektra has no active editor. Keeping the mapper's held-modifier
            // state would otherwise consume the following native key events as well.
            mapper.resetDevice(event.deviceId)
            return false
        }
        return result.consume
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {rocks.pkb.plektra.KeyboardLauncherBridge.cancel()}

    override fun onDestroy() {
        rocks.pkb.plektra.KeyboardLauncherBridge.cancel()
        mapper.reset()
        super.onDestroy()
    }
}
