package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Account counters only. Never sends source text; the cache is not exported. */
internal class DeepLUsage(private val context:Context,
 private val credential:()->DeepLCredentials.Entry?={DeepLCredentials(context).read()},
 private val transport:((String,String)->Pair<Int,String>)?=null):AutoCloseable {
 data class Snapshot(val remaining:Long,val checkedAt:Long)
 private val file=File(context.noBackupFilesDir,"deepl-usage-v1")
 private val worker=Executors.newSingleThreadExecutor()
 private val ui=Handler(Looper.getMainLooper())
 @Volatile private var closed=false
 @Volatile private var connection:HttpURLConnection?=null
 private fun identity(auth:DeepLCredentials.Entry)=MessageDigest.getInstance("SHA-256").digest((auth.key+":"+auth.free).toByteArray()).joinToString(""){"%02x".format(it)}
 fun cached():Snapshot?=runCatching {
  val auth=credential() ?: return null
  val json=JSONObject(file.readText(Charsets.UTF_8));require(json.getString("identity")==identity(auth))
  Snapshot(json.getLong("remaining").also{require(it>=0)},json.getLong("checkedAt"))
 }.getOrNull()
 fun refresh(done:(Snapshot)->Unit,failed:()->Unit) {
  if(closed)return
  val auth=credential() ?: run{failed();return}
  worker.execute {
   try {
    val url=if(auth.free)"https://api-free.deepl.com/v2/usage" else "https://api.deepl.com/v2/usage"
    val(code,body)=transport?.invoke(url,auth.key) ?: request(url,auth.key)
    require(code==200);val snapshot=parse(body)
    if(closed || credential()?.let{identity(it)}!=identity(auth))return@execute
    val output=android.util.AtomicFile(file);val stream=output.startWrite()
    try {stream.write(JSONObject().put("identity",identity(auth)).put("remaining",snapshot.remaining).put("checkedAt",snapshot.checkedAt).toString().toByteArray());output.finishWrite(stream)}catch(error:Exception){output.failWrite(stream);throw error}
    ui.post{if(!closed)done(snapshot)}
   }catch(_:Exception){ui.post{if(!closed)failed()}}
  }
 }
 fun cacheVerified(auth:DeepLCredentials.Entry,snapshot:Snapshot):Boolean {
  if(closed||credential()?.let{identity(it)}!=identity(auth))return false
  val output=android.util.AtomicFile(file);val stream=output.startWrite()
  try{stream.write(JSONObject().put("identity",identity(auth)).put("remaining",snapshot.remaining).put("checkedAt",snapshot.checkedAt).toString().toByteArray());output.finishWrite(stream)}catch(error:Exception){output.failWrite(stream);throw error}
  return true
 }
 private fun request(url:String,key:String):Pair<Int,String> {
  val client=URL(url).openConnection() as HttpURLConnection;connection=client
  try {
   client.instanceFollowRedirects=false;client.requestMethod="GET";client.connectTimeout=10000;client.readTimeout=10000
   client.setRequestProperty("Authorization","DeepL-Auth-Key "+key)
   val status=client.responseCode
   if(status!=200)return status to ""
   val body=client.inputStream.bufferedReader(Charsets.UTF_8).use{reader->
    val output=StringBuilder();val buffer=CharArray(2048)
    while(true){val size=reader.read(buffer);if(size<0)break;require(output.length+size<=64000);output.append(buffer,0,size)}
    output.toString()
   };return status to body
  }finally{client.disconnect();connection=null}
 }
 override fun close(){closed=true;connection?.disconnect();worker.shutdownNow()}
 companion object {
  fun label(context:Context,snapshot:Snapshot?):String = if(snapshot==null) context.getString(rocks.pkb.plektra.R.string.translation_deepl_usage_unknown)
   else context.getString(rocks.pkb.plektra.R.string.translation_deepl_remaining,java.text.NumberFormat.getIntegerInstance().format(snapshot.remaining))
  fun parse(body:String):Snapshot {
   val data=JSONObject(body)
   val used=data.getLong("character_count");val limit=data.getLong("character_limit")
   require(used>=0 && limit>=0)
   var remaining=(limit-used).coerceAtLeast(0)
   if(data.has("api_key_character_limit") && !data.isNull("api_key_character_limit") && data.has("api_key_character_count") && !data.isNull("api_key_character_count")) {
    val keyLimit=data.getLong("api_key_character_limit");val keyUsed=data.getLong("api_key_character_count")
    require(keyLimit>=0 && keyUsed>=0);remaining=minOf(remaining,(keyLimit-keyUsed).coerceAtLeast(0))
   }
   return Snapshot(remaining,System.currentTimeMillis())
  }
 }
}
