package com.michaelactions.webtabs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页浏览页：顶部标签栏（带网站图标），点哪个切哪个；屏幕常亮。
 */
public class WebActivity extends Activity {

    private static class Tab {
        Store.Site site;
        WebView web;
        Button btn;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int current = 0;
    private LinearLayout tabBar;
    private FrameLayout container;
    private HorizontalScrollView barScroll;
    private ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int refreshSec = 0;
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (refreshSec > 0 && current < tabs.size()) {
                WebView w = tabs.get(current).web;
                if (w != null && w.getUrl() != null) w.reload();
                handler.postDelayed(this, refreshSec * 1000L);
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);

        FullScreen.applyKeepScreenOn(this);
        FullScreen.apply(this);
        refreshSec = Store.prefs(this).getInt("refreshSec", 0);

        tabBar = (LinearLayout) findViewById(R.id.tabBar);
        container = (FrameLayout) findViewById(R.id.webContainer);
        barScroll = (HorizontalScrollView) findViewById(R.id.tabBarScroll);
        progress = (ProgressBar) findViewById(R.id.progress);

        List<Store.Site> sites = Store.load(this);
        final int start = getIntent().getIntExtra("index", 0);
        if (sites.isEmpty()) { Toast.makeText(this, "还没有标签", Toast.LENGTH_SHORT).show(); finish(); return; }
        for (Store.Site s : sites) addTab(s);

        findViewById(R.id.btnHome).setOnClickListener(v -> finish());
        findViewById(R.id.btnHome).setOnLongClickListener(v -> { settings(); return true; });

        if (!Store.prefs(this).getBoolean("showBar", true)) {
            barScroll.setVisibility(View.GONE);
        }

        select(Math.max(0, Math.min(start, tabs.size() - 1)));
    }

    private void addTab(final Store.Site site) {
        final Tab t = new Tab();
        t.site = site;
        t.web = createWebView(site);

        Button b = new Button(this);
        b.setText(site.name);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(24, 8, 24, 8);
        b.setBackgroundColor(0xFFF2F4F7);
        b.setTextColor(0xFF344054);
        Bitmap ic = Store.loadIcon(this, site);
        if (ic != null) {
            int h = (int) (getResources().getDisplayMetrics().density * 16);
            b.setCompoundDrawablesWithIntrinsicBounds(new android.graphics.drawable.BitmapDrawable(getResources(), Bitmap.createScaledBitmap(ic, h, h, true)), null, null, null);
            b.setCompoundDrawablePadding(10);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 6, 0, 6);
        b.setLayoutParams(lp);
        final int idx = tabs.size();
        b.setOnClickListener(v -> select(idx));
        b.setOnLongClickListener(v -> { tabMenu(idx); return true; });
        tabBar.addView(b);
        container.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.btn = b;
        tabs.add(t);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView(Store.Site site) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (Store.prefs(this).getBoolean("desktopUA", false)) {
            s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36");
        }
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
            @Override public void onReceivedIcon(WebView view, Bitmap icon) {
                if (icon != null) {
                    for (Tab t : tabs) {
                        if (t.web == view) { Store.saveIcon(WebActivity.this, t.site, icon); break; }
                    }
                }
            }
        });
        w.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Toast.makeText(WebActivity.this, "无法打开下载链接", Toast.LENGTH_SHORT).show(); }
            }
        });
        w.loadUrl(site.url);
        w.setVisibility(View.GONE);
        return w;
    }

    private void select(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        current = idx;
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            t.web.setVisibility(i == idx ? View.VISIBLE : View.GONE);
            t.btn.setBackgroundColor(i == idx ? 0xFF1570EF : 0xFFF2F4F7);
            t.btn.setTextColor(i == idx ? Color.WHITE : 0xFF344054);
        }
        barScroll.post(() -> {
            View v = tabs.get(current).btn;
            barScroll.smoothScrollTo(Math.max(0, v.getLeft() - 40), 0);
        });
        handler.removeCallbacks(refresher);
        if (refreshSec > 0) handler.postDelayed(refresher, refreshSec * 1000L);
    }

    private void tabMenu(final int idx) {
        final Tab t = tabs.get(idx);
        String[] items = {"重新加载", "复制网址", "外部浏览器打开", "只看这个"};
        new AlertDialog.Builder(this)
                .setTitle(t.site.name)
                .setItems(items, (d, w) -> {
                    if (w == 0) t.web.reload();
                    else if (w == 1) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", t.web.getUrl() == null ? t.site.url : t.web.getUrl()));
                        Toast.makeText(this, "网址已复制", Toast.LENGTH_SHORT).show();
                    } else if (w == 2) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t.web.getUrl() == null ? t.site.url : t.web.getUrl()))); }
                        catch (Exception e) { Toast.makeText(this, "无法打开", Toast.LENGTH_SHORT).show(); }
                    } else if (w == 3) {
                        Intent it = getIntent();
                        it.putExtra("index", idx);
                        finish();
                        startActivity(it);
                    }
                })
                .show();
    }

    private void settings() {
        final android.content.SharedPreferences p = Store.prefs(this);
        String[] items = {
                "屏幕常亮：" + (p.getBoolean("keepOn", true) ? "开" : "关"),
                "显示标签栏：" + (p.getBoolean("showBar", true) ? "显示" : "隐藏"),
                "全屏沉浸：" + (p.getBoolean("fullscreen", true) ? "开" : "关"),
                "自动刷新：" + p.getInt("refreshSec", 0) + " 秒（重启本文页生效）"
        };
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, w) -> {
                    if (w == 0) p.edit().putBoolean("keepOn", !p.getBoolean("keepOn", true)).apply();
                    else if (w == 1) p.edit().putBoolean("showBar", !p.getBoolean("showBar", true)).apply();
                    else if (w == 2) p.edit().putBoolean("fullscreen", !p.getBoolean("fullscreen", true)).apply();
                    reapplyWindowFlags();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void reapplyWindowFlags() {
        FullScreen.applyKeepScreenOn(this);
        FullScreen.apply(this);
        barScroll.setVisibility(Store.prefs(this).getBoolean("showBar", true) ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullScreen.apply(this);   // 弹窗/切回来后恢复全屏
    }

    @Override
    public void onBackPressed() {
        Tab t = tabs.get(current);
        if (t.web != null && t.web.canGoBack()) t.web.goBack();
        else finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshSec > 0) handler.postDelayed(refresher, refreshSec * 1000L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        for (Tab t : tabs) {
            if (t.web != null) {
                container.removeView(t.web);
                t.web.destroy();
            }
        }
        tabs.clear();
        super.onDestroy();
    }
}
