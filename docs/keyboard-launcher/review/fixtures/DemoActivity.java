package org.example.plektra.launcher.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
public final class DemoActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
 LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(28,28,28,28);layout.setBackgroundColor(Color.rgb(245,247,250));layout.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(28,insets.getSystemWindowInsetTop()+28,28,insets.getSystemWindowInsetBottom()+28);return insets;});
 TextView title=new TextView(this);title.setText(getPackageName().endsWith("sampleone")?"Sample One":"Sample Two");title.setTextSize(30);title.setTextColor(Color.rgb(20,35,55));layout.addView(title);
 TextView subtitle=new TextView(this);subtitle.setText("Plektra launcher demonstration\nFictional test app - no network permissions");subtitle.setTextSize(18);subtitle.setPadding(0,24,0,28);layout.addView(subtitle);
 EditText input=new EditText(this);input.setTextSize(24);input.setSingleLine(false);input.setHint("Type sample text here");input.setInputType(0x20001);input.setGravity(Gravity.TOP);layout.addView(input,new LinearLayout.LayoutParams(-1,180));
 setContentView(layout);layout.requestApplyInsets();
 }
}