package rocks.pkb.plektra.inputmethod
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DeepLUsageTest {
 @Test fun realAccountAndKeyLimitsTakePrecedenceOverAnyFixedFreeAllowance() {
  assertEquals(900000,DeepLUsage.parse("{\"character_count\":100000,\"character_limit\":1000000}").remaining)
  assertEquals(50,DeepLUsage.parse("{\"character_count\":100000,\"character_limit\":1000000,\"api_key_character_count\":150,\"api_key_character_limit\":200}").remaining)
  assertEquals(0,DeepLUsage.parse("{\"character_count\":120,\"character_limit\":100}").remaining)
 }
 @Test fun explicitUsageRefreshSendsNoTextAndCacheIsBoundToCredentialAndOutsideBackup() {
  val context=ApplicationProvider.getApplicationContext<Context>();var auth=DeepLCredentials.Entry("fictional-usage-key",true);var calls=0;var complete=false
  val usage=DeepLUsage(context,{auth}){url,key->calls++;assertTrue(url.endsWith("/v2/usage"));assertEquals(auth.key,key);200 to "{\"character_count\":123,\"character_limit\":1000000}"}
  assertEquals(0,calls);usage.refresh({assertEquals(999877,it.remaining);complete=true},{fail()})
  repeat(200){shadowOf(Looper.getMainLooper()).idle();if(!complete)Thread.sleep(10)};assertTrue(complete);assertEquals(1,calls)
  assertEquals(999877,usage.cached()!!.remaining);assertTrue(java.io.File(context.noBackupFilesDir,"deepl-usage-v1").isFile)
  auth=DeepLCredentials.Entry("other-fictional-key",true);assertNull(usage.cached());usage.close()
 }
 @Test fun missingCredentialNeverCallsNetwork() {
  val context=ApplicationProvider.getApplicationContext<Context>();var calls=0;var failed=false
  val usage=DeepLUsage(context,{null}){_,_->calls++;200 to "{}"};usage.refresh({fail()},{failed=true})
  assertTrue(failed);assertEquals(0,calls);usage.close()
 }
}
