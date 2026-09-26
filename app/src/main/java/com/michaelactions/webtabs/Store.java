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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 站点（标签）数据与图标的本地存储。
 * 默认站点的图标已随 App 打包（assets/icons/<urlKey>.png），不联网即可显示；
 * 只有当网站那边的图标真的变了，才会联网取回新图标替换。
 */
public class Store {

    /** 联网检查网站图标是否变化的间隔（默认 12 小时） */
    public static final long ICON_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;

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

    // ---------- 图标文件 ----------

    public static String key(String s) {
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

    public static File iconDir(Context c) {
        File dir = new File(c.getFilesDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File iconFile(Context c, Site s) {
        return new File(iconDir(c), key(s.url) + ".png");
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
            FileOutputStream fos = new FileOutputStream(iconFile(c, s));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception ignored) { }
    }

    /**
     * 首次运行：把 App 内置的图标（assets/icons/<key>.png）落到本地缓存。
     * 这样默认站点一打开就有图标，完全不用联网。
     */
    public static void seedBundledIcons(Context c, List<Site> sites) {
        for (Site s : sites) {
            File f = iconFile(c, s);
            if (f.exists() && f.length() > 0) continue;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = c.getAssets().open("icons/" + key(s.url) + ".png");
                out = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                setChecked(c, s, System.currentTimeMillis());
            } catch (Exception ignored) {
                // assets 里没有这个站的内置图标：留着后面联网抓
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                try { if (out != null) out.close(); } catch (Exception ignored) { }
            }
        }
    }

    public static boolean hasIcon(Context c, Site s) {
        File f = iconFile(c, s);
        return f.exists() && f.length() > 0;
    }

    public static long lastChecked(Context c, Site s) {
        return prefs(c).getLong("t_" + key(s.url), 0L);
    }

    public static void setChecked(Context c, Site s, long t) {
        prefs(c).edit().putLong("t_" + key(s.url), t).apply();
    }

    public static boolean needCheck(Context c, Site s) {
        return System.currentTimeMillis() - lastChecked(c, s) > ICON_CHECK_INTERVAL_MS;
    }

    // ---------- 联网抓图标 ----------

    /**
     * 只有网站的图标真的换了才更新本地图标，返回 true 表示已更新。
     * 第一次联网只记基线（不动内置图标）；之后每次比对，变了才替换。
     */
    public static boolean checkRemoteChanged(Context c, Site s) {
        String[] urls = faviconCandidates(s);
        byte[] raw = null;
        for (String u : urls) {
            raw = downloadBytes(u);
            if (raw != null) break;
        }
        setChecked(c, s, System.currentTimeMillis());
        if (raw == null) return false;

        String hash = sha1(raw);
        String prefKey = "ic_" + key(s.url);
        String last = prefs(c).getString(prefKey, "");
        prefs(c).edit().putString(prefKey, hash).apply();

        if (last.isEmpty()) {
            // 第一次联网：本地还没图标才用（内置图标优先保留）
            if (hasIcon(c, s)) return false;
        } else if (hash.equals(last)) {
            return false;   // 网站图标没变，什么都不做
        }
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (b == null || b.getWidth() < 8) return false;
        saveIcon(c, s, b);
        return true;
    }

    /** 用户手动"刷新图标"：无条件联网取一次 */
    public static Bitmap fetchIcon(Site s) {
        for (String u : faviconCandidates(s)) {
            Bitmap b = downloadImage(u);
            if (b != null && b.getWidth() >= 8) return b;
        }
        return null;
    }

    private static String[] faviconCandidates(Site s) {
        String host = s.host();
        String scheme = s.url.toLowerCase().startsWith("https://") ? "https" : "http";
        if (host.isEmpty()) return new String[0];
        return new String[]{
                scheme + "://" + host + "/favicon.ico",
                "https://" + host + "/favicon.ico",
                "http://" + host + "/favicon.ico",
                scheme + "://" + host + "/favicon.png",
                "https://" + host + "/favicon.png"
        };
    }

    private static byte[] downloadBytes(String u) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(u);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WebTabs");
            if (conn.getResponseCode() != 200) return null;
            String type = conn.getContentType();
            if (type != null && type.contains("text/html")) return null;   // 不是图标
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] data = bos.toByteArray();
            return data.length > 0 ? data : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap downloadImage(String u) {
        byte[] raw = downloadBytes(u);
        return raw == null ? null : BitmapFactory.decodeByteArray(raw, 0, raw.length);
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "len" + data.length;
        }
    }

    /** 字母头像（首字母 + 固定颜色）兜底 */
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
}
