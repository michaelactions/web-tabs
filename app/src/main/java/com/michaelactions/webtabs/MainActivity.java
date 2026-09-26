package com.michaelactions.webtabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 首页：显示在「设置」里配置好的所有标签（自动带网站图标），
 * 点哪个就直接进哪个网页。
 */
public class MainActivity extends Activity {

    /** 内置默认标签：装好就自带，升级/重装都不会丢 */
    private static final String[][] DEFAULTS = {
            {"工具站", "https://tools.office3.pp.ua/"},
            {"节点切换", "https://tools.office3.pp.ua/failover/"},
            {"状态监控", "https://status.digac.icu/"},
            {"华住会看板", "https://huazhu.office3.pp.ua/"}
    };

    private List<Store.Site> sites;
    private GridView grid;
    private TextView empty;
    private SiteAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean fetching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ensureDefaults();
        sites = Store.load(this);

        grid = (GridView) findViewById(R.id.grid);
        empty = (TextView) findViewById(R.id.empty);
        adapter = new SiteAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(position));
        grid.setOnItemLongClickListener((parent, view, position, id) -> { menu(position); return true; });

        findViewById(R.id.btnAdd).setOnClickListener(v -> edit(-1));
        findViewById(R.id.btnSettings).setOnClickListener(v -> settings());
        ((TextView) findViewById(R.id.title)).setText("标签浏览器");

        fetchMissingIcons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureDefaults();
        sites = Store.load(this);
        adapter.notifyDataSetChanged();
        refreshEmpty();
    }

    private void refreshEmpty() {
        boolean e = sites.isEmpty();
        empty.setVisibility(e ? View.VISIBLE : View.GONE);
        grid.setVisibility(e ? View.GONE : View.VISIBLE);
    }

    private void open(int idx) {
        if (idx < 0 || idx >= sites.size()) return;
        Intent it = new Intent(this, WebActivity.class);
        it.putExtra("index", idx);
        startActivity(it);
    }

    // ---------- 增删改 ----------

    private void edit(final int idx) {
        final boolean isNew = idx < 0;
        final Store.Site s = isNew ? new Store.Site() : sites.get(idx);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("名称，例如：工具站");
        nameEt.setSingleLine(true);
        nameEt.setText(s.name);
        box.addView(nameEt);

        final EditText urlEt = new EditText(this);
        urlEt.setHint("网址，例如：tools.office3.pp.ua");
        urlEt.setSingleLine(true);
        urlEt.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlEt.setText(s.url);
        box.addView(urlEt);

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加标签" : "编辑标签")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String u = Store.normalize(urlEt.getText().toString());
                    if (u.isEmpty()) { toast("网址不能为空"); return; }
                    String n = nameEt.getText().toString().trim();
                    if (n.isEmpty()) n = u.replaceFirst("(?i)^[a-z]+://", "").replaceAll("/.*$", "");
                    s.name = n;
                    s.url = u;
                    if (isNew) sites.add(s);
                    Store.save(MainActivity.this, sites);
                    sites = Store.load(MainActivity.this);
                    adapter.notifyDataSetChanged();
                    refreshEmpty();
                    fetchMissingIcons();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void menu(final int idx) {
        if (idx < 0 || idx >= sites.size()) return;
        final Store.Site s = sites.get(idx);
        final String[] items = {"进入", "编辑", "上移", "下移", "刷新图标", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(items, (d, w) -> {
                    if (w == 0) open(idx);
                    else if (w == 1) edit(idx);
                    else if (w == 2) move(idx, -1);
                    else if (w == 3) move(idx, 1);
                    else if (w == 4) refreshIcon(idx, true);
                    else if (w == 5) confirmDelete(idx);
                })
                .show();
    }

    private void move(int idx, int dir) {
        int to = idx + dir;
        if (to < 0 || to >= sites.size()) return;
        Store.Site a = sites.get(idx);
        sites.set(idx, sites.get(to));
        sites.set(to, a);
        Store.save(this, sites);
        sites = Store.load(this);
        adapter.notifyDataSetChanged();
    }

    private void confirmDelete(final int idx) {
        new AlertDialog.Builder(this)
                .setTitle("删除标签")
                .setMessage("确定删除「" + sites.get(idx).name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    final String delUrl = sites.get(idx).url;
                    Store.iconFile(MainActivity.this, sites.get(idx)).delete();
                    sites.remove(idx);
                    markDefaultRemoved(delUrl);
                    Store.save(MainActivity.this, sites);
                    sites = Store.load(MainActivity.this);
                    adapter.notifyDataSetChanged();
                    refreshEmpty();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 设置 ----------

    private void settings() {
        final android.content.SharedPreferences p = Store.prefs(this);
        final String[] items = {
                "屏幕常亮：" + (p.getBoolean("keepOn", true) ? "开" : "关"),
                "显示标签栏：" + (p.getBoolean("showBar", true) ? "显示" : "隐藏"),
                "全屏沉浸：" + (p.getBoolean("fullscreen", false) ? "开" : "关"),
                "桌面版网页：" + (p.getBoolean("desktopUA", false) ? "开" : "关"),
                "自动刷新：" + p.getInt("refreshSec", 0) + " 秒（0=不刷新）",
                "重新抓取全部图标",
                "恢复默认标签（补齐内置站点）"
        };
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, w) -> {
                    if (w == 0) p.edit().putBoolean("keepOn", !p.getBoolean("keepOn", true)).apply();
                    else if (w == 1) p.edit().putBoolean("showBar", !p.getBoolean("showBar", true)).apply();
                    else if (w == 2) p.edit().putBoolean("fullscreen", !p.getBoolean("fullscreen", false)).apply();
                    else if (w == 3) p.edit().putBoolean("desktopUA", !p.getBoolean("desktopUA", false)).apply();
                    else if (w == 4) askRefresh();
                    else if (w == 5) { clearIcons(); fetchMissingIcons(); }
                    else if (w == 6) { restoreDefaults(); return; }
                    settings();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void askRefresh() {
        final android.content.SharedPreferences p = Store.prefs(this);
        final EditText et = new EditText(this);
        et.setHint("秒，0 表示不自动刷新");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(p.getInt("refreshSec", 0)));
        new AlertDialog.Builder(this)
                .setTitle("自动刷新间隔")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    int sec = 0;
                    try { sec = Integer.parseInt(et.getText().toString().trim()); } catch (Exception ignored) { }
                    if (sec < 0) sec = 0;
                    if (sec > 0 && sec < 5) sec = 5;
                    p.edit().putInt("refreshSec", sec).apply();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearIcons() {
        for (Store.Site s : sites) Store.iconFile(this, s).delete();
        adapter.notifyDataSetChanged();
    }

    // ---------- 图标抓取 ----------

    private void fetchMissingIcons() {
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            boolean any = false;
            for (final Store.Site s : Store.load(MainActivity.this)) {
                if (Store.loadIcon(MainActivity.this, s) != null) continue;
                final Bitmap b = Store.fetchIcon(s);
                if (b != null) {
                    Store.saveIcon(MainActivity.this, s, b);
                    any = true;
                }
            }
            final boolean changed = any;
            ui.post(() -> {
                fetching = false;
                if (changed) adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void refreshIcon(final int idx, final boolean toastResult) {
        final Store.Site s = sites.get(idx);
        Store.iconFile(this, s).delete();
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            final Bitmap b = Store.fetchIcon(s);
            if (b != null) Store.saveIcon(MainActivity.this, s, b);
            ui.post(() -> {
                adapter.notifyDataSetChanged();
                if (toastResult) toast(b != null ? "图标已更新" : "没抓到图标，已用字母代替");
            });
        }).start();
    }

    // ---------- 内置默认标签 ----------

    /** 把内置默认标签补齐（用户手动删掉的不会再加回来） */
    private void ensureDefaults() {
        android.content.SharedPreferences p = Store.prefs(this);
        String removed = p.getString("removedDefaults", "|");
        List<Store.Site> list = Store.load(this);
        boolean changed = false;
        for (String[] d : DEFAULTS) {
            if (removed.contains("|" + d[1] + "|")) continue;
            boolean found = false;
            for (Store.Site s : list) {
                if (s.url != null && s.url.equalsIgnoreCase(d[1])) { found = true; break; }
            }
            if (!found) {
                list.add(new Store.Site(d[0], d[1]));
                changed = true;
            }
        }
        if (changed) Store.save(this, list);
    }

    private boolean isDefault(String url) {
        for (String[] d : DEFAULTS) if (d[1].equalsIgnoreCase(url)) return true;
        return false;
    }

    /** 记下"用户主动删掉的默认站"，避免下次又冒出来 */
    private void markDefaultRemoved(String url) {
        if (!isDefault(url)) return;
        android.content.SharedPreferences p = Store.prefs(this);
        String removed = p.getString("removedDefaults", "|");
        if (!removed.contains("|" + url + "|")) {
            p.edit().putString("removedDefaults", removed + url + "|").apply();
        }
    }

    /** 恢复默认标签：清掉删除记录并补齐 */
    private void restoreDefaults() {
        Store.prefs(this).edit().putString("removedDefaults", "|").apply();
        ensureDefaults();
        sites = Store.load(this);
        adapter.notifyDataSetChanged();
        refreshEmpty();
        fetchMissingIcons();
        toast("默认标签已恢复");
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ---------- 列表适配器 ----------

    private class SiteAdapter extends BaseAdapter {
        @Override public int getCount() { return sites.size(); }
        @Override public Object getItem(int i) { return sites.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_site, parent, false);
            Store.Site s = sites.get(i);
            ImageView iv = (ImageView) v.findViewById(R.id.icon);
            Bitmap b = Store.loadIcon(MainActivity.this, s);
            if (b == null) b = Store.letterIcon(s);
            iv.setImageBitmap(b);
            ((TextView) v.findViewById(R.id.name)).setText(s.name);
            ((TextView) v.findViewById(R.id.host)).setText(s.host());
            return v;
        }
    }
}
