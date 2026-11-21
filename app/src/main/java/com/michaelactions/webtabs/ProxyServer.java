package com.michaelactions.webtabs;

import android.util.Base64;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 *内置 HTTP代理。
 *
 *老板的内网设备（爱快路由、AIDA64等）都在各自店里，平板在外面要访问必须走对应上游 HTTP代理：
 *10.0.1.x -> wooo.men:444 账号密码本地保存 （南岸电信）
 *10.0.2.x -> cu.wooo.men:444 账号密码本地保存 （南岸联通）
 *172.30.6.x -> rc.wooo.men:444 账号密码本地保存 （荣昌联通）
 *
 * Android WebView没法按站点设代理（ProxyController是全局的，而且不支持代理认证），
 *所以在 App里起一个本地 HTTP代理：WebView全部走127.0.0.1:port，
 *由这里按目标 IP段决定用哪个上游代理并补上 Proxy-Authorization；不在规则里的地址直接直连。
 */
public class ProxyServer {

public static final String[][] RULES = {
{"10.0.1.", "wooo.men", "444", "ZXRvdGFrZW8=", "bmcwNTc3MjA="},
{"10.0.2.", "cu.wooo.men", "444", "dGFu", "bmcwNTc3MjA="},
{"172.30.6.", "rc.wooo.men", "444", "ZXRvdGFrZW8=", "bmcwNTc3MjA="}
};

private static String d(String b64){ try { return new String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), "UTF-8"); } catch (Exception e) { return ""; } }
private static ServerSocket server;
static final String SP = String.valueOf((char)32);
private static int port =0;
private static volatile boolean running = false;

/**返回本地代理端口，0表示启动失败 */
public static synchronized int ensureStarted() {
if (running && server != null && !server.isClosed()) return port;
try {
server = new ServerSocket(0,50, java.net.InetAddress.getByName("127.0.0.1"));
port = server.getLocalPort();
running = true;
Thread t = new Thread(new Runnable() {
public void run() { acceptLoop(); }
});
t.setDaemon(true);
t.setName("webtabs-proxy");
t.start();
} catch (Exception e) {
running = false;
port =0;
}
return port;
}

public static int port() { return port; }

/**命中哪个上游：返回 {前缀,代理主机,端口,用户名,密码}，没有返回 null */
public static String[] ruleFor(String host) {
if (host == null) return null;
for (String[] r : RULES) {
if (host.startsWith(r[0])) return r;
}
return null;
}

private static void acceptLoop() {
while (running) {
Socket c = null;
try {
c = server.accept();
final Socket client = c;
Thread t = new Thread(new Runnable() {
public void run() { handle(client); }
});
t.setDaemon(true);
t.start();
} catch (Exception e) {
if (!running) return;
}
}
}

private static void handle(Socket client) {
Socket upstream = null;
try {
client.setSoTimeout(30000);
client.setTcpNoDelay(true);
String head = readHead(client.getInputStream());
if (head == null) return;
String[] lines = head.split("\r\n");
if (lines.length <1) return;
String[] rl = lines[0].split(" ");
if (rl.length <2) return;
String method = rl[0];
String target = rl[1];
boolean connect = "CONNECT".equalsIgnoreCase(method);

String host;
int tport;
String path = "/";

if (connect) {
host = hostPart(target);
tport = portPart(target,443);
} else if (target.toLowerCase().startsWith("http://")) {
String rest = target.substring(7);
int slash = rest.indexOf('/');
String hp = slash >=0 ? rest.substring(0, slash) : rest;
path = slash >=0 ? rest.substring(slash) : "/";
host = hostPart(hp);
tport = portPart(hp,80);
} else {
String h = headerValue(lines, "Host");
if (h == null) return;
host = hostPart(h);
tport = portPart(h,80);
path = target;
}
if (host == null || host.isEmpty()) return;

String[] rule = ruleFor(host);
upstream = new Socket();
upstream.setTcpNoDelay(true);

if (rule == null) {
upstream.connect(new InetSocketAddress(host, tport),10000);
OutputStream uo = upstream.getOutputStream();
if (connect) {
OutputStream co = client.getOutputStream();
co.write(("HTTP/1.1" + SP + "200" + SP + "Connection Established\r\n\r\n").getBytes("ISO-8859-1"));
co.flush();
} else {
uo.write(originRequest(method, host, tport, path, lines).getBytes("ISO-8859-1"));
uo.flush();
}
} else {
upstream.connect(new InetSocketAddress(rule[1], Integer.parseInt(rule[2])),12000);
String auth = "Proxy-Authorization: Basic " + Base64.encodeToString(
(d(rule[3])+":"+d(rule[4])).getBytes("UTF-8"), Base64.NO_WRAP) + "\r\n";
OutputStream uo = upstream.getOutputStream();
if (connect) {
uo.write(("CONNECT " + host + ":" + tport + " HTTP/1.1\r\nHost: " + host + ":" + tport + "\r\n"
+ auth + "\r\n").getBytes("ISO-8859-1"));
uo.flush();
String rh = readHead(upstream.getInputStream());
if (rh == null) return;
OutputStream co = client.getOutputStream();
co.write(rh.getBytes("ISO-8859-1"));
co.flush();
if (!rh.startsWith("HTTP/1.1" + SP + "200") && !rh.startsWith("HTTP/1.0" + SP + "200")) return;
} else {
String hp = tport ==80 ? host : host + ":" + tport;
StringBuilder sb = new StringBuilder();
sb.append(method).append(' ').append("http://").append(hp).append(path).append(" HTTP/1.1\r\n");
for (int i =1; i < lines.length; i++) {
String l = lines[i];
if (l.isEmpty()) continue;
String low = l.toLowerCase();
if (low.startsWith("proxy-")) continue;
if (low.startsWith("connection:")) continue;
sb.append(l).append("\r\n");
}
sb.append(auth);
sb.append("Connection: close\r\n\r\n");
uo.write(sb.toString().getBytes("ISO-8859-1"));
uo.flush();
}
}

pump(client, upstream);
upstream = null;
} catch (Exception e) {
//失败就断开，WebView会显示自己的错误页
} finally {
try { if (upstream != null) upstream.close(); } catch (Exception ignored) { }
try { client.close(); } catch (Exception ignored) { }
}
}

