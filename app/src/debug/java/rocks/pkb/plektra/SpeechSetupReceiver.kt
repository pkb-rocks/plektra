package rocks.pkb.plektra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import rocks.pkb.plektra.inputmethod.speech.*
import java.io.File

/** Debug-only shell entry point. Credentials arrive through private app storage, never intent extras. */
class SpeechSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = File(context.cacheDir, "speech-setup.json")
        try {
            val data = JSONObject(pending.readText())
            val keys = listOf(SpeechProvider.OPENROUTER, SpeechProvider.OPENAI).mapNotNull { service ->
                val field = "${service.name.lowercase(java.util.Locale.ROOT)}_key"
                if (data.has(field)) service to data.getString(field) else null
            }
            require(keys.isNotEmpty())
            require(keys.all { (_, key) -> key.isNotBlank() && key.none { it.isWhitespace() } })
            val provider = if (data.has("provider")) SpeechProvider.valueOf(data.getString("provider")) else null
            val silenceMs = if (data.has("silence_ms")) data.getLong("silence_ms") else null
            require(silenceMs == null || silenceMs in listOf(0L, 1500L, 3000L, 5000L))
            val models = listOf(SpeechProvider.OPENROUTER, SpeechProvider.OPENAI).mapNotNull { service ->
                val field = "${service.name.lowercase(java.util.Locale.ROOT)}_model"
                if (!data.has(field)) return@mapNotNull null
                val id = data.getString(field)
                require(id.isNotBlank())
                val defaults = if (service == SpeechProvider.OPENROUTER) SpeechModels.openRouterDefaults else SpeechModels.openAiDefaults
                service to (defaults.firstOrNull { it.id == id } ?: SpeechModel(id, id))
            }
            keys.forEach { (service, key) ->
                SpeechSettings.saveKey(context, service, key)
                check(SpeechSettings.readKey(context, service) == key)
            }
            models.forEach { (service, model) -> SpeechSettings.setModel(context, service, model) }
            silenceMs?.let { SpeechSettings.setSilenceMs(context, it) }
            provider?.let { SpeechSettings.setProvider(context, it) }
            resultCode = 0
            resultData = "Speech configuration saved; encrypted keys verified"
        } catch (_: Exception) {
            resultCode = 1
            resultData = "Speech configuration failed"
        } finally {
            pending.delete()
        }
    }
}
