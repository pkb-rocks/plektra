package rocks.pkb.plektra.inputmethod
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.*
internal object TranslationTheme {
 fun colors(context:Context):ColorScheme {
  val night=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
  return if(Build.VERSION.SDK_INT>=31){if(night)dynamicDarkColorScheme(context)else dynamicLightColorScheme(context)}else if(night)darkColorScheme()else lightColorScheme()
 }
}
