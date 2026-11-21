package com.michaelactions.webtabs;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Credentials are injected from a CI secret into the personal APK, never committed. */
final class ProxyCredentials {
    static int start(Context context) throws Exception {
        JSONObject values = new JSONObject();
        try (InputStream in = context.getAssets().open("proxy-credentials.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] b = new byte[1024]; int n;
            while ((n = in.read(b)) != -1) bytes.write(b, 0, n);
            values = new JSONObject(bytes.toString("UTF-8"));
        } catch (java.io.FileNotFoundException ignored) { }
        ProxyServer.Upstream[] routes = new ProxyServer.Upstream[3];
        for (int i = 0; i < routes.length; i++) {
            JSONObject entry = values.optJSONObject(RouteTable.PREFIXES[i]);
            if (entry == null) continue;
            String user = entry.optString("username");
            String pass = entry.optString("password");
            if (user.isEmpty() || pass.isEmpty()) continue;
            String auth = "Basic " + Base64.encodeToString((user + ":" + pass)
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            routes[i] = new ProxyServer.Upstream(RouteTable.PROXIES[i], 444, auth);
        }
        return ProxyServer.start(routes);
    }
    private ProxyCredentials() {}
}
