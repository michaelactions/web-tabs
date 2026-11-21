package com.michaelactions.webtabs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real emulator WebView -> local relay -> mock authenticated upstream for every LAN. */
@RunWith(AndroidJUnit4.class)
public class WebTabsSmokeTest {
    private android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private static class Mock implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger hits = new AtomicInteger();
        final String marker;
        final String body;
        volatile boolean running = true;
        volatile Throwable error;
        Mock(String marker) throws IOException { this(marker, defaultBody(marker)); }
        Mock(String marker, String body) throws IOException {
            this.marker = marker;
            this.body = body;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> { while (running) {
                try (Socket c = server.accept()) {
                    c.setSoTimeout(10000);
                    String h = head(c.getInputStream());
                    if (!h.contains("Proxy-Authorization: Basic test-" + marker)) throw new AssertionError("missing proxy auth " + marker);
                    hits.incrementAndGet();
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    c.getOutputStream().write(data); c.getOutputStream().flush();
                } catch (Throwable e) { if (running) error = e; }
            }});
            t.setDaemon(true); t.start();
        }
        private static String defaultBody(String marker) {
            return "<!doctype html><html><head><title>" + marker + "</title><meta name='viewport' content='width=device-width'></head>"
                + "<body style='font-family:sans-serif;background:#f5f7fb;padding:32px'><h1>WebTabs</h1><h2>" + marker + "</h2><p>Authenticated proxy route verified</p></body></html>";
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws IOException { running = false; server.close(); }
    }
    static String head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int tail = 0;
        while (b.size() < 65536) { int c = in.read(); if (c < 0) break;
            b.write(c); tail = (tail << 8) | c; if (tail == 0x0d0a0d0a) break; }
        return b.toString("ISO-8859-1");
    }
    private WebView activeWeb(Activity a) {
        FrameLayout container = a.findViewById(R.id.webContainer);
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            if (v instanceof WebView && v.getVisibility() == View.VISIBLE) return (WebView) v;
        }
        return null;
    }

    /** 1366x768 skin on a white body, same shape as AIDA64 RemoteSensor. */
    private static final String AIDA_PAGE =
        "<!doctype html><html><head><title>AIDA64</title><meta name='viewport' content='width=1366'>"
        + "<style>html,body{margin:0;padding:0}"
        + "body{background-color:#FFFFFF;background-repeat:no-repeat;background-attachment:fixed;background-position:center}"
        + "#page0{position:relative;width:1366px;height:768px;background:#3B3B3B}"
        + "</style></head><body><div id='page0'></div></body></html>";

    private static final String PLAIN_PAGE =
        "<!doctype html><html><head><title>Plain</title><meta name='viewport' content='width=device-width'>"
        + "</head><body style='margin:0;background-color:#FFFFFF'><h1>Plain</h1></body></html>";

    private WebView waitForTitle(Activity activity, String title) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 25000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            final String[] current = new String[1];
            getInstrumentation().runOnMainSync(() -> {
                WebView w = activeWeb(activity);
                current[0] = w == null || w.getTitle() == null ? "" : w.getTitle();
            });
            if (title.equals(current[0])) {
                final WebView[] found = new WebView[1];
                getInstrumentation().runOnMainSync(() -> found[0] = activeWeb(activity));
                assertNotNull("no active WebView", found[0]);
                return found[0];
            }
            Thread.sleep(200);
        }
        throw new AssertionError("page title never became " + title);
    }

    /** evaluateJavascript returns a JSON literal; unwrap the JSON string we ask the page to produce. */
    private JSONArray evalArray(WebView view, String expression) throws Exception {
        final String[] out = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        getInstrumentation().runOnMainSync(() -> view.evaluateJavascript("JSON.stringify(" + expression + ")",
            value -> { out[0] = value; done.countDown(); }));
        assertTrue("JS evaluation timed out", done.await(15, TimeUnit.SECONDS));
        assertNotNull(out[0]);
        Object unwrapped = new JSONTokener(out[0]).nextValue();
        assertTrue("unexpected JS result " + out[0], unwrapped instanceof String);
        return new JSONArray((String) unwrapped);
    }

    private String evalString(WebView view, String expression) throws Exception {
        final String[] out = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(expression,
            value -> { out[0] = value; done.countDown(); }));
        assertTrue("JS evaluation timed out", done.await(15, TimeUnit.SECONDS));
        return out[0];
    }

    private void assertChannelNear(String what, int actual, int expected, int tolerance) {
        int r = Color.red(actual), g = Color.green(actual), b = Color.blue(actual);
        assertTrue(what + " expected ~" + String.format("#%06X", expected & 0xFFFFFF)
            + " but was " + String.format("#%06X", actual & 0xFFFFFF),
            Math.abs(r - Color.red(expected)) <= tolerance
            && Math.abs(g - Color.green(expected)) <= tolerance
            && Math.abs(b - Color.blue(expected)) <= tolerance);
    }

    /** 等 WebView 报告一帧视觉状态；超时不直接失败，交给后面的截图轮询判定。 */
    private boolean awaitVisualFrame(WebView view) throws Exception {
        final CountDownLatch drawn = new CountDownLatch(1);
        getInstrumentation().runOnMainSync(() ->
            view.postVisualStateCallback(System.nanoTime(), new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) { drawn.countDown(); }
            }));
        return drawn.await(5, TimeUnit.SECONDS);
    }

    private static boolean nearColor(int pixel, int expected, int tolerance) {
        return Math.abs(Color.red(pixel) - Color.red(expected)) <= tolerance
            && Math.abs(Color.green(pixel) - Color.green(expected)) <= tolerance
            && Math.abs(Color.blue(pixel) - Color.blue(expected)) <= tolerance;
    }

    private static int darkLetterboxRows(Bitmap shot, int x, int fromY) {
        int rows = 0;
        for (int y = fromY; y < shot.getHeight(); y++) {
            if (nearColor(shot.getPixel(x, y), 0xFF1B1B1B, 12)) rows++;
        }
        return rows;
    }

    /** 视觉状态回调 + 有界截图轮询，等 WebView 真正合成出皮肤和黑边（JS 就绪 != 已上屏）。 */
    private Bitmap awaitStableAidaPixels(WebView view) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        Bitmap last = null;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            awaitVisualFrame(view);
            last = getInstrumentation().getUiAutomation().takeScreenshot();
            if (last != null && last.getWidth() > 0 && last.getHeight() > 720) {
                int cx = last.getWidth() / 2;
                if (nearColor(last.getPixel(cx, 400), 0xFF3B3B3B, 4)
                        && darkLetterboxRows(last, cx, 700) >= 20) return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    @Test public void testAllProxyRoutesFullscreenAndKeepOn() throws Throwable {
        ProxyServer.resetForTests();
        MainActivity main = (MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try (Mock a = new Mock("Nanan-Telecom"); Mock b = new Mock("Nanan-Unicom"); Mock c = new Mock("Rongchang-Unicom")) {
            ProxyServer.start(new ProxyServer.Upstream[]{
                new ProxyServer.Upstream("127.0.0.1", a.port(), "Basic test-" + a.marker),
                new ProxyServer.Upstream("127.0.0.1", b.port(), "Basic test-" + b.marker),
                new ProxyServer.Upstream("127.0.0.1", c.port(), "Basic test-" + c.marker)
            });
            List<Store.Site> sites = Arrays.asList(
                new Store.Site("AIDA64", "http://10.0.1.10/"),
                new Store.Site("南岸联通", "http://10.0.2.1/"),
                new Store.Site("荣昌联通", "http://172.30.6.1/")
            );
            getInstrumentation().runOnMainSync(() -> {
                Store.save(main, sites);
                Store.prefs(main).edit().putBoolean("fullscreen", true).putBoolean("showBar", true).putBoolean("keepOn", true).commit();
            });
            Mock[] mocks = {a, b, c};
            for (int i = 0; i < 3; i++) {
                final int index = i;
                Intent intent = new Intent(main, WebActivity.class).putExtra("index", index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Activity web = getInstrumentation().startActivitySync(intent);
                try {
                    long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
                    AtomicReference<String> title = new AtomicReference<>("");
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        getInstrumentation().runOnMainSync(() -> {
                            WebView w = activeWeb(web); title.set(w == null ? "" : w.getTitle());
                        });
                        if (mocks[i].marker.equals(title.get())) break;
                        Thread.sleep(200);
                    }
                    assertEquals("WebView must reach correct proxy, not direct", mocks[i].marker, title.get());
                    assertTrue(mocks[i].hits.get() > 0);
                    assertNull(mocks[i].error);
                    getInstrumentation().runOnMainSync(() -> {
                        assertEquals(View.GONE, web.findViewById(R.id.topRow).getVisibility());
                        assertTrue((web.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
                        assertTrue(web.findViewById(R.id.root).getKeepScreenOn());
                        WindowInsets insets = web.getWindow().getDecorView().getRootWindowInsets();
                        if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
                            assertFalse(insets.isVisible(WindowInsets.Type.statusBars()));
                            assertFalse(insets.isVisible(WindowInsets.Type.navigationBars()));
                        }
                        FrameLayout container = web.findViewById(R.id.webContainer);
                        int loaded = 0;
                        for (int j = 0; j < container.getChildCount(); j++) {
                            View v = container.getChildAt(j);
                            if (v instanceof WebView && ((WebView)v).getUrl() != null) loaded++;
                        }
                        assertEquals("Only selected tab loads", 1, loaded);
                    });
                    if (i == 2) {
                        Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
                        try (FileOutputStream out = new FileOutputStream(new File(main.getFilesDir(), "smoke.png"))) {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    }
                    getInstrumentation().runOnMainSync(() -> {
                        Store.prefs(web).edit().putBoolean("keepOn", false).commit();
                        FullScreen.applyKeepScreenOn(web);
                        assertFalse(web.findViewById(R.id.root).getKeepScreenOn());
                        assertEquals(0, web.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        Store.prefs(web).edit().putBoolean("keepOn", true).commit();
                    });
                } finally { getInstrumentation().runOnMainSync(web::finish); }
            }
        }
    }

    /**
     * AIDA64 的固定 1366x768 皮肤按宽度缩放后，底部会露出 body 的白底。
     * 断言：只对 10.0.1.10 把 html/body 背景刷深，皮肤尺寸/比例不变；
     * 1280x800 平板截图底部确实是深色；导航到其它站点后白底和 WebView 底色恢复。
     */
    @Test public void testAidaLetterboxBackgroundAndScope() throws Throwable {
        ProxyServer.resetForTests();
        try (Mock aida = new Mock("AIDA64", AIDA_PAGE); Mock plain = new Mock("Plain", PLAIN_PAGE)) {
            ProxyServer.start(new ProxyServer.Upstream[]{
                new ProxyServer.Upstream("127.0.0.1", aida.port(), "Basic test-" + aida.marker),
                new ProxyServer.Upstream("127.0.0.1", plain.port(), "Basic test-" + plain.marker),
                null
            });
            MainActivity main = (MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().runOnMainSync(() -> {
                Store.save(main, Arrays.asList(
                    new Store.Site("AIDA64", "http://10.0.1.10/"),
                    new Store.Site("普通站点", "http://10.0.2.1/")));
                Store.prefs(main).edit().putBoolean("fullscreen", true).putBoolean("showBar", true).putBoolean("keepOn", true).commit();
            });
            Activity web = getInstrumentation().startActivitySync(
                new Intent(main, WebActivity.class).putExtra("index", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                WebView view = waitForTitle(web, "AIDA64");

                long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
                String marker = "";
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    marker = evalString(view, "document.documentElement.getAttribute('data-webtabs-aida')");
                    if ("\"1\"".equals(marker)) break;
                    Thread.sleep(200);
                }
                assertEquals("\"1\"", marker);

                JSONArray skin = evalArray(view,
                    "(function(){var r=document.getElementById('page0').getBoundingClientRect();"
                    + "return [getComputedStyle(document.body).backgroundColor,"
                    + "getComputedStyle(document.documentElement).backgroundColor,"
                    + "r.width,r.height,window.innerWidth,window.innerHeight,"
                    + "document.documentElement.scrollWidth];})()");

                // 先等 WebView 真正把这一帧合成上屏，再存图/存诊断，最后才断言像素。
                Bitmap shot = awaitStableAidaPixels(view);
                assertNotNull("no screenshot", shot);
                try (FileOutputStream out = new FileOutputStream(new File(main.getFilesDir(), "aida-letterbox.png"))) {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                int cx = shot.getWidth() / 2;
                try (Writer diag = new OutputStreamWriter(new FileOutputStream(
                        new File(main.getFilesDir(), "aida-letterbox.txt")), StandardCharsets.UTF_8)) {
                    diag.write("size=" + shot.getWidth() + "x" + shot.getHeight() + "\n");
                    diag.write("skinCentre=" + String.format("#%06X", shot.getPixel(cx, 400) & 0xFFFFFF) + "\n");
                    diag.write("bottom=" + String.format("#%06X", shot.getPixel(cx, shot.getHeight() - 10) & 0xFFFFFF) + "\n");
                    diag.write("darkRowsBelow700=" + darkLetterboxRows(shot, cx, 700) + "\n");
                    diag.write("js=" + skin + "\n");
                }

                assertEquals("AIDA body background must be dark", "rgb(27, 27, 27)", skin.getString(0));
                assertEquals("AIDA html background must be dark", "rgb(27, 27, 27)", skin.getString(1));
                assertEquals("skin width must not change", 1366.0, skin.getDouble(2), 0.5);
                assertEquals("skin height must not change", 768.0, skin.getDouble(3), 0.5);
                assertTrue("layout width must stay width-fit: " + skin.getDouble(4),
                    skin.getDouble(4) >= 1280.0 && skin.getDouble(4) <= 1370.0);
                assertTrue("letterbox viewport must be taller than the skin: " + skin.getDouble(5),
                    skin.getDouble(5) > 768.0 + 20.0);
                assertEquals("horizontal layout width must not change", 1366.0, skin.getDouble(6), 0.5);
                assertEquals("regression must run on the 1280x800 tablet AVD", 1280, shot.getWidth());
                assertEquals("regression must run on the 1280x800 tablet AVD", 800, shot.getHeight());
                assertChannelNear("AIDA skin centre", shot.getPixel(cx, 400), 0xFF3B3B3B, 4);
                int darkRows = darkLetterboxRows(shot, cx, 700);
                assertTrue("AIDA letterbox must be repainted dark below the skin, dark rows=" + darkRows,
                    darkRows >= 20);

                getInstrumentation().runOnMainSync(() -> view.loadUrl("http://10.0.2.1/"));
                waitForTitle(web, "Plain");
                awaitVisualFrame(view);
                JSONArray plainState = evalArray(view,
                    "(function(){return [getComputedStyle(document.body).backgroundColor,"
                    + "document.documentElement.getAttribute('data-webtabs-aida')];})()");
                assertEquals("non-AIDA site must keep its white body", "rgb(255, 255, 255)", plainState.getString(0));
                assertTrue("non-AIDA site must not be marked as AIDA", plainState.isNull(1));
                final int[] nativeBg = new int[1];
                getInstrumentation().runOnMainSync(() -> {
                    Drawable d = view.getBackground();
                    nativeBg[0] = d instanceof ColorDrawable ? ((ColorDrawable) d).getColor() : Color.TRANSPARENT;
                });
                assertEquals("WebView background must reset to white away from AIDA", Color.WHITE, nativeBg[0]);
            } finally { getInstrumentation().runOnMainSync(web::finish); }
        }
    }
}
