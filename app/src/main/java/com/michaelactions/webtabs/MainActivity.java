package com.michaelactions.webtabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签浏览器：多标签网页 + 屏幕常亮 + 标签持久化保存。
 * 纯 framework 实现（不依赖 AndroidX），单 Activity。
 */
public class MainActivity extends Activity {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static class Tab {
        String name;
        String url;
        WebView web;
        Button btn;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int current = 0;

    private LinearLayout tabBar;
    private FrameLayout webContainer;
    private HorizontalScrollView tabBarScroll;
    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshTask;

    // ---------- 生命周期 ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 常亮：窗口级 + 布局级双向保证
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tabBar = findViewById(R.id.tabBar);
        webContainer = findViewById(R.id.webContainer);
        tabBarScroll = findViewById(R.id.tabBarScroll);
        prefs = getSharedPreferences("webtabs", Context.MODE_PRIVATE);

        loadState();
        if (tabs.isEmpty()) {
            addTab("工具站", "https://tools.office3.pp.ua/", false);
            addTab("华住会看板", "https://huazhu.office3.pp.ua/", false);
        }
        rebuildTabBar();
        selectTab(Math.max(0, Math.min(current, tabs.size() - 1)), true);
        applySettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyRefreshTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRefreshTimer();
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            if (t.web != null) {
                webContainer.removeView(t.web);
                t.web.destroy();
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Tab t = tabs.get(current);
        if (t.web != null && t.web.canGoBack()) {
            t.web.goBack();
        } else {
            // 不退到桌面，避免误触退出（看板场景）
            moveTaskToBack(true);
        }
    }

    // ---------- 标签管理 ----------

