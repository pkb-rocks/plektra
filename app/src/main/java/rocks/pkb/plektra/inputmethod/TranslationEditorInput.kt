package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import rocks.pkb.plektra.SettingsManager
import kotlin.math.abs

/** Edits only the proposal. No access to the original field's InputConnection. */
internal class TranslationEditorInput(private val context: Context, private val editor: EditText,
    private val cycleLanguage: () -> Unit = {
        SubtypeCycler.cycleToNextSubtype(context,PhysicalKeyboardInputMethodService::class.java,context.assets,
            showToast=SettingsManager.isToastOnLayoutSwitchEnabled(context));Unit
    }) {
    private var navigation=false
    private var lastFnRelease=-1L
    private val navMappings by lazy {rocks.pkb.plektra.data.mappings.KeyMappingLoader.loadCtrlKeyMappings(context.assets,context)}
    private var ctrlHeld = false
    private var ctrlTap = false
    private var ctrlUsed = false
    private val shortcutKeys = mutableSetOf<Int>()
    private var contact = false
    private var downX = 0f
    private var downY = 0f
    private var startNormalized = 0f
    private var downTime = 0L
    private var deleted = false

    fun hasCtrlModifier(event: KeyEvent): Boolean = ctrlHeld || ctrlTap || event.isCtrlPressed
    fun key(original: KeyEvent): Boolean {
        val mapped = DeviceSpecific.remapHardwareKeyEvent(original.keyCode, original,
            SettingsManager.getPhysicalKeyboardProfileOverride(context))
        val event = mapped.event ?: original
        val code = mapped.keyCode
        val modifier = code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT ||
            code == KeyEvent.KEYCODE_FUNCTION || (original.scanCode == 251 && DeviceSpecific.isTitan2Device())
        if (modifier) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val double=SettingsManager.getNavModeEnabled(context) &&
                    ctrlTap && lastFnRelease>=0 && event.eventTime-lastFnRelease in 0..299
                if(double) {navigation=true;ctrlHeld=true;ctrlUsed=true;ctrlTap=false;lastFnRelease=-1}
                else {navigation=false;ctrlHeld=true;ctrlUsed=false;ctrlTap=false}
            } else if (event.action == KeyEvent.ACTION_UP) {
                ctrlHeld = false; ctrlTap = !ctrlUsed && !navigation
                lastFnRelease=if(ctrlTap) event.eventTime else -1
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && shortcutKeys.remove(code)) return true
        if(event.action==KeyEvent.ACTION_DOWN && navigation) {shortcutKeys.add(code);navigate(code,event);return true}
        if(event.action==KeyEvent.ACTION_DOWN)lastFnRelease=-1
        val ctrl = ctrlHeld || ctrlTap || event.isCtrlPressed
        if (event.action == KeyEvent.ACTION_DOWN && ctrl) {
            ctrlTap = false; ctrlUsed = true; shortcutKeys.add(code)
            if(code==KeyEvent.KEYCODE_SPACE && SettingsManager.isCtrlSpaceLayoutSwitchEnabled(context)) {
                if(event.repeatCount==0) cycleLanguage()
                return true
            }
            if(code==KeyEvent.KEYCODE_V && (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.let{it.itemCount>0 && it.getItemAt(0).uri!=null}==true) {
                android.widget.Toast.makeText(context,rocks.pkb.plektra.R.string.translation_text_only,android.widget.Toast.LENGTH_LONG).show();return true
            }
            val action = when (code) {
                KeyEvent.KEYCODE_A -> android.R.id.selectAll
                KeyEvent.KEYCODE_C -> android.R.id.copy
                KeyEvent.KEYCODE_X -> android.R.id.cut
                KeyEvent.KEYCODE_V -> android.R.id.paste
                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) android.R.id.redo else android.R.id.undo
                KeyEvent.KEYCODE_Y -> android.R.id.redo
                else -> null
            }
            if (action != null) editor.onTextContextMenuItem(action)
            else {
                val native = KeyEvent(event.downTime, event.eventTime, event.action, code,
                    event.repeatCount, event.metaState or KeyEvent.META_CTRL_ON,
                    event.deviceId, event.scanCode, event.flags, event.source)
                if (!editor.onKeyShortcut(code, native)) editor.onKeyDown(code, native)
            }
            return true
        }
        if(event.action==KeyEvent.ACTION_DOWN && !event.isAltPressed && !event.isMetaPressed) {
            val upper=event.isShiftPressed || event.isCapsLockOn ||
                android.text.method.MetaKeyKeyListener.getMetaState(editor.text,KeyEvent.META_SHIFT_ON)!=0
            val mapping=rocks.pkb.plektra.data.layout.LayoutMappingRepository.getMapping(code)
            val value=mapping?.let { entry -> rocks.pkb.plektra.data.layout.LayoutMappingRepository.resolveText(entry,upper) }
            if(!value.isNullOrEmpty()) {
                val start=minOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(0)
                val end=maxOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(start)
                editor.text.replace(start,end,value);editor.setSelection(start+value.length)
                android.text.method.MetaKeyKeyListener.adjustMetaAfterKeypress(editor.text)
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) editor.onKeyDown(code, event)
        else if (event.action == KeyEvent.ACTION_UP) editor.onKeyUp(code, event)
        return true
    }

    private fun navigate(code:Int,event:KeyEvent) {
        if(code==KeyEvent.KEYCODE_ESCAPE){navigation=false;return}
        val mapping=navMappings[code] ?: return
        fun native(key:Int,select:Boolean=false,word:Boolean=false) {
            val meta=(if(select) KeyEvent.META_SHIFT_ON else 0) or (if(word)KeyEvent.META_CTRL_ON else 0)
            val keyEvent=KeyEvent(event.downTime,event.eventTime,KeyEvent.ACTION_DOWN,key,event.repeatCount,meta)
            editor.onKeyDown(key,keyEvent)
            editor.onKeyUp(key,KeyEvent.changeAction(keyEvent,KeyEvent.ACTION_UP))
        }
        fun horizontal(right:Boolean,select:Boolean,word:Boolean=false) {
            val anchor=editor.selectionStart.coerceAtLeast(0);val current=editor.selectionEnd.coerceAtLeast(0)
            val text=editor.text
            val next=if(word) {
                val boundary=java.text.BreakIterator.getWordInstance().apply{setText(text.toString())}
                (if(right)boundary.following(current) else boundary.preceding(current)).let{if(it<0)if(right)text.length else 0 else it}
            } else if(right && current<text.length)Character.offsetByCodePoints(text,current,1)
              else if(!right && current>0)Character.offsetByCodePoints(text,current,-1) else current
            if(select)editor.setSelection(anchor,next) else editor.setSelection(next)
        }
        val actions=mapOf("select_all" to android.R.id.selectAll,"copy" to android.R.id.copy,"cut" to android.R.id.cut,
            "paste" to android.R.id.paste,"undo" to android.R.id.undo,"redo" to android.R.id.redo)
        when(mapping.type) {
            "keycode" -> {val key=KeyEvent.keyCodeFromString("KEYCODE_"+mapping.value);if(key==KeyEvent.KEYCODE_ESCAPE)navigation=false else if(key!=KeyEvent.KEYCODE_UNKNOWN)native(key)}
            "native_ctrl" -> {val key=KeyEvent(event.downTime,event.eventTime,KeyEvent.ACTION_DOWN,code,event.repeatCount,KeyEvent.META_CTRL_ON);editor.onKeyShortcut(code,key)}
            "action" -> when(mapping.value) {
                in actions -> editor.onTextContextMenuItem(actions.getValue(mapping.value))
                "expand_selection_left" -> horizontal(false,true)
                "expand_selection_right" -> horizontal(true,true)
                "expand_selection_word_left" -> horizontal(false,true,true)
                "expand_selection_word_right" -> horizontal(true,true,true)
                "move_word_left" -> horizontal(false,false,true)
                "move_word_right" -> horizontal(true,false,true)
                "expand_selection_up" -> native(KeyEvent.KEYCODE_DPAD_UP,true)
                "expand_selection_down" -> native(KeyEvent.KEYCODE_DPAD_DOWN,true)
                else -> Unit // Global commands never act on the original app from this local editor.
            }
        }
    }

    private fun deleteWord() {
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        var from = start
        if (start == end) {
            while (from > 0 && editor.text[from - 1].isWhitespace()) from--
            while (from > 0 && !editor.text[from - 1].isWhitespace()) from--
        }
        if (from < end) { editor.text.delete(from, end); editor.setSelection(from) }
    }

    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD) &&
            InputDevice.getDevice(event.deviceId)?.name?.contains("touchpad", true) != true) return false
        if (event.pointerCount != 1 || !event.x.isFinite() || !event.y.isFinite()) { contact = false; return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                contact = true; deleted = false; downX = event.x; downY = event.y; downTime = event.eventTime
                startNormalized = TranslationGestures.coordinate(event, MotionEvent.AXIS_X, context.resources.displayMetrics.widthPixels)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (contact && !deleted) {
                val left = downX - event.x; val vertical = event.y - downY
                val duration = (event.eventTime - downTime).coerceAtLeast(1)
                val accepts = (left >= SettingsManager.getTrackpadDeleteSwipeThreshold(context) && abs(vertical) < left / 4f && left / duration >= .35f)
                if (SettingsManager.getSwipeToDelete(context) && accepts) { deleteWord(); deleted = true }
            }
            MotionEvent.ACTION_CANCEL -> contact = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) contact = false
        // A proposal contact must never reach the original field's suggestion/deletion handlers.
        return true
    }
}
