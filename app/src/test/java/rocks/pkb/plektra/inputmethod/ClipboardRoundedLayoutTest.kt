package rocks.pkb.plektra.inputmethod

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.clipboard.ClipboardHistoryManager
import rocks.pkb.plektra.inputmethod.ui.ClipboardHistoryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardRoundedLayoutTest {
    @Test
    fun hostCloseControlPreservesCardSpacing() {
        val context = RuntimeEnvironment.getApplication()
        val view = ClipboardHistoryView(context, ClipboardHistoryManager(context))
        fun descendants(group: ViewGroup): List<View> = (0 until group.childCount).flatMap {
            val child = group.getChildAt(it)
            listOf(child) + if (child is ViewGroup) descendants(child) else emptyList()
        }
        val children = descendants(view)
        val recycler = children.filterIsInstance<RecyclerView>().single()
        val close = children.filterIsInstance<ImageView>().single { it.contentDescription == context.getString(R.string.close) }
        val originalPadding = recycler.paddingBottom
        val originalSidePadding = recycler.paddingLeft
        view.useHostCloseControl()
        assertEquals(View.GONE, close.visibility)
        assertEquals(originalSidePadding, recycler.paddingLeft)
        assertEquals(originalSidePadding, recycler.paddingRight)
        assertEquals(originalSidePadding, recycler.paddingTop)
        assertEquals(originalPadding, recycler.paddingBottom)
    }
}
