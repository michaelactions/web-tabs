package com.michaelactions.webtabs;

import android.graphics.Color;
import android.net.Uri;
import android.webkit.WebView;

/**
 * AIDA64 RemoteSensor（只认 10.0.1.10）的固定比例页面：WebView 按宽度缩放后，
 * 底部会露出 body 的白色背景。这里只把 html/body 背景刷成深色，不裁切、不缩放、
 * 不动页面里任何元素的位置和尺寸；其它站点完全不受影响。
 */
final class AidaSkin {

    static final String HOST = "10.0.1.10";
    static final int LETTERBOX = 0xFF1B1B1B;

    private static final String DARKEN_JS =
            "(function(){try{var d=document;"
            + "if(!d.body||window.location.hostname!=='10.0.1.10')return;"
            + "d.documentElement.style.setProperty('background-color','#1b1b1b','important');"
            + "d.body.style.setProperty('background-color','#1b1b1b','important');"
            + "d.documentElement.setAttribute('data-webtabs-aida','1');"
            + "}catch(e){}})();";

    static boolean isAida(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return HOST.equals(host);
    }

    /** 开始加载时先定 WebView 底色，避免 AIDA 页首帧闪白；其它站点保持默认白底。 */
    static void started(WebView view, String url) {
        if (view == null) return;
        view.setBackgroundColor(isAida(url) ? LETTERBOX : Color.WHITE);
    }

    /** 页面 DOM 就绪后再刷 html/body 背景；只改背景色，不动布局。 */
    static void finished(WebView view, String url) {
        if (view == null || !isAida(url)) return;
        view.setBackgroundColor(LETTERBOX);
        view.evaluateJavascript(DARKEN_JS, null);
    }

    private AidaSkin() {}
}
