package rocks.pkb.plektra.inputmethod
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DeepLCredentialsTest {
 @Test fun keyIsEncryptedOutsideBackupAndDeletionRemovesIt() {
  val c=ApplicationProvider.getApplicationContext<Context>();val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
  val store=DeepLCredentials(c){key};store.write("fictional-api-key:fx",true)
  assertEquals("fictional-api-key:fx",store.read()!!.key);assertTrue(store.read()!!.free)
  val file=File(c.noBackupFilesDir,"deepl-credential-v1")
  assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("fictional-api-key:fx"))
  assertFalse(file.canonicalPath.startsWith(c.filesDir.canonicalPath+File.separator))
  store.delete();assertNull(store.read());assertFalse(file.exists())
 }
 @Test fun modifiedCiphertextAndHeaderInjectionAreRejected() {
  val c=ApplicationProvider.getApplicationContext<Context>();val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
  val store=DeepLCredentials(c){key};store.write("fictional-api-key",false)
  val file=File(c.noBackupFilesDir,"deepl-credential-v1");val data=file.readBytes();data[data.lastIndex]=(data.last().toInt() xor 1).toByte();file.writeBytes(data)
  assertNull(store.read())
  try{store.write("bad-key\r\nInjected: x",true);fail()}catch(_:IllegalArgumentException){}
 }
}
