package com.michaelactions.webtabs;

import android.graphics.Color;
import android.net.Uri;
import android.webkit.WebView;

/**
 * AIDA64 RemoteSensor（只认 10.0.1.10）的固定比例页面：WebView 按宽度缩放后，
 * 上下会露出 body 的白色背景。这里把 html/body 背景刷成深色，并把皮肤整体
 * 上下居中（左右不动、不裁切、不缩放），让上下留边等宽；其它站点完全不受影响。
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

    /**
     * 垂直居中：量出皮肤实际高度与视口高度之差，一半放到上方。
     * 只改 margin/padding 这一个偏移量，宽度、缩放比例、皮肤内部布局都不变；
     * 每次都用绝对像素覆盖写入，重复执行不会累积漂移。内容比视口高时不动。
     */
    private static final String CENTER_JS =
            "(function(){try{var d=document;"
            + "if(!d.body||window.location.hostname!=='10.0.1.10')return;"
            + "var vh=window.innerHeight||0;"
            + "var skin=d.getElementById('page0');"
            + "var cs=getComputedStyle(d.body);"
            + "var base=skin?skin.getBoundingClientRect().height"
            + ":(d.body.scrollHeight-(parseFloat(cs.paddingTop)||0));"
            + "if(!(vh>0)||!(base>0))return;"
            + "var off=Math.max(0,Math.round((vh-base)/2));"
            + "if(off>0){"
            + "if(skin){skin.style.setProperty('margin-top',off+'px','important');}"
            + "else{d.body.style.setProperty('padding-top',off+'px','important');}"
            + "}else{"
            + "if(skin){skin.style.removeProperty('margin-top');}"
            + "else{d.body.style.removeProperty('padding-top');}"
            + "}"
            + "d.documentElement.setAttribute('data-webtabs-aida-offset',String(off));"
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

    /** 页面 DOM 就绪后刷背景并把皮肤上下居中；只改背景色和一个上偏移量。 */
    static void finished(final WebView view, String url) {
        if (view == null || !isAida(url)) return;
        view.setBackgroundColor(LETTERBOX);
        view.evaluateJavascript(DARKEN_JS, null);
        view.evaluateJavascript(CENTER_JS, null);
        // 图片/字体晚到会让皮肤高度变化，再量一次；绝对值覆盖，不会叠加。
        view.postDelayed(() -> {
            if (view.getUrl() != null && isAida(view.getUrl())) {
                view.evaluateJavascript(CENTER_JS, null);
            }
        }, 500);
    }

    /** 尺寸变化（旋转、分屏）后按新视口重新居中。 */
    static void recenter(WebView view) {
        if (view == null || !isAida(view.getUrl())) return;
        view.evaluateJavascript(CENTER_JS, null);
    }

    private AidaSkin() {}
}
