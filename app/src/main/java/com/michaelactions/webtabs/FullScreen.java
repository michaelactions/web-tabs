package com.michaelactions.webtabs;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 *全屏（沉浸式）：状态栏 +导航栏全隐，滑边缘可临时唤出。
 *Android11(API30)起走 WindowInsetsController，更早的版本走 setSystemUiVisibility，
 *同时保留 FLAG_FULLSCREEN兜底（部分国产 ROM只认这个）。
 */
public class FullScreen {

/**屏幕常亮 */
public static void applyKeepScreenOn(Activity a) {
boolean keep = Store.prefs(a).getBoolean("keepOn", true);
View content = a.findViewById(android.R.id.content);
if (content instanceof android.view.ViewGroup) {
    android.view.ViewGroup group = (android.view.ViewGroup) content;
    for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setKeepScreenOn(keep);
}
if (keep) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
}

/**全屏开关（默认开） */
public static boolean isOn(Activity a) {
return Store.prefs(a).getBoolean("fullscreen", true);
}

public static void apply(Activity a) {
Window w = a.getWindow();
boolean fs = isOn(a);
if (fs) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

if (Build.VERSION.SDK_INT >=30) {
w.setDecorFitsSystemWindows(!fs);
WindowInsetsController c = w.getInsetsController();
if (c != null) {
if (fs) {
c.hide(WindowInsets.Type.systemBars());
c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
} else {
c.show(WindowInsets.Type.systemBars());
}
}
} else {
View d = w.getDecorView();
if (fs) {
d.setSystemUiVisibility(
View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
| View.SYSTEM_UI_FLAG_FULLSCREEN
| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
} else {
d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
}
}
}
}
