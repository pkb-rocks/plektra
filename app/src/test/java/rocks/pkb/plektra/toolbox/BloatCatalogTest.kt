package rocks.pkb.plektra.toolbox

import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The denylist is the only thing standing between this feature and a bricked phone, and it is
 * exactly the kind of code someone "simplifies" later. These tests are the tripwire.
 */
class BloatCatalogTest {

    @Test
    fun `refuses the packages Pastiera itself depends on`() {
        // Owns the orange side key that Pastiera rebinds.
        assertFalse(BloatCatalog.isRemovable("com.agui.shortcutsettings"))
        // Hosts the vendor keyboard and backlight configuration.
        assertFalse(BloatCatalog.isRemovable("com.agui.settings"))
        // The OTA client: removing it ends firmware updates permanently.
        assertFalse(BloatCatalog.isRemovable("com.agui.update"))
        // The spacebar is also the fingerprint sensor and the trackpad trigger.
        assertFalse(BloatCatalog.isRemovable("com.agui.spacebarkey"))
    }

    @Test
    fun `refuses whole classes of system package`() {
        listOf(
            "com.agui.overlay.kika",
            "com.google.android.projection.gearhead.agui.overlay",
            "com.agui.google.android.wifi.resources.overlay",
            "com.android.systemui",
            "com.android.phone",
            "com.google.android.gms",
            "com.android.dialer",
            "com.android.mms"
        ).forEach { pkg ->
            assertFalse("should refuse $pkg", BloatCatalog.isRemovable(pkg))
        }
    }

    @Test
    fun `refuses anything not in the catalog`() {
        assertFalse(BloatCatalog.isRemovable("com.whatsapp"))
        assertFalse(BloatCatalog.isRemovable("rocks.pkb.plektra"))
        assertFalse(BloatCatalog.isRemovable(""))
        assertFalse(BloatCatalog.isRemovable("com.agui.somethingInvented"))
    }

    @Test
    fun `allows the curated factory and vendor packages`() {
        assertTrue(BloatCatalog.isRemovable("com.bhpme.AgingTest"))
        assertTrue(BloatCatalog.isRemovable("com.agui.factorytest"))
        assertTrue(BloatCatalog.isRemovable("com.agui.systemmanager"))
        assertTrue(BloatCatalog.isRemovable("com.debug.loggerui"))
    }

    @Test
    fun `no catalog entry is also denied`() {
        // A row the UI offers but isRemovable() always refuses would be a dead button.
        BloatCatalog.entries().forEach { entry ->
            assertTrue(
                "catalog lists ${entry.packageName} but isRemovable refuses it",
                BloatCatalog.isRemovable(entry.packageName)
            )
        }
    }

    @Test
    fun `catalog has no duplicate packages`() {
        val names = BloatCatalog.entries().map { it.packageName }
        assertTrue("duplicate entries: $names", names.size == names.toSet().size)
    }

    // tests added in the port.

    @Test
    fun `never offers the only fallback keyboard for removal`() {
        // bbkeyboard is the phone's only other keyboard; removing it can leave nothing to type with.
        assertFalse(BloatCatalog.isRemovable("com.iqqijni.bbkeyboard"))
    }

    @Test
    fun `every catalog package is a name the command layer accepts`() {
        BloatCatalog.entries().forEach { entry ->
            assertTrue(
                "${entry.packageName} fails the package-name check",
                PrivilegedCommand.isValidPackageName(entry.packageName)
            )
            assertTrue(PrivilegedCommand.isRemovableCatalogPackage(entry.packageName))
        }
    }

    @Test
    fun `every bundle only lists catalog entries and is not empty`() {
        BloatCatalog.Preset.entries.forEach { preset ->
            assertTrue("$preset is empty", BloatCatalog.entriesIn(preset).isNotEmpty())
        }
    }

    @Test
    fun `manifest declares every catalog package as queryable`() {
        // Without a <queries> entry the local PackageManager check cannot see the package, and a
        // disable could not be confirmed from the phone itself.
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
        val declared = Regex("<package android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = BloatCatalog.entries().map { it.packageName }.filterNot { it in declared }
        assertTrue("not in the manifest <queries>: $missing", missing.isEmpty())
    }
}
