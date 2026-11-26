package rocks.pkb.plektra

import android.content.ContextWrapper
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.PreferencesBackupHelper
import rocks.pkb.plektra.backup.ZipHelper
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class KeyboardLauncherStoreTest {
 private val context get()=RuntimeEnvironment.getApplication()
 @Before fun reset() {SettingsManager.getPreferences(context).edit().clear().commit()}
 @Test fun disabledByDefaultAndSettingsNeverAlterNavMappings() {
  val nav=SettingsManager.getNavModeMappingsFile(context);nav.parentFile?.mkdirs();nav.writeText("unchanged-nav-fixture")
  assertFalse(KeyboardLauncherStore.enabled(context));assertEquals(400,KeyboardLauncherStore.delay(context));assertTrue(KeyboardLauncherStore.read(context).isEmpty())
  KeyboardLauncherStore.save(context,mapOf(KeyEvent.KEYCODE_A to LauncherMapping("app:org.example.synthetic",LauncherMapping("device.home"))))
  assertEquals("unchanged-nav-fixture",nav.readText())
 }
 @Test fun shortLongReferencesRemainStableAndUnknownCommandIdsAreNotDiscarded() {
  val map=mapOf(KeyEvent.KEYCODE_A to LauncherMapping("app:org.example.synthetic",LauncherMapping("future.command")),KeyEvent.KEYCODE_Z to LauncherMapping("",LauncherMapping("device.home")))
  KeyboardLauncherStore.save(context,map);assertEquals(map,KeyboardLauncherStore.read(context))
 }
 @Test fun malformedAssignmentsAndInvalidTriggersFailSafely() {
  val prefs=SettingsManager.getPreferences(context)
  prefs.edit().putString(KeyboardLauncherStore.ASSIGNMENTS,"invalid").putInt(KeyboardLauncherStore.TRIGGER,-1).putInt(KeyboardLauncherStore.DELAY,99999).commit()
  assertTrue(KeyboardLauncherStore.read(context).isEmpty());assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT,KeyboardLauncherStore.trigger(context));assertEquals(800,KeyboardLauncherStore.delay(context))
 }
 @Test fun fourTypedPreferencesSurviveRealZipExportAndRestore() {
  val root=File(System.getProperty("java.io.tmpdir"),"launcher-zip-${System.nanoTime()}").apply{mkdirs()}
  val wrapped=object:ContextWrapper(context){override fun getDataDir()=File(root,"data").apply{mkdirs()}}
  try {
   val prefs=SettingsManager.getPreferences(wrapped)
   prefs.edit().putBoolean(KeyboardLauncherStore.ENABLED,true).putInt(KeyboardLauncherStore.TRIGGER,KeyEvent.KEYCODE_SHIFT_RIGHT).putInt(KeyboardLauncherStore.DELAY,650).commit()
   val map=mapOf(KeyEvent.KEYCODE_A to LauncherMapping("app:org.example.synthetic",LauncherMapping("device.home")))
   KeyboardLauncherStore.save(wrapped,map)
   File(wrapped.dataDir,"shared_prefs/plektra_prefs.xml").apply{parentFile!!.mkdirs();writeText("<map/>")}
   val source=File(root,"export");val prefDir=File(source,"prefs").apply{mkdirs()}
   assertTrue(PreferencesBackupHelper.dumpSharedPreferences(wrapped,prefDir).contains("prefs/plektra_prefs.json"))
   val bytes=ByteArrayOutputStream();ZipHelper.zip(source,bytes)
   val extracted=File(root,"restore").apply{mkdirs()};ZipHelper.unzip(bytes.toByteArray().inputStream(),extracted)
   prefs.edit().clear().commit()
   val summary=PreferencesBackupHelper.restorePreferences(wrapped,PreferencesBackupHelper.readPreferencesFromBackup(File(extracted,"prefs")))
   assertTrue(summary.skippedKeys.isEmpty());assertTrue(KeyboardLauncherStore.enabled(wrapped));assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT,KeyboardLauncherStore.trigger(wrapped));assertEquals(650,KeyboardLauncherStore.delay(wrapped));assertEquals(map,KeyboardLauncherStore.read(wrapped))
  } finally {root.deleteRecursively()}
 }
 @Test fun allLauncherRoutesAndScreenTitleAreRegistered() {
  val ids=setOf("main.personal_launcher","personal_launcher.enabled","personal_launcher.trigger","personal_launcher.delay","personal_launcher.assignments")
  val entries=SettingLinkRegistry.entries.filter{it.id in ids}
  assertEquals(ids,entries.map{it.id}.toSet());assertTrue(entries.all{it.route.destination==SettingsDestination.PersonalLauncher})
  assertEquals(R.string.keyboard_launcher_title,SettingLinkRegistry.destinationTitles[SettingsDestination.PersonalLauncher])
 }
}
