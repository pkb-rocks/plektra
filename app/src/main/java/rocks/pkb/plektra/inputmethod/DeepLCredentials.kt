package rocks.pkb.plektra.inputmethod
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-owned credential; encrypted with a device key, never included in ZIP or Android backup. */
internal class DeepLCredentials(context:Context,private val keyProvider:()->SecretKey={deviceKey()}) {
 data class Entry(val key:String,val free:Boolean)
 private val file=AtomicFile(File(context.noBackupFilesDir,"deepl-credential-v1"))
 fun read():Entry? = runCatching {
  val bytes=file.openRead().use{it.readBytes()};require(bytes.size in 30..4096)
  val ivSize=bytes[0].toInt() and 255;require(ivSize==12)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,keyProvider(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
  val data=JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
  Entry(data.getString("key"),data.getBoolean("free"))
 }.getOrNull()
 fun write(key:String,free:Boolean) {
  require(key.length in 8..256 && key.all{it.code in 33..126})
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keyProvider())
  val plain=JSONObject().put("key",key).put("free",free).toString().toByteArray(Charsets.UTF_8)
  val encrypted=cipher.doFinal(plain);val out=file.startWrite()
  try {out.write(byteArrayOf(cipher.iv.size.toByte()));out.write(cipher.iv);out.write(encrypted);file.finishWrite(out)}
  catch(e:Exception){file.failWrite(out);throw e}
 }
 fun delete()=file.delete()
 companion object {
  @Synchronized private fun deviceKey():SecretKey {
   val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   (store.getKey("pastiera.deepl.v1",null) as? SecretKey)?.let{return it}
   return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
    init(KeyGenParameterSpec.Builder("pastiera.deepl.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
     .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
   }.generateKey()
  }
 }
}
