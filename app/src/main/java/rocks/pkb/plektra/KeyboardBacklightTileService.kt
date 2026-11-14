package rocks.pkb.plektra

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.KeyboardBacklightManager

/**
 * Quick Settings tile that toggles the Unihertz keyboard backlight
 * (`Settings.Global.agui_keyboard_background_light`).
 *
 * Writing the global needs WRITE_SECURE_SETTINGS. The private build gets it from the embedded ADB
 * broker, and holds it only while a feature that needs it is on: this tile's own switch in the
 * Smart backlight section ([rocks.pkb.plektra.inputmethod.WriteSecureSettingsHolders.BACKLIGHT_TILE]),
 * or Smart backlight ([rocks.pkb.plektra.inputmethod.WriteSecureSettingsHolders.BACKLIGHT]).
 * Without the permission a tap says where that switch is. The tile itself never touches the broker
 * (it cannot mint a [rocks.pkb.plektra.inputmethod.BrokerTrigger]): while the permission is held every write is an
 * ordinary in-process settings write, which is what a tile tap needs.
 *
 * Only offered on the Titan 2 Elite, where the vendor row exists.
 */
class KeyboardBacklightTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!isSupportedDevice()) {
            updateTile()
            return
        }
        if (!KeyboardBacklightManager.canWriteKeyboardLightSwitch(this)) {
            Toast.makeText(
                this,
                getString(R.string.backlight_tile_permission_missing),
                Toast.LENGTH_LONG
            ).show()
            updateTile()
            return
        }
        val newValue = if (readBacklightEnabled()) {
            KeyboardBacklightManager.KEYBOARD_LIGHT_OFF
        } else {
            KeyboardBacklightManager.KEYBOARD_LIGHT_ON
        }
        if (!KeyboardBacklightManager.writeKeyboardLightSwitch(this, newValue)) {
            Log.w(TAG, "Failed to write keyboard backlight setting")
        }
        updateTile()
    }

    private fun isSupportedDevice(): Boolean = DeviceSpecific.isTitan2EliteDevice()

    private fun readBacklightEnabled(): Boolean =
        KeyboardBacklightManager.readKeyboardLightSwitch(this) == KeyboardBacklightManager.KEYBOARD_LIGHT_ON

    private fun updateTile() {
        val tile = qsTile ?: return
        val supported = isSupportedDevice()
        val canWrite = KeyboardBacklightManager.canWriteKeyboardLightSwitch(this)
        tile.state = when {
            !supported || !canWrite -> Tile.STATE_UNAVAILABLE
            readBacklightEnabled() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.backlight_tile_label)
        tile.subtitle = if (supported && !canWrite) {
            getString(R.string.backlight_tile_no_permission_subtitle)
        } else {
            null
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "KbBacklightTile"
    }
}
