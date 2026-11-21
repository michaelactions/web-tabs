package com.michaelactions.webtabs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.test.ActivityInstrumentationTestCase2;
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
public class WebTabsSmokeTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public WebTabsSmokeTest() { super(MainActivity.class); }
    private static class Mock implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger hits = new AtomicInteger();
        final String marker;
        volatile boolean running = true;
        volatile Throwable error;
        Mock(String marker) throws IOException {
            this.marker = marker;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> { while (running) {
                try (Socket c = server.accept()) {
                    c.setSoTimeout(10000);
                    String h = head(c.getInputStream());
                    if (!h.contains("Proxy-Authorization: Basic test-" + marker)) throw new AssertionError("missing proxy auth " + marker);
                    hits.incrementAndGet();
                    String body = "<!doctype html><html><head><title>" + marker + "</title><meta name='viewport' content='width=device-width'></head>"
                        + "<body style='font-family:sans-serif;background:#f5f7fb;padding:32px'><h1>WebTabs</h1><h2>" + marker + "</h2><p>Authenticated proxy route verified</p></body></html>";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    c.getOutputStream().write(data); c.getOutputStream().flush();
                } catch (Throwable e) { if (running) error = e; }
            }});
            t.setDaemon(true); t.start();
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
    public void testAllProxyRoutesFullscreenAndKeepOn() throws Throwable {
        MainActivity main = getActivity();
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
            runTestOnUiThread(() -> {
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
                        try (FileOutputStream out = new FileOutputStream(new File(main.getExternalFilesDir(null), "smoke.png"))) {
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
}