    private void addTab(String name, String url, boolean switchTo) {
        Tab t = new Tab();
        t.name = name;
        t.url = normalize(url);
        WebView w = createWebView();
        t.web = w;
        w.loadUrl(t.url);
        webContainer.addView(w, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.setVisibility(View.GONE);
        tabs.add(t);
        if (switchTo) {
            rebuildTabBar();
            selectTab(tabs.size() - 1, true);
        }
        saveState();
    }

    private WebView createWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        s.setUserAgentString(prefs.getBoolean("desktopUA", false) ? DESKTOP_UA : s.getUserAgentString());

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient());
        w.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("无法打开下载链接");
                }
            }
        });
        return w;
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            Button b = new Button(this);
            b.setText(t.name == null || t.name.isEmpty() ? ("标签" + (i + 1)) : t.name);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setPadding(dp(14), dp(4), dp(14), dp(4));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(Color.parseColor("#FF344054"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx, true);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showTabMenu(idx);
                    return true;
                }
            });
            t.btn = b;
            tabBar.addView(b);
        }
        // 新增标签按钮
        Button add = new Button(this);
        add.setText("＋");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        add.setAllCaps(false);
        add.setBackgroundColor(Color.TRANSPARENT);
        add.setTextColor(Color.parseColor("#FF1570EF"));
        add.setMinWidth(0);
        add.setMinimumWidth(0);
        add.setMinHeight(0);
        add.setMinimumHeight(0);
        add.setPadding(dp(12), dp(4), dp(12), dp(4));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(-1);
            }
        });
        add.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showSettings();
                return true;
            }
        });
        tabBar.addView(add);
        highlightCurrent();
    }

    private void highlightCurrent() {
        for (int i = 0; i < tabs.size(); i++) {
            Button b = tabs.get(i).btn;
            if (b == null) continue;
            if (i == current) {
                b.setTextColor(Color.parseColor("#FF1570EF"));
                b.setBackgroundColor(Color.parseColor("#FFE8F0FE"));
            } else {
                b.setTextColor(Color.parseColor("#FF344054"));
                b.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private void selectTab(int idx, boolean scrollIntoView) {
        if (idx < 0 || idx >= tabs.size()) return;
        for (int i = 0; i < tabs.size(); i++) {
            WebView w = tabs.get(i).web;
            if (w != null) w.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        current = idx;
        highlightCurrent();
        if (scrollIntoView && tabs.get(idx).btn != null) {
            final View b = tabs.get(idx).btn;
            tabBarScroll.post(new Runnable() {
                @Override
                public void run() {
                    tabBarScroll.smoothScrollTo(Math.max(0, b.getLeft() - dp(60)), 0);
                }
            });
        }
        saveState();
        applyRefreshTimer();
    }

    private void showTabMenu(final int idx) {
        final Tab t = tabs.get(idx);
        String[] items = {"编辑此标签", "删除此标签", "刷新", "复制网址"};
        new AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            showEditDialog(idx);
                        } else if (which == 1) {
                            deleteTab(idx);
                        } else if (which == 2) {
                            if (t.web != null) t.web.reload();
                        } else {
                            toast(t.url);
                        }
                    }
                })
                .show();
    }

    private void deleteTab(int idx) {
        if (tabs.size() <= 1) {
            toast("至少保留一个标签");
            return;
        }
        Tab t = tabs.remove(idx);
        if (t.web != null) {
            webContainer.removeView(t.web);
            t.web.destroy();
        }
        if (current >= tabs.size()) current = tabs.size() - 1;
        rebuildTabBar();
        selectTab(current, false);
        saveState();
    }

    /** idx = -1 表示新增 */
    private void showEditDialog(final int idx) {
        final boolean isNew = idx < 0;
        Tab t = isNew ? null : tabs.get(idx);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("标签名称（例如：账单系统）");
        nameEt.setSingleLine(true);
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT);
        if (!isNew) nameEt.setText(t.name);
        box.addView(nameEt);

        final EditText urlEt = new EditText(this);
        urlEt.setHint("网址（例如：https://example.com 或 10.0.1.10:8080）");
        urlEt.setSingleLine(true);
        urlEt.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        if (!isNew) urlEt.setText(t.url);
        box.addView(urlEt);

        TextView tip = new TextView(this);
        tip.setText("提示：不写 http:// 也可以，会自动补全。长按标签可编辑/删除，长按「＋」打开设置。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(Color.parseColor("#FF667085"));
        tip.setPadding(0, dp(10), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "新增标签" : "编辑标签")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = nameEt.getText().toString().trim();
                        String url = normalize(urlEt.getText().toString().trim());
                        if (url.isEmpty()) {
                            toast("网址不能为空");
                            return;
                        }
                        if (name.isEmpty()) name = url.replaceFirst("^https?://", "");
                        if (isNew) {
                            addTab(name, url, true);
                        } else {
                            Tab t2 = tabs.get(idx);
                            t2.name = name;
                            t2.url = url;
                            if (t2.web != null) t2.web.loadUrl(url);
                            rebuildTabBar();
                            selectTab(idx, false);
                        }
                        saveState();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettings() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(8));

        final CheckBox hideBar = new CheckBox(this);
        hideBar.setText("隐藏顶部标签栏（长按「＋」可再次打开设置）");
        hideBar.setChecked(prefs.getBoolean("hideBar", false));
        box.addView(hideBar);

        final CheckBox desktop = new CheckBox(this);
        desktop.setText("使用桌面版网页（部分网站布局更合适）");
        desktop.setChecked(prefs.getBoolean("desktopUA", false));
        box.addView(desktop);

        final CheckBox immersive = new CheckBox(this);
        immersive.setText("全屏沉浸（隐藏系统状态栏）");
        immersive.setChecked(prefs.getBoolean("immersive", false));
        box.addView(immersive);

        final CheckBox keepOn = new CheckBox(this);
        keepOn.setText("屏幕常亮（推荐开启）");
        keepOn.setChecked(prefs.getBoolean("keepOn", true));
        box.addView(keepOn);

        TextView lab = new TextView(this);
        lab.setText("自动刷新（秒，0 表示不自动刷新；只刷新当前标签）");
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lab.setPadding(0, dp(10), 0, 0);
        box.addView(lab);

        final EditText refreshEt = new EditText(this);
        refreshEt.setSingleLine(true);
        refreshEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        refreshEt.setText(String.valueOf(prefs.getInt("refreshSec", 0)));
        box.addView(refreshEt);

        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sc)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int sec = 0;
                        try {
                            sec = Integer.parseInt(refreshEt.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        prefs.edit()
                                .putBoolean("hideBar", hideBar.isChecked())
                                .putBoolean("desktopUA", desktop.isChecked())
                                .putBoolean("immersive", immersive.isChecked())
                                .putBoolean("keepOn", keepOn.isChecked())
                                .putInt("refreshSec", Math.max(0, sec))
                                .apply();
                        // 桌面 UA 开关需要重建内核设置
                        for (Tab t : tabs) {
                            if (t.web != null) {
                                t.web.getSettings().setUserAgentString(
                                        desktop.isChecked() ? DESKTOP_UA : null);
                            }
                        }
                        applySettings();
                    }
                })
                .setNeutralButton("重新加载当前", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Tab t = tabs.get(current);
                        if (t.web != null) t.web.reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applySettings() {
        boolean hide = prefs.getBoolean("hideBar", false);
        tabBarScroll.setVisibility(hide ? View.GONE : View.VISIBLE);
        boolean keepOn = prefs.getBoolean("keepOn", true);
        View root = findViewById(R.id.root);
        root.setKeepScreenOn(keepOn);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        boolean im = prefs.getBoolean("immersive", false);
        View decor = getWindow().getDecorView();
        int flags = im
                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : 0;
        decor.setSystemUiVisibility(flags);
        applyRefreshTimer();
    }

    private void applyRefreshTimer() {
        stopRefreshTimer();
        int sec = prefs.getInt("refreshSec", 0);
        if (sec <= 0) return;
        final int interval = sec * 1000;
        refreshTask = new Runnable() {
            @Override
            public void run() {
                if (!tabs.isEmpty()) {
                    Tab t = tabs.get(current);
                    if (t.web != null) t.web.reload();
                }
                handler.postDelayed(this, interval);
            }
        };
        handler.postDelayed(refreshTask, interval);
    }

    private void stopRefreshTimer() {
        if (refreshTask != null) {
            handler.removeCallbacks(refreshTask);
            refreshTask = null;
        }
    }

    // ---------- 持久化 ----------

    private void saveState() {
        JSONArray arr = new JSONArray();
        for (Tab t : tabs) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", t.name);
                o.put("url", t.url);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString("tabs", arr.toString()).putInt("current", current).apply();
    }

    private void loadState() {
        String raw = prefs.getString("tabs", null);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab();
                t.name = o.optString("name");
                t.url = normalize(o.optString("url"));
                t.web = createWebView();
                t.web.loadUrl(t.url);
                webContainer.addView(t.web, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                t.web.setVisibility(View.GONE);
                tabs.add(t);
            }
            current = prefs.getInt("current", 0);
        } catch (Exception e) {
            tabs.clear();
        }
    }

    // ---------- 小工具 ----------

    private static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            u = "http://" + u;
        }
        return u;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
