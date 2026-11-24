package rocks.pkb.plektra.inputmethod

import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Border-only resizing. Text and action controls keep their normal touch handling. */
internal class TranslationWindowResize(private val view: View,
    private val position: () -> Rect, private val bounds: () -> Rect,
    private val minimum: () -> Pair<Int,Int>,
    private val resize: (Rect,Handle,Boolean) -> Unit, private val bottomInset: Int = 0) {
    enum class Handle(val left: Boolean,val top: Boolean,val horizontal: Boolean = true,val vertical: Boolean = true) {
        TOP_LEFT(true,true),TOP_RIGHT(false,true),BOTTOM_LEFT(true,false),BOTTOM_RIGHT(false,false),
        LEFT(true,false,true,false),RIGHT(false,false,true,false),TOP(false,true,false,true),BOTTOM(false,false,false,true)
    }
    private var corner: Handle? = null
    private var initial = Rect()
    private var x = 0f
    private var y = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val edge = 32 * view.resources.displayMetrics.density
    fun cancel() { corner = null; moved = false }
    fun touch(event: MotionEvent): Boolean {
        if(event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false
        if(event.pointerCount != 1 || !event.rawX.isFinite() || !event.rawY.isFinite()) {
            val consumed = corner != null; cancel(); return consumed
        }
        if(event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            val padding = event.x < view.paddingLeft || event.x >= view.width-view.paddingRight ||
                event.y < view.paddingTop || event.y >= view.height-view.paddingBottom-bottomInset
            if(!padding) return false
            corner = hit(event.x,event.y,view.width,view.height,edge,80*view.resources.displayMetrics.density) ?: return false
            initial = Rect(position()); x = event.rawX; y = event.rawY
            return true
        }
        val active = corner ?: return false
        if(event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.rawX-x; val dy = event.rawY-y
            moved = moved || abs(dx) > slop || abs(dy) > slop
            if(moved) {
                val (width,height) = minimum()
                resize(frame(initial,active,dx.toInt(),dy.toInt(),bounds(),width,height),active,active.vertical && abs(dy)>slop)
            }
        }
        if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancel()
        return true
    }
    companion object {
        fun hit(x: Float,y: Float,width: Int,height: Int,edge: Float,bottomGripWidth: Float = 80f): Handle? {
            if(!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > width || y > height) return null
            val left = x in 0f..edge
            val right = x >= width-edge && x <= width
            val top = y in 0f..edge
            val bottom = y >= height-edge && y <= height
            return when {
                left && top -> Handle.TOP_LEFT
                right && top -> Handle.TOP_RIGHT
                left && bottom -> Handle.BOTTOM_LEFT
                right && bottom -> Handle.BOTTOM_RIGHT
                left -> Handle.LEFT
                right -> Handle.RIGHT
                top -> Handle.TOP
                bottom && abs(x-width/2f) > bottomGripWidth/2 -> Handle.BOTTOM
                else -> null
            }
        }
        fun frame(initial: Rect,corner: Handle,dx: Int,dy: Int,bounds: Rect,minWidth: Int,minHeight: Int): Rect {
            val width = minWidth.coerceIn(1,bounds.width().coerceAtLeast(1))
            val height = minHeight.coerceIn(1,bounds.height().coerceAtLeast(1))
            var left = initial.left.coerceIn(bounds.left,bounds.right-width)
            var right = (left+initial.width().coerceIn(width,bounds.width())).coerceAtMost(bounds.right)
            var top = initial.top.coerceIn(bounds.top,bounds.bottom-height)
            var bottom = (top+initial.height().coerceIn(height,bounds.height())).coerceAtMost(bounds.bottom)
            if(corner.horizontal) {
                if(corner.left) {
                    right = initial.right.coerceIn(bounds.left+width,bounds.right)
                    left = (initial.left+dx).coerceIn(bounds.left,right-width)
                } else {
                    left = initial.left.coerceIn(bounds.left,bounds.right-width)
                    right = (initial.right+dx).coerceIn(left+width,bounds.right)
                }
            }
            if(corner.vertical) {
                if(corner.top) {
                    bottom = initial.bottom.coerceIn(bounds.top+height,bounds.bottom)
                    top = (initial.top+dy).coerceIn(bounds.top,bottom-height)
                } else {
                    top = initial.top.coerceIn(bounds.top,bounds.bottom-height)
                    bottom = (initial.bottom+dy).coerceIn(top+height,bounds.bottom)
                }
            }
            return Rect(left,top,right,bottom)
        }
    }
}
