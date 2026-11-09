package rocks.pkb.plektra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class ThemeTextureRenderingTest {
    private lateinit var context:Context
    private lateinit var texture:KeyboardThemeTexture
    private lateinit var pngBytes:ByteArray
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();ThemeTextureStore.invalidate()
        val bytes=javaClass.classLoader!!.getResourceAsStream("theme-backdrop.png")!!.use{it.readBytes()}
        pngBytes=bytes
        texture=KeyboardThemeTexture("texture-${ThemeTextureStore.hash(bytes)}.png",wholeKeyboard=true)
        ThemeTextureStore.file(context,texture.asset).apply{parentFile!!.mkdirs();writeBytes(bytes)}
    }
    private fun load(view:View) {
        assertEquals(4, ThemeTextureStore.decodeAsset(context, texture.asset).width)
        ThemeTextureStore.request(view,texture.asset)
        val deadline=System.nanoTime()+5_000_000_000L
        while(ThemeTextureStore.peek(context,texture.asset)==null && System.nanoTime()<deadline) {
            Thread.sleep(10);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertNotNull("Bounded asset must load asynchronously",ThemeTextureStore.peek(context,texture.asset))
    }
    private class RecordingCanvas:Canvas() {
        val rectangles=mutableListOf<Rect>()
        val alphas=mutableListOf<Int>()
        override fun drawRect(rect:Rect,paint:Paint) {rectangles+=Rect(rect);alphas+=paint.alpha}
    }
    @Test fun imageCoversChangedBoundsInsteadOfKeepingOldRectangle() {
        val owner=View(context);ThemeTextureBackground.set(owner,0xff112233.toInt(),texture);load(owner)
        val drawable=owner.background;val canvas=RecordingCanvas()
        drawable.setBounds(0,0,300,120);drawable.draw(canvas)
        drawable.setBounds(0,0,500,80);drawable.draw(canvas)
        assertEquals(Rect(0,0,300,120),canvas.rectangles[1])
        assertEquals(Rect(0,0,500,80),canvas.rectangles[3])
    }
    @Test fun nestedSurfaceDoesNotPaintASecondPhotoCrop() {
        val root=FrameLayout(context);val child=View(context);root.addView(child)
        ThemeTextureBackground.set(root,0,texture);ThemeTextureBackground.set(child,0,texture)
        load(root);val canvas=RecordingCanvas();child.background.setBounds(0,0,120,40);child.background.draw(canvas)
        assertTrue(canvas.rectangles.isEmpty())
    }
    @Test fun overlayCoversContentBelowWithTheSharedCrop() {
        val root=FrameLayout(context);val menu=View(context);root.addView(menu)
        ThemeTextureBackground.set(root,0xff112233.toInt(),texture);ThemeTextureBackground.set(menu,0xff112233.toInt(),texture,overlay=true)
        load(root)
        root.layout(0,0,400,100);menu.layout(0,40,400,80)
        root.background.setBounds(0,0,400,100);menu.background.setBounds(0,0,400,40)
        val matrices=mutableListOf<android.graphics.Matrix>()
        val canvas=object:Canvas() {
            val rectangles=mutableListOf<Rect>()
            override fun drawRect(rect:Rect,paint:Paint) {
                rectangles+=Rect(rect)
                (paint.shader as? android.graphics.BitmapShader)?.let { matrices+=android.graphics.Matrix().also(it::getLocalMatrix) }
            }
        }
        root.background.draw(canvas);menu.background.draw(canvas)
        assertEquals("Root and overlay both paint colour and photo",4,canvas.rectangles.size)
        assertEquals(Rect(0,0,400,40),canvas.rectangles[3])
        val rootValues=FloatArray(9).also(matrices[0]::getValues);val menuValues=FloatArray(9).also(matrices[1]::getValues)
        assertEquals(rootValues[android.graphics.Matrix.MSCALE_X],menuValues[android.graphics.Matrix.MSCALE_X],0.001f)
        assertEquals("The overlay shows the part of the root crop behind it",rootValues[android.graphics.Matrix.MTRANS_Y]-40f,
            menuValues[android.graphics.Matrix.MTRANS_Y],0.001f)
    }
    @Test fun transparentFallbackAndIntensityKeepTheirAlpha() {
        val owner=View(context);ThemeTextureBackground.set(owner,0x40112233,texture.copy(intensity=.5f));load(owner)
        owner.background.setBounds(0,0,120,40);val canvas=RecordingCanvas();owner.background.draw(canvas)
        assertEquals(listOf(64,127),canvas.alphas)
    }
    @Test fun softwareViewReturnsToItsOriginalBackgroundWhenImageRemoved() {
        val view=AospKeyboardView(context);val original=view.background
        val plain=keyboardThemePresets().first().toAospThemeOverride()
        view.themeOverride=plain.copy(textures=mapOf("background" to texture))
        assertTrue(view.background is ThemeTextureDrawable)
        view.themeOverride=plain
        assertSame(original,view.background)
    }
    @Test fun removingImageRestoresSolidDrawableAndInvalidationDropsCache() {
        val owner=View(context);ThemeTextureBackground.set(owner,0xff123456.toInt(),texture);load(owner)
        ThemeTextureBackground.set(owner,0xff123456.toInt(),null)
        assertEquals(0xff123456.toInt(),(owner.background as android.graphics.drawable.ColorDrawable).color)
        ThemeTextureStore.invalidate();assertNull(ThemeTextureStore.peek(context,texture.asset))
    }
    @Test fun softwareKeyDrawingDoesNotCoverThePhotoWithTheOldSolidBackground() {
        val view=AospKeyboardView(context)
        val plain=keyboardThemePresets().first().toAospThemeOverride()
        val fills=mutableListOf<Int>()
        val canvas=object:Canvas() { override fun drawColor(color:Int) { fills+=color } }
        fun drawKeys() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(view,"onDraw",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Canvas::class.java,canvas))
        view.themeOverride=plain
        drawKeys()
        assertEquals(listOf(plain.background),fills)
        fills.clear()
        view.themeOverride=plain.copy(normalKey=0,specialKey=0,textures=mapOf("background" to texture))
        drawKeys()
        assertTrue("Key drawing must leave the already-painted photo visible",fills.isEmpty())
        view.themeOverride=plain
        drawKeys()
        assertEquals(listOf(plain.background),fills)
    }
    private fun awaitUnavailable(view:View,asset:String) {
        ThemeTextureStore.request(view,asset)
        val deadline=System.nanoTime()+5_000_000_000L
        while(!ThemeTextureStore.isUnavailable(asset) && System.nanoTime()<deadline) Thread.sleep(10)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("Unusable image must be recorded as failed",ThemeTextureStore.isUnavailable(asset))
    }
    private fun photoTheme(asset:String)=keyboardThemePresets().first().withSoftwareKeyboardDefaults()
        .copy(normalKey=0xff102030.toInt(),specialKey=0xff203040.toInt(),suggestion=0xff304050.toInt(),statusBarButton=0xff405060.toInt())
        .withBackgroundPhoto(texture.copy(asset=asset))
    /** Draws the software keyboard as the preview and IME do, and records solid fills over the backdrop. */
    private fun renderedKeyboard(theme:KeyboardThemePreset):Pair<AospKeyboardView,List<Int>> {
        val view=AospKeyboardView(context);view.themeOverride=theme.toAospThemeOverride()
        val fills=mutableListOf<Int>()
        val canvas=object:Canvas() { override fun drawColor(color:Int) { fills+=color } }
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(view,"onDraw",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Canvas::class.java,canvas))
        return view to fills
    }
    private fun assertSavedColoursRendered(theme:KeyboardThemePreset) {
        val colors=theme.toKeyboardThemeColors()
        assertEquals(listOf(theme.normalKey,theme.specialKey,theme.suggestion,theme.statusBarButton),
            listOf(colors.normalKey,colors.specialKey,colors.suggestion,colors.statusBarButton))
        assertFalse(colors.textures.containsKey("background"))
        val override=theme.toAospThemeOverride()
        assertEquals(theme.normalKey to theme.specialKey,override.normalKey to override.specialKey)
        val (view,fills)=renderedKeyboard(theme)
        assertFalse("A failed photo must not stay installed as the keyboard background",view.background is ThemeTextureDrawable)
        assertEquals(listOf(theme.background),fills)
        val root=View(context);ThemeTextureBackground.set(root,theme.background,colors.textures["background"])
        assertEquals(theme.background,(root.background as android.graphics.drawable.ColorDrawable).color)
        assertTrue("The saved photo reference itself is kept",theme.hasBackgroundPhoto())
    }
    @Test fun missingRestoredPhotoFallsBackToEverySavedSurfaceColour() {
        val missing="texture-${"0".repeat(64)}.png"
        val theme=photoTheme(missing)
        var notified=0
        val listener={ notified++; Unit }
        ThemeTextureStore.addAvailabilityListener(listener)
        try {
            // Until the load has finished, surfaces stay transparent for the photo.
            assertEquals(android.graphics.Color.TRANSPARENT,theme.toKeyboardThemeColors().normalKey)
            val (pending,_)=renderedKeyboard(theme)
            assertTrue(pending.background is ThemeTextureDrawable)
            awaitUnavailable(pending,missing)
            assertTrue("Renderers must be told to re-apply their theme",notified>0)
            // Re-applying the theme to the already shown view restores its solid background.
            pending.themeOverride=theme.toAospThemeOverride()
            assertFalse(pending.background is ThemeTextureDrawable)
            // A view created later (another page or editor) starts with the colours directly.
            assertSavedColoursRendered(theme)
            val settings=theme.toSettingsTheme().toKeyboardThemeColors()
            assertEquals(theme.normalKey to theme.statusBarButton,settings.normalKey to settings.statusBarButton)
            // Reloading forgets the failure, tries again and reaches the same fallback.
            notified=0;ThemeTextureStore.invalidate()
            assertEquals(android.graphics.Color.TRANSPARENT,theme.toKeyboardThemeColors().normalKey)
            awaitUnavailable(View(context),missing)
            assertTrue(notified>0)
            assertSavedColoursRendered(theme)
        } finally { ThemeTextureStore.removeAvailabilityListener(listener) }
    }
    @Test fun narrowEmojiPickerAndPhoneKeypadShowThePhotoAndRedrawColoursAfterFallback() {
        val missing="texture-${"1".repeat(64)}.png"
        val theme=photoTheme(missing)
        val picker=rocks.pkb.plektra.inputmethod.ui.EmojiPickerView(context)
        picker.configureSoftwareKeyboardMode(700,{})
        fun layoutNarrow() {
            picker.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(350,View.MeasureSpec.AT_MOST))
            picker.layout(0,0,picker.measuredWidth,picker.measuredHeight)
        }
        fun inner(name:String)=org.robolectric.util.ReflectionHelpers.getField<View>(picker,name)
        fun innerColour(name:String)=(inner(name).background as android.graphics.drawable.ColorDrawable).color
        val keypad=AospKeyboardView(context).apply { keypad=rocks.pkb.plektra.inputmethod.SoftwareKeypad.PHONE }
        val fills=mutableListOf<Int>()
        val canvas=object:Canvas() { override fun drawColor(color:Int) { fills+=color } }
        fun drawKeypad() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(keypad,"onDraw",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Canvas::class.java,canvas))

        picker.themeOverride=theme.toKeyboardThemeColors();layoutNarrow()
        keypad.themeOverride=theme.toAospThemeOverride();drawKeypad()
        assertTrue(picker.background is ThemeTextureDrawable)
        for (name in listOf("vertical","recyclerView")) assertEquals(name,android.graphics.Color.TRANSPARENT,innerColour(name))
        assertTrue("The keypad must not cover the photo",fills.isEmpty())

        awaitUnavailable(View(context),missing)
        picker.themeOverride=theme.toKeyboardThemeColors();layoutNarrow()
        keypad.themeOverride=theme.toAospThemeOverride();drawKeypad()
        assertEquals(theme.background,(picker.background as android.graphics.drawable.ColorDrawable).color)
        for (name in listOf("vertical","recyclerView")) assertEquals(name,theme.background,innerColour(name))
        assertFalse(keypad.background is ThemeTextureDrawable)
        assertEquals(listOf(theme.background),fills)
    }
    @Test fun photoSearchPanelCoversEmojiResultsWithTheSavedBackgroundColour() {
        val theme = photoTheme(texture.asset)
        val picker = rocks.pkb.plektra.inputmethod.ui.EmojiPickerView(context)
        picker.themeOverride = theme.toKeyboardThemeColors()
        picker.showSearch()
        val panel = org.robolectric.util.ReflectionHelpers.getField<View>(picker, "searchPanel")
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(theme.background or 0xff000000.toInt(),
            (panel.background as android.graphics.drawable.ColorDrawable).color)
        val results = org.robolectric.util.ReflectionHelpers.getField<View>(picker, "recyclerView")
        assertEquals(android.graphics.Color.TRANSPARENT,
            (results.background as android.graphics.drawable.ColorDrawable).color)
        assertTrue("The picker still draws the photo", picker.background is ThemeTextureDrawable)
    }

    @Test fun damagedPhotoFallsBackToSavedSurfaceColours() {
        ThemeTextureStore.file(context,texture.asset).writeBytes(byteArrayOf(1,2,3))
        awaitUnavailable(View(context),texture.asset)
        assertSavedColoursRendered(photoTheme(texture.asset))
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun reimportingTheSamePhotoAfterAFailureMakesItVisibleAgain() {
        val photo=File(context.cacheDir,"picked.png").apply { writeBytes(pngBytes) }
        val first=kotlinx.coroutines.runBlocking { ThemeTextureStore.import(context,android.net.Uri.fromFile(photo)) }
        ThemeTextureStore.file(context,first.asset).delete()
        awaitUnavailable(View(context),first.asset)
        val theme=photoTheme(first.asset)
        assertFalse(theme.toKeyboardThemeColors().textures.containsKey("background"))
        var notified=0
        val listener={ notified++; Unit }
        ThemeTextureStore.addAvailabilityListener(listener)
        try {
            val second=kotlinx.coroutines.runBlocking { ThemeTextureStore.import(context,android.net.Uri.fromFile(photo)) }
            assertEquals("Same photo, same content hash",first.asset,second.asset)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("Renderers must re-apply the theme even though the reference is unchanged",notified>0)
        } finally { ThemeTextureStore.removeAvailabilityListener(listener) }
        val colors=theme.toKeyboardThemeColors()
        assertEquals(android.graphics.Color.TRANSPARENT,colors.normalKey)
        val owner=View(context);ThemeTextureBackground.set(owner,theme.background,colors.textures["background"])
        assertTrue(owner.background is ThemeTextureDrawable)
        owner.background.setBounds(0,0,120,40);owner.background.draw(Canvas())
        val deadline=System.nanoTime()+5_000_000_000L
        while(ThemeTextureStore.peek(context,first.asset)==null && System.nanoTime()<deadline) {
            Thread.sleep(10);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertNotNull("The re-imported photo must load and show",ThemeTextureStore.peek(context,first.asset))
    }
    @Test fun readerRejectsOversizedInputBeforeDecode() {
        assertThrows(IllegalArgumentException::class.java) {ThemeTextureStore.readBounded(byteArrayOf(1,2,3).inputStream(),2)}
        assertArrayEquals(byteArrayOf(1,2,3),ThemeTextureStore.readBounded(byteArrayOf(1,2,3).inputStream(),3))
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun openHardwareSymGridReappliesSavedKeyColoursAfterPhotoFails() {
        val a=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val sm=rocks.pkb.plektra.SettingsManager
        sm.getPreferences(a).edit().clear().commit()
        sm.setSoftwareKeyboardMode(a, rocks.pkb.plektra.SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        val asset="texture-${"9".repeat(64)}.png"
        val theme=sm.getKeyboardTheme(a,rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget.HARDWARE).copy(
            normalKey=0xff126834.toInt(), background=0xff192030.toInt(),
            textures=mapOf("background" to rocks.pkb.plektra.KeyboardThemeTexture(asset,wholeKeyboard=true)))
        sm.setKeyboardTheme(a,rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget.HARDWARE,theme)
        val bar=rocks.pkb.plektra.inputmethod.StatusBarController(a,rocks.pkb.plektra.inputmethod.StatusBarController.Mode.CANDIDATES_ONLY)
        val snapshot=rocks.pkb.plektra.inputmethod.StatusBarController.StatusSnapshot(false,false,false,false,false,false,false,false,false,false,2)
        fun render() {
            bar.update(snapshot,symMappings=mapOf(android.view.KeyEvent.KEYCODE_Q to ""))
            val root=bar.getLayout()!!
            root.measure(View.MeasureSpec.makeMeasureSpec(1078,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            root.layout(0,0,root.measuredWidth,root.measuredHeight)
        }
        fun pixel():Int {
            @Suppress("UNCHECKED_CAST")
            val key=(bar.javaClass.getDeclaredField("emojiKeyButtons").apply { isAccessible = true }.get(bar) as List<View>).first()
            val bmp=android.graphics.Bitmap.createBitmap(key.width,key.height,android.graphics.Bitmap.Config.ARGB_8888)
            key.draw(android.graphics.Canvas(bmp))
            return bmp.getPixel(key.width/2,key.height/2)
        }
        render()
        assertEquals(android.graphics.Color.TRANSPARENT,pixel())
        rocks.pkb.plektra.ThemeTextureStore.request(View(a),asset)
        val deadline=System.nanoTime()+5_000_000_000L
        while(!rocks.pkb.plektra.ThemeTextureStore.isUnavailable(asset)) { check(System.nanoTime()<deadline);Thread.sleep(10) }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        render()
        val afterRefresh=pixel()
        bar.update(snapshot.copy(symPage=0))
        render()
        assertEquals("Rebuilding the same hardware SYM page restores the correct saved colour",theme.normalKey,pixel())
        assertEquals("Open hardware SYM keys must regain the saved normal-key colour",theme.normalKey,afterRefresh)
        a.finish()
    }

}
