package rocks.pkb.plektra.inputmethod

import android.view.RoundedCorner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManagerGlobal

/**
 * Gives a Robolectric display real rounded corners, so `Display.getRoundedCorner` answers as on
 * a device. Robolectric 4.11 has no public setter; this edits the display's `DisplayInfo` through
 * the shadow display manager, which notifies display listeners like a configuration change.
 */
internal object TestDisplayCorners {
    fun setBottomRadius(displayId: Int, radius: Int) {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val infoClass = Class.forName("android.view.DisplayInfo")
        val current = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, displayId)
        val info = infoClass.getConstructor(infoClass).newInstance(current)
        val width = infoClass.getField("logicalWidth").getInt(info)
        val height = infoClass.getField("logicalHeight").getInt(info)
        val cornersClass = Class.forName("android.view.RoundedCorners")
        val corners = if (radius <= 0) null else cornersClass.getConstructor(
            RoundedCorner::class.java, RoundedCorner::class.java, RoundedCorner::class.java, RoundedCorner::class.java
        ).newInstance(
            RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, radius, radius, radius),
            RoundedCorner(RoundedCorner.POSITION_TOP_RIGHT, radius, width - radius, radius),
            RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, radius, width - radius, height - radius),
            RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT, radius, radius, height - radius)
        )
        infoClass.getField("roundedCorners").set(info, corners)
        val shadow = Shadow.extract<ShadowDisplayManagerGlobal>(global)
        ShadowDisplayManagerGlobal::class.java.getDeclaredMethod("changeDisplay", Int::class.javaPrimitiveType, infoClass)
            .apply { isAccessible = true }.invoke(shadow, displayId, info)
    }
}
