package rocks.pkb.plektra.ui

import android.content.SharedPreferences
import android.os.Build
import android.view.RoundedCorner
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.R
import rocks.pkb.plektra.MainBackgroundPreference
import rocks.pkb.plektra.MainMotionBackground
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.TextMotionEvent
import rocks.pkb.plektra.T2eCornerCalibration
import rocks.pkb.plektra.T2eCornerGeometry
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import kotlin.math.abs

private val PlektraBrown = Color(0xFF332515)

@Composable
internal fun CustomTopBar(
    onSettingsClick: () -> Unit,
    background: MainBackgroundPreference,
    textEvent: TextMotionEvent?,
    modifier: Modifier = Modifier
) {
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    var temporaryBackground by remember(background) { mutableStateOf<MainBackgroundPreference?>(null) }
    val displayedBackground = temporaryBackground ?: background
    val swipeThreshold = with(density) { 48.dp.toPx() }
    var calibration by remember { mutableStateOf(T2eCornerCalibration.read(context)) }
    DisposableEffect(context) {
        val prefs = SettingsManager.getPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == T2eCornerCalibration.KEY) calibration = T2eCornerCalibration.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val headerShape: Shape = if (DeviceSpecific.isTitan2EliteDevice()) {
        val fallback = with(density) { 50.dp.toPx() }
        fun radius(position: Int) = if (Build.VERSION.SDK_INT >= 31) {
            view.rootWindowInsets?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        } else fallback
        val left = radius(RoundedCorner.POSITION_BOTTOM_LEFT)
        val right = radius(RoundedCorner.POSITION_BOTTOM_RIGHT)
        remember(calibration, left, right) {
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    density: androidx.compose.ui.unit.Density
                ): Outline {
                    val adjusted = calibration.copy(offsetPx = 0f, shiftXPx = 0f, shiftYPx = 0f)
                    val path = T2eCornerGeometry.path(size.width, size.height, left, right, adjusted)
                    val bounds = AndroidPath().apply {
                        addRect(0f, 0f, size.width, size.height, AndroidPath.Direction.CW)
                    }
                    path.op(bounds, AndroidPath.Op.INTERSECT)
                    return Outline.Generic(path.asComposePath())
                }
            }
        }
    } else {
        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    }
    Surface(
        modifier = modifier.fillMaxWidth().shadow(4.dp, headerShape),
        shape = headerShape,
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().pointerInput(displayedBackground) {
                var distance = 0f
                var switched = false
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f; switched = false },
                    onHorizontalDrag = { _, amount ->
                        distance += amount
                        if (!switched && abs(distance) >= swipeThreshold) {
                            temporaryBackground = when (displayedBackground) {
                                MainBackgroundPreference.Keyboard -> MainBackgroundPreference.Strings
                                else -> MainBackgroundPreference.Keyboard
                            }
                            switched = true
                        }
                    }
                )
            }
        ) {
            MainMotionBackground(
                preference = displayedBackground,
                textEvent = textEvent,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(PlektraBrown.copy(alpha = if (displayedBackground == MainBackgroundPreference.Keyboard) 0.52f else 0.58f))
                    .padding(horizontal = 16.dp, vertical = 32.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center).offset(y = statusBarInset / 2),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Plektra",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (displayedBackground == MainBackgroundPreference.Keyboard) {
                            "Feel the travel."
                        } else {
                            "Strike keys. Strike chords."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.84f),
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = statusBarInset / 2)
                        .size(58.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings_content_description),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
