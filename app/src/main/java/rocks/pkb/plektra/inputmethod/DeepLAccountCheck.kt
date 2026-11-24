package rocks.pkb.plektra.inputmethod

import android.os.Handler
import android.os.Looper
import rocks.pkb.plektra.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Validates a user-owned key using account counters only. Never sends translation text. */
internal class DeepLAccountCheck(private val transport:((String,String)->Pair<Int,String>)?=null):AutoCloseable {
 data class Verified(val credential:DeepLCredentials.Entry,val usage:DeepLUsage.Snapshot?)
 class Rejected(val status:Int):Exception()
 private val worker=Executors.newSingleThreadExecutor()
 private val ui=Handler(Looper.getMainLooper())
 @Volatile private var closed=false
 @Volatile private var connection:HttpURLConnection?=null
 fun verify(auth:DeepLCredentials.Entry,done:(Verified)->Unit,failed:(Int)->Unit){
  if(closed)return
  worker.execute{try{
   val result=probe(auth){url,key->check(!closed);transport?.invoke(url,key) ?: request(url,key)}
   ui.post{if(!closed)done(result)}
  }catch(error:Exception){
   val message=when((error as? Rejected)?.status){403->R.string.translation_deepl_key_error;429->R.string.translation_deepl_busy;456->R.string.translation_deepl_quota;else->R.string.translation_deepl_network}
   ui.post{if(!closed)failed(message)}
  }}
 }
 private fun request(url:String,key:String):Pair<Int,String>{
  val client=URL(url).openConnection() as HttpURLConnection;connection=client
  try{
   check(!closed);client.instanceFollowRedirects=false;client.requestMethod="GET";client.connectTimeout=10000;client.readTimeout=10000
   client.setRequestProperty("Authorization","DeepL-Auth-Key "+key)
   val status=client.responseCode
   if(status!=200)return status to ""
   val body=client.inputStream.bufferedReader(Charsets.UTF_8).use{reader->
    val text=StringBuilder();val buffer=CharArray(2048)
    while(true){val n=reader.read(buffer);if(n<0)break;require(text.length+n<=64000);text.append(buffer,0,n)}
    text.toString()
   }
   return status to body
  }finally{client.disconnect();connection=null}
 }
 override fun close(){closed=true;connection?.disconnect();worker.shutdownNow()}
 companion object {
  fun probe(auth:DeepLCredentials.Entry,request:(String,String)->Pair<Int,String>):Verified {
   for(free in listOf(auth.free,!auth.free)){
    val url=if(free)"https://api-free.deepl.com/v2/usage" else "https://api.deepl.com/v2/usage"
    val (status,body)=request(url,auth.key)
    if(status==200)return Verified(auth.copy(free=free),runCatching{DeepLUsage.parse(body)}.getOrNull())
    if(status!=403)throw Rejected(status)
   }
   throw Rejected(403)
  }
 }
}
