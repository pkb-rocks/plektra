package rocks.pkb.plektra.inputmethod.speech

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/** FUTO's auxiliary voice IME owns the editor connection and switches back after committing. */
object ExternalSpeechProvider {
    const val FUTO_PACKAGE = "org.futo.voiceinput"
    const val FUTO_DOWNLOAD_URL = "https://app.futo.org/fdroid/repo/"

    data class VoiceInput(val id: String, val subtype: InputMethodSubtype)

    fun resolveVoiceInput(methods: List<InputMethodInfo>): VoiceInput? = methods.asSequence()
        .filter { it.packageName == FUTO_PACKAGE }
        .mapNotNull { method ->
            (0 until method.subtypeCount).map(method::getSubtypeAt)
                .firstOrNull { it.mode == "voice" && it.isAuxiliary }
                ?.let { VoiceInput(method.id, it) }
        }.firstOrNull()

    fun enabledVoiceInput(context: Context): VoiceInput? = resolveVoiceInput(
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList)

    fun isAvailable(context: Context): Boolean = resolveVoiceInput(
        context.getSystemService(InputMethodManager::class.java).inputMethodList) != null

    fun handOff(
        service: InputMethodService,
        voiceInput: VoiceInput,
        requestShow: () -> Unit = { service.requestShowSelf(InputMethodManager.SHOW_IMPLICIT) }
    ): Boolean {
        val connection = service.currentInputConnection ?: return false
        connection.finishComposingText()
        requestShow()
        service.switchInputMethod(voiceInput.id, voiceInput.subtype)
        return true
    }

    fun settingsIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(FUTO_PACKAGE)
}
