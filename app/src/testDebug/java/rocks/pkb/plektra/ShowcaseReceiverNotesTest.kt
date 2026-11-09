package rocks.pkb.plektra

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.EditText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShowcaseReceiverNotesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun captureNoteIsAddedBesidePersonalNotesAndRemovedOnRestore() {
        val personal = Robolectric.buildActivity(NotesActivity::class.java).setup()
        personal.get().findViewById<EditText>(R.id.notes_body).setText("Personal note")
        personal.pause().stop().destroy()
        val personalNotes = NotesRepository(context.getSharedPreferences("notes", Context.MODE_PRIVATE)).all()

        assertEquals(Activity.RESULT_OK, broadcast(Intent().putExtra("note",
            JSONObject().put("title", "Weekend plans").put("body", "Synthetic capture").toString())))
        val capture = Robolectric.buildActivity(NotesActivity::class.java).setup()
        assertEquals("Synthetic capture", capture.get().findViewById<EditText>(R.id.notes_body).text.toString())
        capture.get().findViewById<EditText>(R.id.notes_body).append(" typed")
        capture.pause().stop().destroy()
        val during = NotesRepository(context.getSharedPreferences("notes", Context.MODE_PRIVATE)).all()
        assertEquals(personalNotes, during.dropLast(1))

        assertEquals(Activity.RESULT_OK, broadcast(Intent().putExtra("restore", true)))
        assertEquals(personalNotes, NotesRepository(context.getSharedPreferences("notes", Context.MODE_PRIVATE)).all())
        val reopened = Robolectric.buildActivity(NotesActivity::class.java).setup()
        assertEquals("Personal note", reopened.get().findViewById<EditText>(R.id.notes_body).text.toString())
        reopened.pause().stop().destroy()
    }

    private fun broadcast(intent: Intent): Int {
        var result: Int? = null
        context.sendOrderedBroadcast(intent.setClass(context, ShowcaseReceiver::class.java), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { result = resultCode }
            }, null, Activity.RESULT_OK, null, null)
        shadowOf(Looper.getMainLooper()).idle()
        return checkNotNull(result) { "ShowcaseReceiver did not answer" }
    }
}
