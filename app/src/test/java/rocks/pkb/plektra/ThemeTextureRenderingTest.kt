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
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();ThemeTextureStore.invalidate()
        val bytes=javaClass.classLoader!!.getResourceAsStream("theme-backdrop.png")!!.use{it.readBytes()}
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
    @Test fun readerRejectsOversizedInputBeforeDecode() {
        assertThrows(IllegalArgumentException::class.java) {ThemeTextureStore.readBounded(byteArrayOf(1,2,3).inputStream(),2)}
        assertArrayEquals(byteArrayOf(1,2,3),ThemeTextureStore.readBounded(byteArrayOf(1,2,3).inputStream(),3))
    }
}
