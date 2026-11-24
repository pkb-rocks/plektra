package rocks.pkb.plektra.inputmethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class BergamotModelStoreTest {
 private val c get()=RuntimeEnvironment.getApplication()
 private val bytes="fictional model bytes".toByteArray()
 private fun spec()=BergamotModelStore.FileSpec("fixture.bin","https://example.invalid/fixture.bin",bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
 @Test fun compressedDownloadIsVerifiedBeforePublication() {
  val store=BergamotModelStore(c);val s=spec();val b=ByteArrayOutputStream();GZIPOutputStream(b).use{it.write(bytes)}
  store.install("fr","en",s,b.toByteArray().inputStream()){true}
  assertTrue(store.valid(store.file("fr","en",s.name),s))
 }
 @Test fun truncatedOrCancelledDownloadNeverOverwritesInstalledModel() {
  val store=BergamotModelStore(c);val s=spec();store.install("fr","en",s,bytes.inputStream()){true}
  for(data in listOf(bytes.copyOf(3),bytes+byteArrayOf(2))) {
   try {store.install("fr","en",s,data.inputStream()){true};fail("Invalid file accepted")}catch(_:IllegalArgumentException){}
   assertTrue(store.valid(store.file("fr","en",s.name),s))
  }
  try {store.install("fr","en",s,bytes.inputStream()){false};fail("Cancelled file accepted")}catch(_:IllegalStateException){}
  assertTrue(store.valid(store.file("fr","en",s.name),s))
 }
 @Test fun onlySupportedPairsAndSafePrivateFileNamesAreAccepted() {
  val store=BergamotModelStore(c);assertEquals(3,store.specs("fr","en").size);assertEquals(3,store.specs("en","fr").size)
  try {store.specs("de","fr");fail()}catch(_:IllegalArgumentException){}
  try {store.file("fr","en","../private");fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun providerDefaultsToGoogleAndPersistsExplicitChoice() {
  val prefs=rocks.pkb.plektra.SettingsManager.getPreferences(c);prefs.edit().clear().commit()
  assertEquals(TranslationEngines.GOOGLE,TranslationEngines.selected(c))
  prefs.edit().putString(TranslationEngines.PREFERENCE,TranslationEngines.BERGAMOT).commit()
  assertEquals(TranslationEngines.BERGAMOT,TranslationEngines.selected(c));prefs.edit().clear().commit()
 }
}
