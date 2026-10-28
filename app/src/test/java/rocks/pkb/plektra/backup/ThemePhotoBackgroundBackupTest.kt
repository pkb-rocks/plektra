package rocks.pkb.plektra.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.*
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemePhotoBackgroundBackupTest {
    private lateinit var context: Context
    private val bytes: ByteArray get() = javaClass.classLoader!!.getResourceAsStream("theme-backdrop.png")!!.use { it.readBytes() }
    private fun image(data: ByteArray = bytes): KeyboardThemeTexture {
        val hash=ThemeTextureStore.hash(data)
        val texture="texture-$hash.png";val source="source-$hash.png"
        for (name in listOf(texture,source)) ThemeTextureStore.file(context,name).apply { parentFile!!.mkdirs();writeBytes(data) }
        return KeyboardThemeTexture(texture,wholeKeyboard=true,sourceAsset=source,sourceCrop=listOf(0f,0f,1f,1f))
    }
    private fun theme(photo: KeyboardThemeTexture)=SettingsManager.defaultKeyboardTheme().copy(textures=mapOf("background" to photo))
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        File(context.filesDir,"theme-images").deleteRecursively()
        ThemeTextureStore.invalidate()
    }
    @Test fun actualZipTransfersImageSourceLibraryDraftAndBothTargets()=runBlocking {
        val photo=image();val value=theme(photo)
        SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,value)
        SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.SOFTWARE,value.copy(keyHeightScale=1.3f))
        SettingsManager.saveKeyboardTheme(context,"Photo",value)
        SettingsManager.saveKeyboardThemeDraft(context,SettingsManager.KeyboardThemeDraft("Draft",value,setOf("background")))
        val zip=File(context.cacheDir,"photo-theme.zip")
        val backup=BackupManager.createBackup(context,Uri.fromFile(zip));assertTrue(backup.toString(),backup is BackupResult.Success)
        ZipFile(zip).use {
            assertNotNull(it.getEntry("theme-images/${photo.asset}"));assertNotNull(it.getEntry("theme-images/${photo.sourceAsset}"))
            assertNotNull(it.getEntry("theme-images/manifest.json"));assertNull(it.getEntry("files/theme-images/${photo.asset}"))
        }
        SettingsManager.getPreferences(context).edit().clear().commit();File(context.filesDir,"theme-images").deleteRecursively()
        val result=RestoreManager.restore(context,Uri.fromFile(zip));assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(value,SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE))
        assertEquals(value.copy(keyHeightScale=1.3f),SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.SOFTWARE))
        assertEquals(value,SettingsManager.getSavedKeyboardThemes(context).single().theme)
        assertEquals(value,SettingsManager.getKeyboardThemeDrafts(context).single().theme)
        assertArrayEquals(bytes,ThemeTextureStore.file(context,photo.asset).readBytes())
        assertArrayEquals(bytes,ThemeTextureStore.file(context,photo.sourceAsset!!).readBytes())
    }
    @Test fun abandonedSelectionsAreNotExported() {
        val photo=image();SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,theme(photo))
        val abandoned="texture-${"f".repeat(64)}.png";ThemeTextureStore.file(context,abandoned).writeBytes(bytes)
        val out=File(context.cacheDir,"only-references")
        ThemeImageBackup.snapshot(context,out)
        assertFalse(File(out,abandoned).exists());assertTrue(File(out,photo.asset).exists())
    }
    @Test fun snapshotUsesFrozenExportedReferencesRatherThanLaterPreferences() {
        val photo=image();val frozen=SettingsManager.keyboardThemeToJsonString(theme(photo))
        SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,SettingsManager.defaultKeyboardTheme())
        val out=File(context.cacheDir,"frozen-references")
        ThemeImageBackup.snapshot(context,out,listOf(frozen))
        assertTrue(File(out,photo.asset).exists());assertTrue(File(out,photo.sourceAsset!!).exists())
    }
    @Test fun missingReferencedSourceFailsExportBeforeWritingArchive()=runBlocking {
        val photo=image();SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,theme(photo))
        ThemeTextureStore.file(context,photo.sourceAsset!!).delete()
        val zip=File(context.cacheDir,"missing-source.zip")
        assertTrue(BackupManager.createBackup(context,Uri.fromFile(zip)) is BackupResult.Failure)
        assertFalse(zip.exists())
    }
    @Test fun alteredImageFailsInspectionAndRestoreBeforePreferencesChange()=runBlocking {
        val photo=image();val value=theme(photo);SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,value)
        val root=File(context.cacheDir,"broken-archive").apply {mkdirs()}
        PreferencesBackupHelper.dumpSharedPreferences(context,File(root,"prefs").apply {mkdirs()})
        val folder=File(root,"theme-images");ThemeImageBackup.snapshot(context,folder)
        File(folder,photo.asset).appendBytes(byteArrayOf(1))
        val zip=File(context.cacheDir,"broken.zip");zip.outputStream().use {ZipHelper.zip(root,it)}
        assertTrue(RestoreManager.inspect(context,Uri.fromFile(zip)) is RestoreInspectionResult.Failure)
        assertTrue(RestoreManager.restore(context,Uri.fromFile(zip)) is RestoreResult.Failure)
        assertEquals(value,SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE))
        assertArrayEquals(bytes,ThemeTextureStore.file(context,photo.asset).readBytes())
    }
    @Test fun unlistedFilesAndTraversalNamesAreRejected() {
        val photo=image();SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,theme(photo))
        val folder=File(context.cacheDir,"unexpected");ThemeImageBackup.snapshot(context,folder)
        File(folder,"unexpected.png").writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java){ThemeImageBackup.validate(folder)}
        File(folder,"unexpected.png").delete()
        val manifest=File(folder,"manifest.json");val data=JSONObject(manifest.readText())
        data.getJSONArray("images").getJSONObject(0).put("name","../escape.png");manifest.writeText(data.toString())
        assertThrows(IllegalArgumentException::class.java){ThemeImageBackup.validate(folder)}
    }
    @Test fun absentImageFolderPreservesLocalImagesOnLegacyPartialRestore()=runBlocking {
        val photo=image();val value=theme(photo);SettingsManager.saveKeyboardTheme(context,"Keep",value)
        val root=File(context.cacheDir,"legacy-partial").apply {mkdirs()};val prefs=File(root,"prefs").apply {mkdirs()}
        File(prefs,"plektra_prefs.json").writeText(JSONObject().put("name","plektra_prefs").put("entries",JSONObject().put("keyboard_theme_hardware",
            PreferenceValue(PreferenceValueType.STRING,SettingsManager.keyboardThemeToJsonString(SettingsManager.defaultKeyboardTheme())).toJson())).toString())
        val zip=File(context.cacheDir,"legacy-partial.zip");zip.outputStream().use {ZipHelper.zip(root,it)}
        val result=RestoreManager.restore(context,Uri.fromFile(zip));assertTrue(result.toString(),result is RestoreResult.Success)
        assertEquals(value,SettingsManager.getSavedKeyboardThemes(context).single().theme)
        assertArrayEquals(bytes,ThemeTextureStore.file(context,photo.asset).readBytes())
    }
    @Test fun danglingImageReferencesAreRejectedEvenWhenPreferencesAreValid()=runBlocking {
        val photo=image();SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,theme(photo))
        val root=File(context.cacheDir,"dangling").apply {mkdirs()}
        PreferencesBackupHelper.dumpSharedPreferences(context,File(root,"prefs").apply {mkdirs()})
        val zip=File(context.cacheDir,"dangling.zip");zip.outputStream().use {ZipHelper.zip(root,it)}
        assertTrue(RestoreManager.restore(context,Uri.fromFile(zip)) is RestoreResult.Failure)
    }
    @Test fun laterPreferenceFailureRollsBackInstalledImagesAndExistingTheme()=runBlocking {
        val photo=image();SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,theme(photo))
        val zip=File(context.cacheDir,"rollback-photo.zip");assertTrue(BackupManager.createBackup(context,Uri.fromFile(zip)) is BackupResult.Success)
        File(context.filesDir,"theme-images").deleteRecursively()
        val old=SettingsManager.defaultKeyboardTheme().copy(background=0xff234567.toInt())
        SettingsManager.setKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE,old)
        var failedAfterImageInstall=false
        val wrapper=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val original=super.getSharedPreferences(name,mode)
                if(name!="plektra_prefs")return original
                return object:SharedPreferences by original {
                    override fun edit():SharedPreferences.Editor {
                        val editor=original.edit()
                        return object:SharedPreferences.Editor by editor {
                            override fun commit():Boolean {
                                if(!failedAfterImageInstall && ThemeTextureStore.file(context,photo.asset).exists()) {
                                    failedAfterImageInstall=true;throw IllegalStateException("Synthetic preference failure")
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        assertTrue(RestoreManager.restore(wrapper,Uri.fromFile(zip)) is RestoreResult.Failure)
        assertTrue(failedAfterImageInstall)
        assertFalse(ThemeTextureStore.file(context,photo.asset).exists())
        assertEquals(old,SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE))
    }
}
