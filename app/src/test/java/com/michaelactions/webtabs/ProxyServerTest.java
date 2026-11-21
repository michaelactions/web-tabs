package com.michaelactions.webtabs;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class ProxyServerTest {
    static String head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int tail = 0;
        while (b.size() < 65536) { int c = in.read(); if (c < 0) break;
            b.write(c); tail = (tail << 8) | c; if (tail == 0x0d0a0d0a) break; }
        return b.toString("ISO-8859-1");
    }
    static void send(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1)); s.getOutputStream().flush();
    }
    interface Handler { void serve(Socket socket) throws Exception; }
    static class Fake implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Fake(Handler handler) throws Exception {
            Thread t = new Thread(() -> { try (Socket c = server.accept()) {
                c.setSoTimeout(5000); handler.serve(c); done.complete(null);
            } catch (Throwable e) { done.completeExceptionally(e); } });
            t.setDaemon(true); t.start();
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); done.get(8, TimeUnit.SECONDS); }
    }
    static Socket client(ProxyServer p) throws Exception {
        Socket c = new Socket("127.0.0.1", p.getPort()); c.setSoTimeout(5000); return c;
    }
    @Test public void strictSubnetRouting() {
        for (int i = 0; i < 256; i++) {
            assertEquals(0, RouteTable.index("10.0.1." + i));
            assertEquals(1, RouteTable.index("10.0.2." + i));
            assertEquals(2, RouteTable.index("172.30.6." + i));
        }
        for (String s : new String[]{"10.0.1.7.evil.test", "10.0.1.999", "10.0.1.-1", "10.0.1.01", "10.0.10.1", "172.30.60.1", "example.com", "", "127.0.0.1"})
            assertEquals(s, -1, RouteTable.index(s));
        assertEquals(-1, RouteTable.index(null));
    }
    @Test public void upstreamGetWithQueryAndCredentials() throws Exception {
        try (Fake upstream = new Fake(s -> {
            String h = head(s.getInputStream());
            assertTrue(h.startsWith("GET http://10.0.1.10:80/?a=1 HTTP/1.1\r\n"));
            assertTrue(h.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"));
            assertFalse(h.contains("evil-token"));
            send(s, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
        }); ProxyServer relay = new ProxyServer(host -> new ProxyServer.Upstream("127.0.0.1", upstream.port(), "Basic dXNlcjpwYXNz"));
             Socket c = client(relay)) {
            send(c, "GET http://10.0.1.10?a=1 HTTP/1.1\r\nHost: 10.0.1.10\r\nProxy-Authorization: evil-token\r\n\r\n");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 200"));
            assertEquals("OK", new String(c.getInputStream().readNBytes(2), StandardCharsets.UTF_8));
        }
    }
    @Test public void postBodySurvives() throws Exception {
        try (Fake upstream = new Fake(s -> {
            String h = head(s.getInputStream());
            assertTrue(h.startsWith("POST http://10.0.2.1:80/login HTTP/1.1"));
            assertEquals("hello", new String(s.getInputStream().readNBytes(5), StandardCharsets.UTF_8));
            send(s, "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");
        }); ProxyServer relay = new ProxyServer(host -> new ProxyServer.Upstream("127.0.0.1", upstream.port(), "Basic test")); Socket c = client(relay)) {
            send(c, "POST http://10.0.2.1/login HTTP/1.1\r\nHost: 10.0.2.1\r\nContent-Length: 5\r\n\r\nhello");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 204"));
        }
    }
    @Test public void upstreamConnectTunnel() throws Exception {
        try (Fake upstream = new Fake(s -> {
            String h = head(s.getInputStream()); assertTrue(h.startsWith("CONNECT 172.30.6.1:443 HTTP/1.1"));
            assertTrue(h.contains("Proxy-Authorization: Basic test"));
            send(s, "HTTP/1.1 200 Connection Established\r\n\r\n");
            assertEquals("PING", new String(s.getInputStream().readNBytes(4), StandardCharsets.UTF_8)); send(s, "PONG");
        }); ProxyServer relay = new ProxyServer(host -> new ProxyServer.Upstream("127.0.0.1", upstream.port(), "Basic test")); Socket c = client(relay)) {
            send(c, "CONNECT 172.30.6.1:443 HTTP/1.1\r\nHost: 172.30.6.1:443\r\n\r\n");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 200")); send(c, "PING");
            assertEquals("PONG", new String(c.getInputStream().readNBytes(4), StandardCharsets.UTF_8));
        }
    }
    @Test public void websocketUpgradePreserved() throws Exception {
        try (Fake upstream = new Fake(s -> {
            String h = head(s.getInputStream()); assertTrue(h.contains("Connection: Upgrade\r\n"));
            assertTrue(h.contains("Upgrade: websocket\r\n"));
            send(s, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n");
            assertEquals(42, s.getInputStream().read()); s.getOutputStream().write(43); s.getOutputStream().flush();
        }); ProxyServer relay = new ProxyServer(host -> new ProxyServer.Upstream("127.0.0.1", upstream.port(), "Basic test")); Socket c = client(relay)) {
            send(c, "GET http://10.0.1.10/ws HTTP/1.1\r\nHost: 10.0.1.10\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 101"));
            c.getOutputStream().write(42); c.getOutputStream().flush(); assertEquals(43, c.getInputStream().read());
        }
    }
    @Test public void directDoesNotLeakProxyAuthorization() throws Exception {
        try (Fake origin = new Fake(s -> {
            String h = head(s.getInputStream()); assertTrue(h.startsWith("GET /path HTTP/1.1\r\n"));
            assertFalse(h.toLowerCase().contains("proxy-authorization"));
            send(s, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        }); ProxyServer relay = new ProxyServer(host -> null); Socket c = client(relay)) {
            send(c, "GET http://127.0.0.1:" + origin.port() + "/path HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Authorization: secret\r\n\r\n");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 200"));
        }
    }
    @Test public void missingCredentialsFailsClosed() throws Exception {
        try (ProxyServer relay = new ProxyServer(host -> { throw new IOException("unavailable"); }); Socket c = client(relay)) {
            send(c, "GET http://10.0.1.1/ HTTP/1.1\r\nHost: 10.0.1.1\r\n\r\n");
            assertTrue(head(c.getInputStream()).startsWith("HTTP/1.1 502"));
        }
    }
}
