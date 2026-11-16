package local.plektra.launcherdemo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
/** Ordered key-event driver, restricted to fictional apps and isolated review settings.
 * Never reads UI text, launches intents, changes permissions or injects Enter.
 */
public final class DemoKeys extends Instrumentation {
 private String kind;
 @Override public void onCreate(Bundle args){super.onCreate(args);kind=args.getString("kind","short");start();}
 private String pkg(UiAutomation a){AccessibilityNodeInfo r=a.getRootInActiveWindow();if(r==null)return "NONE";try{return String.valueOf(r.getPackageName());}finally{r.recycle();}}
 private boolean allowed(String p){return Arrays.asList("rocks.pkb.plektra.review.launcher","org.example.plektra.launcher.sampleone","org.example.plektra.launcher.sampletwo").contains(p);}
 private boolean inject(UiAutomation a,int action,int code,long down,int meta){return a.injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,meta,-1,0,0,InputDevice.SOURCE_KEYBOARD),true);}
 @Override public void onStart(){Bundle result=new Bundle();UiAutomation a=null;boolean shift=false,letter=false;long shiftDown=0,letterDown=0;int key="display".equals(kind)?KeyEvent.KEYCODE_G:KeyEvent.KEYCODE_A;
 try {
  if(!Arrays.asList("short","long","display").contains(kind))throw new IllegalArgumentException();
  a=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  String before=pkg(a);result.putString("before",before);
  if(!allowed(before)){result.putString("result","STOPPED_NON_TEST_FOREGROUND");finish(0,result);return;}
  shiftDown=SystemClock.uptimeMillis();shift=inject(a,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,shiftDown,KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_LEFT_ON);
  SystemClock.sleep(80);
  if(!allowed(pkg(a)))throw new IllegalStateException();
  letterDown=SystemClock.uptimeMillis();letter=inject(a,KeyEvent.ACTION_DOWN,key,letterDown,KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_LEFT_ON);
  SystemClock.sleep("long".equals(kind)?650:100);
  if(letter){inject(a,KeyEvent.ACTION_UP,key,letterDown,KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_LEFT_ON);letter=false;}
  SystemClock.sleep(80);
  if(shift){inject(a,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,shiftDown,0);shift=false;}
  SystemClock.sleep(400);
  result.putString("after",pkg(a));result.putString("kind",kind);result.putString("result","KEY_SEQUENCE_COMPLETED");finish(0,result);
 }catch(Throwable t){result.putString("errorClass",t.getClass().getName());finish(1,result);}
 finally{if(a!=null){if(letter)inject(a,KeyEvent.ACTION_UP,key,letterDown,0);if(shift)inject(a,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,shiftDown,0);}}
 }
}
