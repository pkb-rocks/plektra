package rocks.pkb.plektra.inputmethod

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.*
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import com.google.mlkit.nl.translate.TranslateLanguage
import rocks.pkb.plektra.R
import java.util.Locale
import java.util.UUID

/** An IME-owned floating transaction; the original editor remains the guarded commit target. */
internal class FieldTranslationPanel(private val context: Context, private val host: LinearLayout,
    private val session: FieldTranslationSession, private val locale: Locale,
    private val isCurrent: () -> Boolean, private val onClosed: () -> Unit,
    private val engineFactory: (String,String) -> TranslationEngine = { a,b -> LocalTranslationEngine(a,b).let{if(rocks.pkb.plektra.SettingsManager.getPreferences(context).getBoolean(TranslationPunctuation.PREFERENCE,true))PunctuationTranslationEngine(it,b)else it} }) {
    private enum class Stage { LANGUAGES, CONSENT, WORKING, PREVIEW, EDITING, ERROR }
    private val colors = TranslationTheme.colors(context)
    private val prefs = context.getSharedPreferences("translation_preferences",Context.MODE_PRIVATE)
    private val displayLocale = context.resources.configuration.locales[0]
    private val languages = TranslateLanguage.getAllLanguages().sortedBy { Locale.forLanguageTag(it).getDisplayLanguage(displayLocale) }
    private var source = prefs.getString("source",locale.language).takeIf { it in languages } ?: "en"
    private var target = "en"
    private var stage = Stage.LANGUAGES
    private var fullList = false
    private var sourceList = false
    private var options = emptyList<String?>()
    private var selected = 0
    private var languageQuery = ""
    private var languageSearch: EditText? = null
    private var languageList: LinearLayout? = null
    private var clearLanguageSearch: ImageButton? = null
    private var closed = false
    private var generation = 0
    private val token = UUID.randomUUID().toString()
    private val gestures = TranslationGestures()
    private var engine: TranslationEngine? = null
    private var translated = ""
    private var previewChoice=0
    private var editingChoice = -1
    private var editor: EditText? = null
    private var editorInput: TranslationEditorInput? = null
    private var drag: TranslationWindowDrag? = null
    private var draggedPosition: Pair<Int,Int>? = null
    private var windowOrigin: Pair<Int,Int>? = null
    private var requestedWindowHeight: Int? = null
    private var resizeAnchor: Pair<android.graphics.Rect,TranslationWindowResize.Handle>? = null
    private var bottomContact = false
    private fun screenRect(): android.graphics.Rect {
        val location = IntArray(2); dialog?.window?.decorView?.getLocationOnScreen(location)
        return android.graphics.Rect(location[0],location[1],location[0]+root.width,location[1]+root.height)
    }
    private fun moveWindow(x: Int,y: Int) { resizeAnchor = null; placeWindow(x,y) }
    private fun repositionWindow() {
        val anchor = resizeAnchor
        if(anchor != null) {
            val (frame,corner) = anchor
            placeWindow(if(corner.horizontal && corner.left) frame.right-root.width else frame.left,
                if(corner.vertical && corner.top) frame.bottom-root.height else frame.top)
        } else draggedPosition?.let { placeWindow(it.first,it.second) }
    }
    internal fun resizeWindow(frame: android.graphics.Rect,corner: TranslationWindowResize.Handle,vertical: Boolean) {
        val window = dialog?.window ?: return
        val current = screenRect()
        placeWindow(current.left,current.top)
        resizeAnchor = android.graphics.Rect(frame) to corner
        requestedWindowHeight = if(vertical) frame.height() else null
        window.setLayout(frame.width(),WindowManager.LayoutParams.WRAP_CONTENT)
        scroll?.requestLayout()
        root.requestLayout()
    }
    private val resize: TranslationWindowResize by lazy {
        TranslationWindowResize(root,{screenRect()},{visibleBounds()},
            { dp(260) to (root.height-(scroll?.height ?: 0)+dp(64)) },
            { frame,corner,vertical -> resizeWindow(frame,corner,vertical) },bottomInset=dp(18))
    }
    private val bottomDrag: TranslationWindowDrag by lazy {
        TranslationWindowDrag(root,{ val frame=screenRect(); frame.left to frame.top },
            {visibleBounds()},{root.width to root.height},{x,y->moveWindow(x,y)},requireLongPress=false)
    }
    private fun visibleBounds(): android.graphics.Rect {
        val frame = android.graphics.Rect()
        root.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) frame.set(0,0,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)
        val status = context.resources.getIdentifier("status_bar_height","dimen","android")
        val statusHeight = if(status == 0) 0 else context.resources.getDimensionPixelSize(status)
        val insets = root.rootWindowInsets
        val statusVisible = if(android.os.Build.VERSION.SDK_INT >= 30 && insets != null)
            insets.isVisible(WindowInsets.Type.statusBars()) else true
        if(statusVisible) frame.top = maxOf(frame.top,statusHeight)
        val keyboard = IntArray(2); host.getLocationOnScreen(keyboard)
        if (host.isShown && keyboard[1] > frame.top) frame.bottom = minOf(frame.bottom,keyboard[1])
        return frame
    }
    private fun placeWindow(x: Int,y: Int) {
        val window = dialog?.window ?: return
        val frame = visibleBounds()
        if(windowOrigin == null) {
            val screen = IntArray(2); window.decorView.getLocationOnScreen(screen)
            val centered = window.attributes.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL
            val centerOffset = if(centered) (context.resources.displayMetrics.widthPixels-window.decorView.width)/2 else 0
            windowOrigin = (screen[0]-window.attributes.x-centerOffset) to (screen[1]-window.attributes.y)
        }
        val point = TranslationWindowDrag.constrain(x,y,window.decorView.width,window.decorView.height,frame)
        draggedPosition = point
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = point.first - windowOrigin!!.first; this.y = point.second - windowOrigin!!.second
        }
    }
    private var scroll: ScrollView? = null
    private val rows = ArrayList<TextView>()
    private var dialog: Dialog? = null
    private fun dp(n: Int) = (n*context.resources.displayMetrics.density+.5f).toInt()
    private fun shape(fill: Int,radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private val root: LinearLayout = object : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if(resize.touch(event)) return true
            if(event.actionMasked == MotionEvent.ACTION_DOWN) bottomContact =
                !event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.pointerCount == 1 &&
                event.y >= height-paddingBottom-dp(18) && kotlin.math.abs(event.x-width/2f) <= dp(40)
            if(bottomContact) {
                if(event.pointerCount != 1) { bottomDrag.cancel(); bottomContact = false; return true }
                bottomDrag.onTouch(this,event)
                if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) bottomContact = false
                return true
            }
            return super.dispatchTouchEvent(event)
        }
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val ink = colors.onSurfaceVariant.toArgb()
            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(110,Color.red(ink),Color.green(ink),Color.blue(ink))
                strokeWidth = resources.displayMetrics.density; strokeCap = android.graphics.Paint.Cap.ROUND
            }
            for(left in listOf(true,false)) for(top in listOf(true,false)) {
                val x = if(left) dp(10).toFloat() else width-dp(10).toFloat()
                val y = if(top) dp(10).toFloat() else height-dp(10).toFloat()
                val sx = if(left) 1 else -1; val sy = if(top) 1 else -1
                for(length in listOf(5,9)) canvas.drawLine(x,y+sy*dp(length),x+sx*dp(length),y,pen)
            }
        }
    }.apply {
        id = R.id.field_translation_panel; orientation = LinearLayout.VERTICAL
        setPadding(dp(18),dp(14),dp(18),dp(8)); background = shape(colors.surface.toArgb(),22)
        addOnLayoutChangeListener { _,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if (right-left != oldRight-oldLeft || bottom-top != oldBottom-oldTop) {
                post { if(!closed) repositionWindow() }
            }
        }
    }
    internal val contentView: View get() = root
    private fun text(value: CharSequence,size: Float = 14f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(colors.onSurface.toArgb())
    }
    private fun button(label: Int,primary: Boolean = true,action: () -> Unit) = Button(context).apply {
        setText(label); isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        minHeight = dp(46); minimumHeight = dp(46); gravity = Gravity.CENTER
        setPadding(dp(12),0,dp(12),0); maxLines = 2
        setTextColor((if(primary) colors.onPrimary else colors.primary).toArgb())
        background = shape((if(primary) colors.primary else colors.surfaceContainerLow).toArgb(),23)
        setOnClickListener { action() }
    }
    private fun action(label: Int,id: Int,primary: Boolean = true,callback: () -> Unit) {
        root.addView(button(label,primary,callback).apply { this.id = id },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin = dp(8) })
    }
    private fun clear(next: Stage,title: Int) {
        stage = next; editor = null; editorInput = null; drag?.cancel(); bottomDrag.cancel(); resize.cancel(); bottomContact = false
        languageSearch = null; languageList = null; clearLanguageSearch = null
        root.removeAllViews(); rows.clear(); scroll = null
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = text(context.getString(title),19f).apply {
            setTypeface(null,Typeface.BOLD)
            contentDescription = context.getString(title) + ". " + context.getString(R.string.translation_drag_hint)
        }
        header.addView(heading,LinearLayout.LayoutParams(0,dp(44),1f))
        heading.gravity = Gravity.CENTER_VERTICAL
        drag = TranslationWindowDrag(heading,
            position = { val xy = IntArray(2); dialog?.window?.decorView?.getLocationOnScreen(xy); xy[0] to xy[1] },
            bounds = { visibleBounds() },
            size = { (dialog?.window?.decorView?.width ?: root.width) to (dialog?.window?.decorView?.height ?: root.height) },
            move = { x,y -> moveWindow(x,y) })
        heading.setOnTouchListener(drag)
        header.addView(ImageButton(context).apply {
            id = R.id.field_translation_close; setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(colors.onSurfaceVariant.toArgb()); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.translation_close); setPadding(dp(10),dp(10),dp(10),dp(10))
            setOnClickListener { close() }
        },LinearLayout.LayoutParams(dp(44),dp(44)))
        root.addView(header)
        dialog?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if(next == Stage.LANGUAGES) clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            else { addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.18f) }
            if (draggedPosition == null) attributes = attributes.apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = maxOf(dp(24),visibleBounds().top) }
        }
        root.post { if(!closed) repositionWindow() }
    }
    private fun measureChrome(body: View,contentWidth: Int): Int {
        var total = root.paddingTop+root.paddingBottom
        for(index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if(child === body || child.visibility == View.GONE) continue
            val params = child.layoutParams
            val margins = params as? ViewGroup.MarginLayoutParams
            val childWidth = (contentWidth-(margins?.leftMargin ?: 0)-(margins?.rightMargin ?: 0)).coerceAtLeast(0)
            val childHeight = if(params.height >= 0) View.MeasureSpec.makeMeasureSpec(params.height,View.MeasureSpec.EXACTLY)
                else View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
            child.measure(View.MeasureSpec.makeMeasureSpec(if(params.width >= 0) params.width else childWidth,View.MeasureSpec.EXACTLY),childHeight)
            total += child.measuredHeight+(margins?.topMargin ?: 0)+(margins?.bottomMargin ?: 0)
        }
        return total
    }
    private fun footer() {
        root.addView(FrameLayout(context).apply {
            id = R.id.field_translation_move_handle
            contentDescription = context.getString(R.string.translation_move_handle)
            addView(View(context).apply { background = shape(colors.onSurfaceVariant.toArgb(),2).apply { alpha = 100 } },
                FrameLayout.LayoutParams(dp(32),dp(3),Gravity.CENTER))
        },LinearLayout.LayoutParams(-1,dp(18)))
    }
    private fun body(view: View,menu: Boolean = false) {
        val scrolling = object : ScrollView(context) {
            override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
                super.onLayout(changed,left,top,right,bottom)
                if(this === scroll && stage == Stage.LANGUAGES) keepLanguageVisible()
            }
            override fun onMeasure(width: Int,height: Int) {
                val chrome = measureChrome(this,View.MeasureSpec.getSize(width))
                val available = visibleBounds().height()
                val desired = minOf(requestedWindowHeight ?: available,available)
                val naturalCap = (resources.displayMetrics.heightPixels * if(menu) .44f else .38f).toInt()
                val explicit = requestedWindowHeight != null
                val cap = if(explicit) (desired-chrome).coerceAtLeast(0) else minOf(naturalCap,(desired-chrome).coerceAtLeast(0))
                super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(cap,if(explicit) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(view); isFillViewport = false; if(menu) id = R.id.field_translation_languages_scroll }
        scroll = scrolling
        root.addView(scrolling,LinearLayout.LayoutParams(-1,-2))
    }
    fun start() {
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val popup = object : Dialog(context,if(night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar) {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if(motion(event)) true else super.dispatchGenericMotionEvent(event)
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = if(motion(event)) true else super.dispatchTouchEvent(event)
        }
        popup.setContentView(root); popup.setCanceledOnTouchOutside(true)
        popup.setOnDismissListener { close() }
        popup.setOnKeyListener { _,_,event -> key(event) }
        val imeService = context as? InputMethodService
        val imeWindow = imeService?.window?.window
        popup.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            if(imeService != null) attributes = attributes.apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                token = imeWindow?.decorView?.windowToken ?: host.windowToken
            }
        }
        dialog = popup
        menu()
        android.util.Log.d("KeyboardTranslation","open tokenPresent=${popup.window?.attributes?.token != null} type=${popup.window?.attributes?.type}")
        try { popup.show(); android.util.Log.d("KeyboardTranslation","shown=${popup.isShowing}") } catch (failure: WindowManager.BadTokenException) {
            android.util.Log.w("KeyboardTranslation","window_unavailable",failure)
            Toast.makeText(context,R.string.translation_error,Toast.LENGTH_LONG).show(); close(); return
        }
        popup.window?.setLayout(minOf(context.resources.displayMetrics.widthPixels-dp(32),dp(360)),WindowManager.LayoutParams.WRAP_CONTENT)
    }
    private fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(displayLocale).replaceFirstChar { it.titlecase(displayLocale) }
    private fun menu() {
        generation++; gestures.reset(); languageQuery = ""
        clear(Stage.LANGUAGES,if(sourceList) R.string.translation_choose_source else R.string.translation_choose_target)
        if(!sourceList) root.addView(text(context.getString(R.string.translation_source,languageName(source)),12f).apply {
            id = R.id.field_translation_source
            setTextColor(colors.onSurfaceVariant.toArgb()); setPadding(0,0,0,dp(8)); isClickable = true
            setOnClickListener { sourceList = true; fullList = true; menu() }
        })
        val searchRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shape(colors.surfaceContainerLow.toArgb(),12)
        }
        searchRow.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_search); setColorFilter(colors.onSurfaceVariant.toArgb())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(18),dp(18)).apply { leftMargin = dp(10) })
        val search = EditText(context).apply {
            id = R.id.field_translation_search; textSize = 15f; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(colors.onSurface.toArgb()); setHintTextColor(colors.onSurfaceVariant.toArgb())
            hint = context.getString(R.string.translation_search_language); contentDescription = hint
            setPadding(dp(8),0,dp(4),0); setBackgroundColor(Color.TRANSPARENT)
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        languageSearch = search
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(value: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(value: CharSequence?,start: Int,before: Int,count: Int) {}
            override fun afterTextChanged(value: android.text.Editable?) {
                if(stage != Stage.LANGUAGES || languageSearch !== search || closed) return
                languageQuery = value?.toString().orEmpty(); gestures.reset(); renderLanguages()
            }
        })
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(40),1f))
        val clear = ImageButton(context).apply {
            id = R.id.field_translation_search_clear; setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = context.getString(R.string.translation_search_clear)
            setColorFilter(colors.onSurfaceVariant.toArgb()); setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10),dp(10),dp(10),dp(10)); setOnClickListener { search.setText("") }
        }
        clearLanguageSearch = clear; searchRow.addView(clear,LinearLayout.LayoutParams(dp(36),dp(36)))
        root.addView(searchRow,LinearLayout.LayoutParams(-1,dp(40)).apply { bottomMargin = dp(8) })
        val list = LinearLayout(context).apply { id = R.id.field_translation_language_list; orientation = LinearLayout.VERTICAL }
        languageList = list; body(list,true)
        root.addView(text(context.getString(R.string.translation_gesture_hint),11f).apply { setTextColor(colors.onSurfaceVariant.toArgb()); setPadding(0,dp(8),0,0) })
        footer(); renderLanguages()
    }
    private fun renderLanguages() {
        val list = languageList ?: return
        list.removeAllViews(); rows.clear(); selected = 0
        val searching = languageQuery.isNotBlank()
        val codes = if(fullList || searching) languages.filter { code ->
            (sourceList || code != source) && TranslationLanguageSearch.matches(languageQuery,
                languageName(code),Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH),
                Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)),code)
        } else listOf("en","de","it","es","el","fr").filter { it != source }.take(5)
        options = codes + if(fullList || searching) emptyList() else listOf(null)
        clearLanguageSearch?.visibility = if(languageQuery.isEmpty()) View.INVISIBLE else View.VISIBLE
        options.forEachIndexed { i,code ->
            val row = text(code?.let(::languageName) ?: context.getString(R.string.translation_other_languages),16f).apply {
                if(code == "en" && !sourceList) id = R.id.field_translation_english
                if(code == null) id = R.id.field_translation_other
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),0)
                isClickable = true; isFocusable = true; setOnClickListener { selected = i; choose() }
            }
            rows.add(row); list.addView(row,LinearLayout.LayoutParams(-1,dp(44)).apply { bottomMargin = dp(3) })
        }
        if(options.isEmpty()) list.addView(text(context.getString(R.string.translation_search_empty),14f).apply {
            id = R.id.field_translation_search_empty; setPadding(dp(12),dp(12),dp(12),dp(12))
            setTextColor(colors.onSurfaceVariant.toArgb())
        })
        scroll?.scrollTo(0,0); paint()
    }
    private fun paint() {
        rows.forEachIndexed { i,row ->
            row.isSelected = i == selected
            row.background = shape((if(i == selected) colors.primaryContainer else colors.surface).toArgb(),12)
            row.setTextColor((if(i == selected) colors.onPrimaryContainer else colors.onSurface).toArgb())
        }
        scroll?.post { if(!closed && stage == Stage.LANGUAGES) keepLanguageVisible() }
    }
    private fun keepLanguageVisible() {
        val viewport = scroll ?: return
        val row = rows.getOrNull(selected) ?: return
        val height = viewport.height-viewport.paddingTop-viewport.paddingBottom
        if(height <= 0) return
        viewport.scrollTo(0,TranslationLanguageSearch.visibleOffset(row.top,row.bottom,viewport.scrollY,height))
    }
    private fun languageKey(event: KeyEvent): Boolean {
        when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveChoice(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveChoice(1)
            KeyEvent.KEYCODE_ENTER -> if(event.repeatCount == 0) choose()
            KeyEvent.KEYCODE_ESCAPE -> if(languageQuery.isNotEmpty()) languageSearch?.setText("") else close()
            KeyEvent.KEYCODE_DEL -> languageSearch?.let { field ->
                val start = minOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0)
                val end = maxOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0)
                if(start != end) field.text.delete(start,end)
                else if(start > 0) field.text.delete(Character.offsetByCodePoints(field.text,start,-1),start)
            }
            else -> {
                if(event.isCtrlPressed || event.isMetaPressed || KeyEvent.isModifierKey(event.keyCode)) return true
                val mapped = DeviceSpecific.remapHardwareKeyEvent(event.keyCode,event,
                    rocks.pkb.plektra.SettingsManager.getPhysicalKeyboardProfileOverride(context))
                val value = rocks.pkb.plektra.data.layout.LayoutMappingRepository.getCharacter(mapped.keyCode,event.isShiftPressed)
                    ?.toString() ?: event.unicodeChar.takeIf { Character.isValidCodePoint(it) && it > 0 }?.let { String(Character.toChars(it)) }
                if(value != null && value.all { it.isLetterOrDigit() || it == ' ' || it == '-' || it.code == 39 }) {
                    languageSearch?.let { field ->
                        val start = minOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0)
                        val end = maxOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0)
                        field.text.replace(start,end,value); field.setSelection((start+value.length).coerceAtMost(field.length()))
                    }
                }
            }
        }
        return true
    }
    private fun choose() {
        if(stage==Stage.PREVIEW){root.findViewById<Button>(if(previewChoice==0)R.id.field_translation_replace else R.id.field_translation_edit)?.performClick();return}

        if(stage != Stage.LANGUAGES || selected !in options.indices) return
        val code = options[selected]
        if(code == null) { fullList = true; menu(); return }
        if(sourceList) { source = code; sourceList = false; fullList = false; menu(); return }
        target = code; begin()
    }
    internal fun moveChoice(steps: Int) {
        if(stage==Stage.PREVIEW){previewChoice=(previewChoice+steps.coerceIn(-1,1)).coerceIn(0,1);paintPreviewChoice();return}

        if(stage != Stage.LANGUAGES || options.isEmpty()) return
        selected = (selected+steps).coerceIn(0,options.lastIndex); paint()
    }
    internal fun acceptChoice() = choose()
    private fun valid(version: Int): Boolean {
        if(closed || version != generation) return false
        if(!isCurrent()) { close(); return false }
        return true
    }
    private fun guarded(): Boolean {
        if(!isCurrent() || !session.unchanged()) { changed(); return false }
        return true
    }
    private fun status(value: Int) = text(context.getString(value),14f).apply {
        id = R.id.field_translation_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(0,dp(8),0,dp(12)); setTextColor(colors.onSurfaceVariant.toArgb())
    }
    private fun working() {
        clear(Stage.WORKING,R.string.status_bar_button_translation)
        root.addView(status(R.string.translation_working))
        root.addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true },LinearLayout.LayoutParams(-1,dp(4)))
        footer()
    }
    private fun begin() {
        if(!guarded()) return
        val version = ++generation; engine?.close()
        val client = engineFactory(source,target); engine = client; working()
        client.available({ available ->
            if(valid(version)) { if(available) translate(client,version) else consent(client,version) }
        },{ failure(version) })
    }
    private fun consent(client: TranslationEngine,version: Int) {
        clear(Stage.CONSENT,R.string.translation_download)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(status(R.string.translation_download_notice))
        val mobile = CheckBox(context).apply {
            id = R.id.field_translation_mobile; setText(R.string.translation_allow_mobile); textSize = 13f
            setTextColor(colors.onSurface.toArgb()); buttonTintList = android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
        }
        column.addView(mobile); body(column)
        action(R.string.translation_download,R.id.field_translation_run) {
            if(!valid(version) || !guarded()) return@action
            val wifi = !mobile.isChecked; working()
            client.download(wifi,{ if(valid(version)) translate(client,version) },{ failure(version) })
        }
        footer()
    }
    private fun translate(client: TranslationEngine,version: Int) {
        if(!guarded()) return
        working()
        client.translate(session.text,{ value ->
            if(!valid(version)) return@translate
            if(!guarded()) return@translate
            translated = value; prefs.edit().putString("source",source).putString("target",target).apply()
            preview(false)
        },{ failure(version) })
    }
    private fun paintPreviewChoice(){
        listOf(R.id.field_translation_replace,R.id.field_translation_edit).forEachIndexed{index,id->root.findViewById<Button>(id)?.apply{
            isSelected=index==previewChoice;setTextColor((if(isSelected)colors.onPrimary else colors.primary).toArgb())
            background=shape((if(isSelected)colors.primary else colors.surfaceContainerLow).toArgb(),23)
        }}
    }
    private fun preview(editing:Boolean){
        clear(if(editing)Stage.EDITING else Stage.PREVIEW,R.string.status_bar_button_translation)
        previewChoice=0;editingChoice = -1
        val value:TextView=if(editing) EditText(context).apply{
            id=R.id.field_translation_editor;setSelectAllOnFocus(false);showSoftInputOnFocus=false
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            setText(translated);setSelection(text.length);editor=this
        }else text(translated,17f).apply{id=R.id.field_translation_preview}
        value.setTextColor(colors.onSurface.toArgb());value.setPadding(dp(12),dp(12),dp(12),dp(12));value.background=shape(colors.surfaceContainerLow.toArgb(),14)
        body(value)
        root.addView(ImageView(context).apply{
            id=R.id.field_translation_attribution;setImageResource(R.drawable.translation_google_attribution)
            contentDescription="powered by Google Translate";scaleType=ImageView.ScaleType.FIT_START
        },LinearLayout.LayoutParams(dp(132),dp(18)).apply{topMargin=dp(8);gravity=Gravity.END})
        action(R.string.translation_accept,R.id.field_translation_replace){
            val final=editor?.text?.toString() ?: translated
            if(!isCurrent()||!session.replace(final)){changed();return@action};close()
        }
        action(if(editing)R.string.translation_discard_edits else R.string.translation_edit,R.id.field_translation_edit,false){preview(!editing)}
        if(!editing)paintPreviewChoice()
        footer()
        if(editing)editor?.let{editorInput=TranslationEditorInput(context,it);it.requestFocus()}
    }
    private fun moveEditing(steps:Int){
        editingChoice=(editingChoice+steps.coerceIn(-1,1)).coerceIn(-1,1)
        listOf(R.id.field_translation_replace,R.id.field_translation_edit).forEachIndexed{index,id->root.findViewById<Button>(id)?.apply{
            isSelected=index==editingChoice;background=shape((if(isSelected)colors.primaryContainer else colors.surfaceContainerLow).toArgb(),14)
        }}
    }
    private fun acceptEditing(){root.findViewById<Button>(if(editingChoice==1)R.id.field_translation_edit else R.id.field_translation_replace)?.performClick()}
    private fun failure(version: Int) {
        if(!valid(version)) return
        clear(Stage.ERROR,R.string.status_bar_button_translation); root.addView(status(R.string.translation_error))
        action(R.string.translation_translate,R.id.field_translation_run) { begin() }
        footer()
    }
    private fun changed() { Toast.makeText(context,R.string.translation_changed,Toast.LENGTH_LONG).show(); close() }
    /** Software keyboard text is confined to the proposal, never the source field. */
    fun commitSoftwareText(value:String):Boolean {
        if(closed)return false
        if(stage!=Stage.EDITING)return true
        editor?.let{field->val start=minOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0);val end=maxOf(field.selectionStart,field.selectionEnd).coerceAtLeast(start);field.text.replace(start,end,value);field.setSelection(start+value.length)}
        return true
    }
    fun key(event: KeyEvent): Boolean {
        if(closed) return false
        if(event.keyCode == KeyEvent.KEYCODE_BACK) { if(event.action == KeyEvent.ACTION_DOWN) close(); return true }
        if(stage == Stage.EDITING) {
            if(event.keyCode==KeyEvent.KEYCODE_TAB && editorInput?.hasCtrlModifier(event)!=true) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) moveEditing(if(event.isShiftPressed) -1 else 1)
                return true
            }
            if(editingChoice>=0 && editorInput?.hasCtrlModifier(event)!=true && (event.keyCode==KeyEvent.KEYCODE_ENTER || event.keyCode==KeyEvent.KEYCODE_SPACE)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) acceptEditing()
                return true
            }
            return editorInput?.key(event) ?: true
        }
        if(event.action != KeyEvent.ACTION_DOWN) return true
        if(stage == Stage.LANGUAGES) return languageKey(event)
        when(event.keyCode) {
            KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE -> close()
            KeyEvent.KEYCODE_DPAD_UP -> moveChoice(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveChoice(1)
            KeyEvent.KEYCODE_ENTER -> if(event.repeatCount == 0) choose()
        }
        return true
    }
    fun motion(event:MotionEvent):Boolean {
        if(closed)return false
        val editing=stage==Stage.EDITING
        val handled=gestures.motion(context.packageName,session.info,session.input,event,
            TranslationGestures.coordinate(event,MotionEvent.AXIS_Y,context.resources.displayMetrics.heightPixels),
            TranslationGestures.coordinate(event,MotionEvent.AXIS_X,context.resources.displayMetrics.widthPixels),
            reviewTarget="$token:$generation",onCommand={operation,steps->
                if(editing){if(operation==TranslationGestures.MOVE)moveEditing(steps)else acceptEditing()}
                else if(operation==TranslationGestures.MOVE)moveChoice(steps)else choose()
            })
        if(editing&&!closed&&stage==Stage.EDITING)return editorInput?.motion(event) ?: handled
        return handled
    }
    fun close() {
        if(closed) return
        android.util.Log.d("KeyboardTranslation","close stage=$stage")
        drag?.cancel(); bottomDrag.cancel(); resize.cancel(); bottomContact = false; editorInput = null
        closed = true; generation++; engine?.close(); engine = null; gestures.reset()
        dialog?.dismiss(); dialog = null; onClosed()
    }
}
