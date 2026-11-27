package rocks.pkb.plektra

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** The notes list: a compact dialog with many notes, long titles and large fonts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w478dp-h533dp-360dpi")
class NotesListDialogTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<NotesActivity>? = null

    @Before
    fun resetState() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        context.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        setFontScale(1f)
    }

    @Test
    fun manyNotesWithLongTitlesStayCompactAndUsable() = verifyList(fontScale = 1f)

    @Test
    fun manyNotesWithLongTitlesStayUsableAtLargerFont() = verifyList(fontScale = 1.3f)

    @Test
    fun manyNotesWithLongTitlesStayUsableAtDoubleFont() = verifyList(fontScale = 2f)

    private fun verifyList(fontScale: Float) {
        setFontScale(fontScale)
        val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        val repository = NotesRepository(prefs)
        val notes = (1..12).map { index ->
            val title = if (index % 3 == 0) "Note $index with a very long title that cannot fit into a single line of the list at all"
            else "Note $index"
            repository.create().copy(title = title, body = "Body $index").also(repository::save)
        }
        repository.select(notes[4].id)
        val created = Robolectric.buildActivity(NotesActivity::class.java).setup()
        controller = created
        val activity = created.get()
        val more = ArrayList<View>()
        activity.findViewById<View>(android.R.id.content)
            .findViewsWithText(more, activity.getString(R.string.notes_title), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        more.single().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val density = activity.resources.displayMetrics.density
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec((420 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((500 * density).toInt(), View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)

        val list = dialog.listView
        assertEquals(notes.size + 1, list.adapter.count)
        val rows = (0 until list.childCount).map { list.getChildAt(it) }
        assertTrue("The list must show several notes at once, showed ${rows.size}", rows.size >= 3)
        val minTarget = (48 * density).toInt()
        rows.forEach { row ->
            val text = row as? TextView ?: row.findViewById(android.R.id.text1)
            assertTrue("Each note needs a 48 dp touch target: ${row.height}", row.height >= minTarget)
            val layout = text.layout
            assertTrue("Titles wrap to at most two lines: ${text.text}", layout.lineCount <= 2)
            if (text.text.length > 40 && fontScale >= 1f) {
                val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                assertTrue("A long title ends with an ellipsis: ${text.text}", ellipsized || layout.lineCount == 1 && layout.getLineWidth(0) <= text.width)
            }
        }
        // Compact header: the first note starts close below the dialog's top edge.
        var firstRowTop = 0
        var view: View = rows.first()
        while (view !== decor) { firstRowTop += view.top; view = view.parent as View }
        firstRowTop -= decor.paddingTop
        assertTrue("The list starts after a compact title: $firstRowTop px",
            firstRowTop <= (64 * density * maxOf(1f, fontScale)).toInt())

        val activePosition = (0 until list.adapter.count).first { (list.adapter.getItem(it) as? NotesRepository.Note)?.id == notes[4].id }
        val activeRow = list.adapter.getView(activePosition, null, list)
        assertTrue("The open note is marked in the list", activeRow.isActivated)

        val targetPosition = (0 until list.adapter.count).first { (list.adapter.getItem(it) as? NotesRepository.Note)?.id == notes[1].id }
        list.performItemClick(list.adapter.getView(targetPosition, null, list), targetPosition, list.adapter.getItemId(targetPosition))
        assertEquals("Note 2", activity.findViewById<EditText>(R.id.notes_heading).text.toString())
    }

    private fun setFontScale(scale: Float) {
        val resources = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
