package com.michaelactions.webtabs;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

/** Loopback-only, per-destination HTTP relay. No private-network direct fallback. */
public final class ProxyServer implements AutoCloseable {
    public static final class Upstream {
        final String host, authorization;
        final int port;
        public Upstream(String host, int port, String authorization) {
            this.host = host; this.port = port; this.authorization = authorization;
        }
    }
    public interface Resolver { Upstream resolve(String host) throws IOException; }
    private static volatile ProxyServer instance;
    private final ServerSocket listener;
    private final Resolver resolver;
    private volatile boolean running = true;
    private final ExecutorService workers = new ThreadPoolExecutor(0, 32, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "webtabs-http"); t.setDaemon(true); return t;
            });

    public ProxyServer(Resolver resolver) throws IOException {
        this.resolver = resolver;
        listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::accept, "webtabs-listener");
        t.setDaemon(true); t.start();
    }
    public static synchronized int start(Upstream[] upstreams) throws IOException {
        if (instance == null || !instance.running) {
            instance = new ProxyServer(host -> {
                int i = RouteTable.index(host);
                if (i < 0) return null;
                if (i >= upstreams.length || upstreams[i] == null
                        || upstreams[i].authorization.isEmpty()) {
                    throw new IOException("Proxy credentials unavailable");
                }
                return upstreams[i];
            });
        }
        return instance.getPort();
    }
    public static int port() { return instance == null ? 0 : instance.getPort(); }

    /** 测试钩子：丢掉单例，便于每个 instrumentation 用例安装自己的 mock 上游。 */
    static synchronized void resetForTests() {
        if (instance != null) {
            try { instance.close(); } catch (IOException ignored) { }
            instance = null;
        }
    }
    public int getPort() { return listener.getLocalPort(); }
    private void accept() {
        while (running) {
            Socket c = null;
            try {
                c = listener.accept();
                final Socket client = c;
                workers.execute(() -> handle(client));
            } catch (Exception e) {
                closeSocket(c);
            }
        }
    }
    private void handle(Socket client) {
        Socket remote = null;
        boolean responseStarted = false;
        try {
            client.setSoTimeout(15000);
            client.setTcpNoDelay(true);
            String[] lines = readHead(client.getInputStream()).split("\r\n");
            String[] req = lines[0].split(" +", 3);
            if (req.length != 3) throw new IOException("Invalid request");
            String method = req[0];
            boolean connect = method.equals("CONNECT");
            URI uri = new URI(connect ? "http://" + req[1] :
                    (req[1].startsWith("/") ? "http://" + header(lines, "Host") + req[1] : req[1]));
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) throw new IOException("Invalid host");
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length()-1);
            int port = uri.getPort() >= 0 ? uri.getPort() : (connect ? 443 : 80);
            if (!connect && !"http".equalsIgnoreCase(uri.getScheme())) throw new IOException("Use CONNECT for TLS");
            Upstream proxy = resolver.resolve(host);
            remote = new Socket();
            remote.setTcpNoDelay(true);
            remote.connect(new InetSocketAddress(proxy == null ? host : proxy.host,
                    proxy == null ? port : proxy.port), 12000);
            remote.setSoTimeout(20000);
            String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            if (connect) {
                if (proxy == null) {
                    write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
                } else {
                    write(remote, "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority
                            + "\r\nProxy-Authorization: " + proxy.authorization + "\r\n\r\n");
                    String reply = readHead(remote.getInputStream());
                    write(client, reply);
                    responseStarted = true;
                    String[] status = reply.split(" +", 3);
                    if (status.length < 2 || !status[1].equals("200")) return;
                }
            } else {
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                boolean upgrade = !header(lines, "Upgrade").isEmpty();
                String target = proxy == null ? path : "http://" + authority + path;
                StringBuilder out = new StringBuilder(method + " " + target + " HTTP/1.1\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String lower = lines[i].toLowerCase(Locale.ROOT);
                    if (lower.startsWith("proxy-") || lower.startsWith("host:")) continue;
                    if (!upgrade && lower.startsWith("connection:")) continue;
                    out.append(lines[i]).append("\r\n");
                }
                out.append("Host: ").append(authority).append("\r\n");
                if (proxy != null) out.append("Proxy-Authorization: ").append(proxy.authorization).append("\r\n");
                if (!upgrade) out.append("Connection: close\r\n");
                out.append("\r\n");
                write(remote, out.toString());
            }
            responseStarted = true;
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            final Socket target = remote;
            Thread upload = new Thread(() -> copy(client, target), "webtabs-upload");
            upload.setDaemon(true); upload.start();
            copy(remote, client);
        } catch (Exception e) {
            if (!responseStarted) {
                try { write(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"); }
                catch (IOException ignored) {}
            }
        } finally {
            closeSocket(remote); closeSocket(client);
        }
    }
    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        } catch (IOException ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (IOException ignored) {}
        }
    }
    private static void write(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
    }
    private static String header(String[] lines, String key) {
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(key)) return line.substring(colon+1).trim();
        }
        return "";
    }
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int tail = 0;
        while (out.size() < 65536) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            out.write(b); tail = (tail << 8) | b;
            if (tail == 0x0d0a0d0a) return out.toString("ISO-8859-1");
        }
        throw new IOException("Header too large");
    }
    private static void closeSocket(Socket s) { if (s != null) try { s.close(); } catch (IOException ignored) {} }
    @Override public void close() throws IOException {
        running = false; listener.close(); workers.shutdownNow();
    }
}
