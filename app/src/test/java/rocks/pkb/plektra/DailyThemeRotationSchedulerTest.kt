package rocks.pkb.plektra
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DailyThemeRotationSchedulerTest {
 @Test fun clockBroadcastsRefreshOnlyAnActiveInputSession() {
  val context=RuntimeEnvironment.getApplication()
  SettingsManager.getPreferences(context).edit().clear().commit()
  var refreshes=0
  val scheduler=DailyThemeRotationScheduler(context){refreshes++}
  try {
   context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED));shadowOf(Looper.getMainLooper()).idle()
   assertEquals(0,refreshes)
   scheduler.start();assertEquals(1,refreshes)
   context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED));shadowOf(Looper.getMainLooper()).idle()
   assertEquals(2,refreshes)
   scheduler.stop()
   context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED));shadowOf(Looper.getMainLooper()).idle()
   assertEquals(2,refreshes)
  } finally {scheduler.close()}
 }
 @Test fun closingUnregistersTheClockReceiver() {
  val context=RuntimeEnvironment.getApplication()
  var refreshes=0
  val scheduler=DailyThemeRotationScheduler(context){refreshes++}
  scheduler.start();scheduler.close()
  context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED));shadowOf(Looper.getMainLooper()).idle()
  assertEquals(1,refreshes)
 }
}
