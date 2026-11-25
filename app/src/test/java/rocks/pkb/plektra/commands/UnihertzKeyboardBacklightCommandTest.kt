package rocks.pkb.plektra.commands

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnihertzKeyboardBacklightCommandTest {

    private lateinit var context: Application
    private val component = ComponentName.unflattenFromString(
        DeviceControlCommandSource.UNIHERTZ_KEYBOARD_BACKLIGHT_COMPONENT
    )!!

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun command_isHiddenWithoutVendorSettingsScreen() {
        val command = commandOrNull()

        assertNull(command)
    }

    @Test
    fun command_isHiddenWhenVendorSettingsScreenIsNotExported() {
        installVendorSettingsScreen(exported = false)

        assertNull(commandOrNull())
    }

    @Test
    fun command_isHiddenWhenVendorSettingsPermissionIsDenied() {
        val permission = android.Manifest.permission.CAMERA
        shadowOf(context).denyPermissions(permission)
        installVendorSettingsScreen(permission = permission)

        assertNull(commandOrNull())
    }

    @Test
    fun command_isAvailableWhenVendorSettingsPermissionIsGranted() {
        val permission = android.Manifest.permission.CAMERA
        shadowOf(context).grantPermissions(permission)
        installVendorSettingsScreen(permission = permission)

        assertNotNull(commandOrNull())
    }

    @Test
    fun command_isAvailableForNavModeWhenVendorSettingsScreenExists() {
        installVendorSettingsScreen()

        val command = commandOrNull()

        assertNotNull(command)
        assertTrue(CommandSurface.NavMode in command!!.defaultSurfaces)
        assertTrue(CommandSurface.AssignedKey in command.defaultSurfaces)
        assertEquals(
            command.id,
            CommandRegistry(context).getCommands(CommandSurface.NavMode)
                .single { it.id == DeviceControlCommandSource.COMMAND_UNIHERTZ_KEYBOARD_BACKLIGHT }
                .id
        )
    }

    @Test
    fun execute_opensVendorKeyboardBacklightSettings() {
        installVendorSettingsScreen()
        val command = commandOrNull()!!

        val result = CommandExecutor(context, showToast = false).execute(command)

        assertTrue(result.isSuccess)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(component, started.component)
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun commandOrNull(): CommandTarget? {
        return DeviceControlCommandSource().getCommands(context)
            .firstOrNull { it.id == DeviceControlCommandSource.COMMAND_UNIHERTZ_KEYBOARD_BACKLIGHT }
    }

    private fun installVendorSettingsScreen(exported: Boolean = true, permission: String? = null) {
        val packageManager = shadowOf(context.packageManager)
        packageManager.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                this.exported = exported
                this.permission = permission
                enabled = true
            }
        )
        packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN))
    }
}
