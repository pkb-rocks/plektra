package rocks.pkb.plektra.inputmethod
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import rocks.pkb.plektra.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DeepLTranslationEngineTest {
 private fun await(done:()->Boolean){repeat(200){shadowOf(Looper.getMainLooper()).idle();if(done())return;Thread.sleep(10)};fail("Callback timeout")}
 @Test fun offlineConfigurationAndConsentFlagsDoNotMakeRequestsAndFreeRequestPreservesFormatting() {
  val c=ApplicationProvider.getApplicationContext<Context>();var requests=0;var output=""
  val engine=DeepLTranslationEngine(c,"fr","en",DeepLCredentials.Entry("fictional-key",true)){url,key,body->
   requests++;assertEquals("https://api-free.deepl.com/v2/translate",url);assertEquals("fictional-key",key)
   val json=JSONObject(body);assertTrue(json.getBoolean("preserve_formatting"));assertEquals("nonewlines",json.getString("split_sentences"));assertEquals("EN-GB",json.getString("target_lang"));assertEquals("Bonjour!!",json.getJSONArray("text").getString(0))
   200 to "{\"translations\":[{\"text\":\"Hello!!\"}]}"
  }
  assertTrue(engine.requiresOnlineConsent);engine.available({assertTrue(it)},{fail()});assertEquals(0,requests)
  engine.translate("Bonjour!!",{output=it},{fail()});await{output.isNotEmpty()};assertEquals("Hello!!",output);engine.close()
 }
 @Test fun quotaFailureIsExplicitAndNeverFallsBackToAnotherProvider() {
  val c=ApplicationProvider.getApplicationContext<Context>();var failed=false
  val engine=DeepLTranslationEngine(c,"en","fr",DeepLCredentials.Entry("fictional-key",false)){url,_,_->assertEquals("https://api.deepl.com/v2/translate",url);456 to "{}"}
  engine.translate("Hi",{fail()},{failed=true});await{failed};assertEquals(R.string.translation_deepl_quota,engine.failureMessage);engine.close()
 }
 @Test fun missingCredentialsDoNotSendAnything() {
  val c=ApplicationProvider.getApplicationContext<Context>();var requests=0
  val engine=DeepLTranslationEngine(c,"fr","en",null){_,_,_->requests++;200 to "{}"}
  assertEquals(R.string.translation_deepl_missing,engine.configurationIssue)
  engine.translate("Bonjour",{fail()},{});assertEquals(0,requests);engine.close()
 }
 @Test fun paragraphsUseOneOrderedRequest() {
  val c=ApplicationProvider.getApplicationContext<Context>();var requests=0;var output=listOf<String>()
  val engine=DeepLTranslationEngine(c,"fr","en",DeepLCredentials.Entry("fictional-key",true)){_,_,body->
   requests++;val input=JSONObject(body).getJSONArray("text");assertEquals(2,input.length());assertEquals("Merci",input.getString(1))
   200 to "{\"translations\":[{\"text\":\"Hello\"},{\"text\":\"Thanks\"}]}"
  }
  engine.translateBatch(listOf("Bonjour","Merci"),{output=it},{fail()},{false});await{output.isNotEmpty()}
  assertEquals(listOf("Hello","Thanks"),output);assertEquals(1,requests);engine.close()
 }
 @Test fun cancellingBatchDoesNotStartLaterRequestsOrReturnPartialText() {
  val c=ApplicationProvider.getApplicationContext<Context>();var requests=0;var cancelled=false
  val engine=DeepLTranslationEngine(c,"fr","en",DeepLCredentials.Entry("fictional-key",true)){_,_,_->
   requests++;cancelled=true
   200 to JSONObject().put("translations",org.json.JSONArray(List(20){JSONObject().put("text","Hello")})).toString()
  }
  engine.translateBatch(List(21){"Bonjour"},{fail()},{fail()},{cancelled})
  await{requests>0};Thread.sleep(30);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,requests);engine.close()
 }

}
