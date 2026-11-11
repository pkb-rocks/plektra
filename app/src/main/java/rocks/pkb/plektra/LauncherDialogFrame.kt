package rocks.pkb.plektra
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal object LauncherDialogSizing {
 fun visibleHeight(windowHeight:Int,systemTop:Int,systemBottom:Int,imeBottom:Int):Int =
  (windowHeight-systemTop-maxOf(systemBottom,imeBottom)).coerceAtLeast(1)
}
private fun Context.launcherHostActivity():Activity? {
 var current:Context=this
 while(current is ContextWrapper) {
  if(current is Activity)return current
  val next=current.baseContext;if(next===current)return null;current=next
 }
 return current as? Activity
}
/** Measure the stable activity viewport, never the floating dialog whose size we change. */
@Composable internal fun LauncherDialogFrame(onDismiss:()->Unit,content:@Composable BoxWithConstraintsScope.()->Unit) {
 val context=LocalContext.current
 val density=LocalDensity.current
 val configuration=LocalConfiguration.current
 val activity=remember(context){context.launcherHostActivity()}
 val windowHeight=if(Build.VERSION.SDK_INT>=30 && activity!=null) activity.windowManager.currentWindowMetrics.bounds.height()
  else with(density){configuration.screenHeightDp.dp.roundToPx()}
 // These insets belong to the activity. Reading a floating dialog's visible frame creates
 // a resize feedback loop as its own bottom edge crosses the IME.
 val top=WindowInsets.systemBars.getTop(density)
 val bottom=WindowInsets.systemBars.getBottom(density)
 val ime=WindowInsets.ime.getBottom(density)
 val pixels=LauncherDialogSizing.visibleHeight(windowHeight,top,bottom,ime)
 val visibleHeight=with(density){pixels.toDp()}
 Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)) {
  BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=visibleHeight).padding(horizontal=24.dp,vertical=16.dp),contentAlignment=Alignment.Center,content=content)
 }
}