private static String originRequest(String method, String host, int tport, String path, String[] lines) {
StringBuilder sb = new StringBuilder();
sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
boolean hasHost = false;
for (int i =1; i < lines.length; i++) {
String l = lines[i];
if (l.isEmpty()) continue;
String low = l.toLowerCase();
if (low.startsWith("proxy-")) continue;
if (low.startsWith("connection:")) continue;
if (low.startsWith("host:")) hasHost = true;
sb.append(l).append("\r\n");
}
if (!hasHost) sb.append("Host: ").append(tport ==80 ? host : host + ":" + tport).append("\r\n");
sb.append("Connection: close\r\n\r\n");
return sb.toString();
}

/**逐字节读到头部结束（\r\n\r\n），不预读，避免把 TLS数据吞掉 */
private static String readHead(InputStream in) {
try {
StringBuilder sb = new StringBuilder();
int state =0;
int b;
while ((b = in.read()) >=0) {
sb.append((char) (b &0xFF));
if (b == '\r' && (state ==0 || state ==2)) state++;
else if (b == '\n' && (state ==1 || state ==3)) state++;
else state =0;
if (state ==4) return sb.toString();
if (sb.length() >65536) return null;
}
} catch (Exception e) {
return null;
}
return null;
}

private static String hostPart(String hp) {
if (hp == null) return null;
String h = hp.trim();
if (h.startsWith("[")) {
int i = h.indexOf(']');
return i >0 ? h.substring(1, i) : h;
}
int c = h.indexOf(':');
return c >=0 ? h.substring(0, c) : h;
}

private static int portPart(String hp, int def) {
if (hp == null) return def;
String h = hp.trim();
int c = h.lastIndexOf(':');
if (c <0) return def;
if (h.startsWith("[") && h.indexOf(']') > c) return def;
try { return Integer.parseInt(h.substring(c +1)); } catch (Exception e) { return def; }
}

private static String headerValue(String[] lines, String name) {
String p = name.toLowerCase() + ":";
for (int i =1; i < lines.length; i++) {
String l = lines[i];
if (l.toLowerCase().startsWith(p)) return l.substring(p.length()).trim();
}
return null;
}

private static void pump(final Socket a, final Socket b) {
Thread t = new Thread(new Runnable() {
public void run() { copy(a, b); }
});
t.setDaemon(true);
t.start();
copy(b, a);
try { a.close(); } catch (Exception ignored) { }
try { b.close(); } catch (Exception ignored) { }
}

private static void copy(Socket from, Socket to) {
try {
InputStream in = from.getInputStream();
OutputStream out = to.getOutputStream();
byte[] buf = new byte[16384];
int n;
while ((n = in.read(buf)) >0) {
out.write(buf,0, n);
out.flush();
}
} catch (Exception ignored) {
} finally {
try { to.shutdownOutput(); } catch (Exception ignored) { }
}
}
}
