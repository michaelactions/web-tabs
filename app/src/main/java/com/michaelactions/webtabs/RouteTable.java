package com.michaelactions.webtabs;

/** Match literal IPv4 /24 networks only. Never resolve a hostname to decide routing. */
public final class RouteTable {
    public static final String[] PREFIXES = {"10.0.1.", "10.0.2.", "172.30.6."};
    public static final String[] PROXIES = {"wooo.men", "cu.wooo.men", "rc.wooo.men"};
    public static int index(String host) {
        if (host == null) return -1;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return -1;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) return -1;
            if (Integer.parseInt(part) > 255) return -1;
        }
        for (int i = 0; i < PREFIXES.length; i++) {
            if (host.startsWith(PREFIXES[i])) return i;
        }
        return -1;
    }
    private RouteTable() {}
}
