package rocks.pkb.plektra.inputmethod
import android.content.Context
import android.os.Handler
import android.os.Looper
import rocks.pkb.plektra.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

internal class DeepLTranslationEngine(context:Context,private val source:String,private val target:String,
 private val credential:DeepLCredentials.Entry?=DeepLCredentials(context).read(),
 private val transport:((String,String,String)->Pair<Int,String>)?=null):TranslationEngine {
 @Volatile private var closed=false
 @Volatile private var connection:HttpURLConnection?=null
 private val worker=Executors.newSingleThreadExecutor()
 private val ui=Handler(Looper.getMainLooper())
 override val attribution get()="DeepL"
 override val requiresOnlineConsent get()=true
 override val configurationIssue get()=if(credential==null) R.string.translation_deepl_missing else null
 override var failureMessage:Int?=null
  private set
 override fun available(done:(Boolean)->Unit,failed:()->Unit){if(!closed)done(credential!=null)}
 override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit){if(!closed)failed()}
 override fun translate(text:String,done:(String)->Unit,failed:()->Unit) = translateBatch(listOf(text),{done(it.single())},failed)
 override fun translateBatch(texts:List<String>,done:(List<String>)->Unit,failed:()->Unit,cancelled:()->Boolean) {
  if(closed || cancelled())return
  val auth=credential ?: run {failureMessage=R.string.translation_deepl_missing;failed();return}
  if(texts.isEmpty()){done(emptyList());return}
  if(texts.size>129 || texts.sumOf{it.length}>8000){failed();return}
  worker.execute {
   try {
    val url=if(auth.free) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
    val results=mutableListOf<String>()
    for(batch in texts.chunked(20)) {
     if(closed || cancelled())return@execute
     val body=payload(batch,source,target).toString()
     val (code,response)=transport?.invoke(url,auth.key,body) ?: request(url,auth.key,body)
     if(code !in 200..299) {
      failureMessage=when(code){403->R.string.translation_deepl_key_error;456->R.string.translation_deepl_quota;429->R.string.translation_deepl_busy;400->R.string.translation_deepl_languages;else->R.string.translation_deepl_network}
      ui.post{if(!closed && !cancelled())failed()};return@execute
     }
     val array=JSONObject(response).getJSONArray("translations")
     require(array.length()==batch.size)
     results.addAll(List(array.length()){array.getJSONObject(it).getString("text").also{value->require(value.isNotBlank())}})
     require(results.sumOf{it.length}<=32000)
    }
    ui.post{if(!closed && !cancelled())done(results)}
   }catch(_:Exception){failureMessage=R.string.translation_deepl_network;ui.post{if(!closed && !cancelled())failed()}}
  }
 }
 private fun request(url:String,key:String,body:String):Pair<Int,String> {
  val c=URL(url).openConnection() as HttpURLConnection;connection=c
  try {
   c.instanceFollowRedirects=false;c.requestMethod="POST";c.connectTimeout=15000;c.readTimeout=30000;c.doOutput=true
   c.setRequestProperty("Authorization","DeepL-Auth-Key "+key);c.setRequestProperty("Content-Type","application/json; charset=utf-8")
   c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
   val code=c.responseCode
   val stream=if(code in 200..299)c.inputStream else c.errorStream
   val bytes=stream?.use { input ->
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) {val size=input.read(buffer);if(size<0)break;require(output.size()+size<=512000);output.write(buffer,0,size)}
    output.toByteArray()
   } ?: byteArrayOf()
   return code to String(bytes,Charsets.UTF_8)
  }finally{c.disconnect();connection=null}
 }
 override fun close(){closed=true;connection?.disconnect();worker.shutdownNow()}
 companion object {
  fun payload(text:String,source:String,target:String):JSONObject = payload(listOf(text),source,target)
  fun payload(texts:List<String>,source:String,target:String):JSONObject = JSONObject()
   .put("text",JSONArray(texts)).put("source_lang",source.uppercase(Locale.ROOT))
   .put("target_lang",if(target=="en")"EN-GB" else target.uppercase(Locale.ROOT))
   .put("preserve_formatting",true).put("split_sentences","nonewlines")
 }
}
