package com.michaelactions.webtabs;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 站点（标签）数据与图标的本地存储/抓取。
 * 列表存在 SharedPreferences，图标 PNG 存在 filesDir/icons 下。
 */
public class Store {

    public static class Site {
        public String name = "";
        public String url = "";
        public Site() {}
        public Site(String n, String u) { name = n; url = u; }
        public String host() {
            try {
                String u = url;
                int i = u.indexOf("://");
                if (i >= 0) u = u.substring(i + 3);
                int j = u.indexOf('/');
                if (j >= 0) u = u.substring(0, j);
                int k = u.indexOf(':');
                if (k >= 0) u = u.substring(0, k);
                return u;
            } catch (Exception e) { return url; }
        }
    }

    private static final String PREF = "webtabs";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "http://" + u;
        return u;
    }

    public static List<Site> load(Context c) {
        List<Site> list = new ArrayList<>();
        String raw = prefs(c).getString("sites", null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Site s = new Site();
                s.name = o.optString("name");
                s.url = normalize(o.optString("url"));
                list.add(s);
            }
        } catch (Exception ignored) { }
        return list;
    }

    public static void save(Context c, List<Site> list) {
        JSONArray arr = new JSONArray();
        for (Site s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs(c).edit().putString("sites", arr.toString()).apply();
    }

    // ---------- 图标 ----------

    private static String key(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    public static File iconFile(Context c, Site s) {
        File dir = new File(c.getFilesDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key(s.url) + ".png");
    }

    public static Bitmap loadIcon(Context c, Site s) {
        try {
            File f = iconFile(c, s);
            if (f.exists() && f.length() > 0) return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception ignored) { }
        return null;
    }

    public static void saveIcon(Context c, Site s, Bitmap bmp) {
        try {
            File f = iconFile(c, s);
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception ignored) { }
    }

    /** 生成字母头像（首字母 + 固定颜色），保证列表里不会空着 */
    public static Bitmap letterIcon(Site s) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        int[] colors = {0xFF1570EF, 0xFF12B76A, 0xFFF79009, 0xFF7A5AF8, 0xFFEE46BC, 0xFF06AED4};
        int idx = Math.abs((s.name + s.url).hashCode()) % colors.length;
        cv.drawColor(colors[idx]);
        String ch = (s.name != null && s.name.length() > 0)
                ? s.name.substring(0, 1)
                : (s.host().length() > 0 ? s.host().substring(0, 1).toUpperCase() : "?");
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.5f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(ch, 0, ch.length(), r);
        cv.drawText(ch, size / 2f, size / 2f - r.exactCenterY() + r.height() / 2f, p);
        return bmp;
    }

    /** 联网抓取网站图标：先 /favicon.ico，再 /favicon.png；失败返回 null */
    public static Bitmap fetchIcon(Site s) {
        String[] candidates = {
                s.url.replaceAll("(?i)^http://", "http://") ,
                s.host()
        };
        String[] paths;
        String scheme = s.url.toLowerCase().startsWith("https://") ? "https" : "http";
        String host = s.host();
        if (host.isEmpty()) return null;
        String[] attempts = {
                scheme + "://" + host + "/favicon.ico",
                "https://" + host + "/favicon.ico",
                "http://" + host + "/favicon.ico",
                scheme + "://" + host + "/favicon.png",
                "https://" + host + "/favicon.png"
        };
        for (String u : attempts) {
            Bitmap b = downloadImage(u);
            if (b != null && b.getWidth() >= 8) return b;
        }
        return null;
    }

    private static Bitmap downloadImage(String u) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(u);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WebTabs");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            String type = conn.getContentType();
            if (type != null && type.contains("text/html")) return null; // 不是图标
            InputStream in = conn.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
