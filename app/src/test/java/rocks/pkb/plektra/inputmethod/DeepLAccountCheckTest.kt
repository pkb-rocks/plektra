package rocks.pkb.plektra.inputmethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DeepLAccountCheckTest {
 private val usage="{\"character_count\":100,\"character_limit\":1000000}"
 @Test fun detectsTheCorrectServerUsingOnlyAccountUsageRequests(){
  val calls=mutableListOf<String>()
  val result=DeepLAccountCheck.probe(DeepLCredentials.Entry("fictional-api-key",true)){url,key->
   assertEquals("fictional-api-key",key);assertTrue(url.endsWith("/v2/usage"));calls.add(url)
   if(url.contains("api-free"))403 to "" else 200 to usage
  }
  assertEquals(2,calls.size);assertFalse(result.credential.free);assertEquals(999900,result.usage!!.remaining)
 }
 @Test fun validFirstServerDoesNotSendTheCredentialElsewhere(){
  var count=0
  val result=DeepLAccountCheck.probe(DeepLCredentials.Entry("fictional-api-key:fx",true)){url,_->count++;assertTrue(url.startsWith("https://api-free.deepl.com/"));200 to usage}
  assertEquals(1,count);assertTrue(result.credential.free)
 }
 @Test fun invalidKeysAreRejectedOnBothOfficialServers(){
  var count=0
  val failure=runCatching{DeepLAccountCheck.probe(DeepLCredentials.Entry("fictional-invalid",true)){_,_->count++;403 to ""}}.exceptionOrNull()
  assertEquals(2,count);assertEquals(403,(failure as DeepLAccountCheck.Rejected).status)
 }
 @Test fun rateLimitsDoNotCauseProbingAnotherServer(){
  var count=0
  val failure=runCatching{DeepLAccountCheck.probe(DeepLCredentials.Entry("fictional-api-key",false)){_,_->count++;429 to ""}}.exceptionOrNull()
  assertEquals(1,count);assertEquals(429,(failure as DeepLAccountCheck.Rejected).status)
 }
 @Test fun aVerifiedKeyRemainsValidWhenOptionalQuotaFieldsAreUnavailable(){
  val result=DeepLAccountCheck.probe(DeepLCredentials.Entry("fictional-api-key",false)){_,_->200 to "{}"}
  assertNull(result.usage)
  assertEquals(900,DeepLUsage.parse("{\"character_count\":100,\"character_limit\":1000,\"api_key_character_count\":null,\"api_key_character_limit\":null}").remaining)
 }
}
