package win.cheems.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import win.cheems.core.net.*;

/** Bounded TCP relay. Stop closes both accepted and outbound connections. */
public final class ProxyCore {
  public interface Listener {
    void onUpdate(String message);
  }

  private final Listener listener;
  private final String localSecret;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final AtomicLong up = new AtomicLong(), down = new AtomicLong();
  private final AtomicInteger active = new AtomicInteger();
  private final Semaphore permits = new Semaphore(24);
  private volatile boolean running;
  private ServerSocket server;
  private ExecutorService pool;
  private Thread acceptThread;

  public ProxyCore(Listener l, String secret) {
    if (secret == null || secret.length() < 24)
      throw new IllegalArgumentException("Local proxy authentication secret required");
    listener = l;
    localSecret = secret;
  }

  // The loopback interface is accessible to other Android apps. Every CONNECT
  // request must prove knowledge of this service instance's random secret.
  boolean authorized(String headers) {
    String expected = Base64.getEncoder()
        .encodeToString(("cheems:" + localSecret).getBytes(StandardCharsets.UTF_8));
    for (String line : headers.split("\r\n")) {
      if (!line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) continue;
      String value = line.substring(20).trim();
      if (!value.regionMatches(true, 0, "Basic ", 0, 6)) continue;
      String offered = value.substring(6).trim();
      if (MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          offered.getBytes(StandardCharsets.US_ASCII))) return true;
    }
    return false;
  }

  public boolean running() {
    return running;
  }

  public int active() {
    return active.get();
  }

  public long uploaded() {
    return up.get();
  }

  public long downloaded() {
    return down.get();
  }

  public synchronized void start(Node node) throws IOException {
    if (running) return;
    if (node == null || !node.supported()) throw new IOException("请选择受支持的节点");
    ServerSocket bound = new ServerSocket();
    bound.setReuseAddress(true);
    bound.bind(new InetSocketAddress("127.0.0.1", 8899));
    server = bound;
    pool = Executors.newFixedThreadPool(48);
    ExecutorService workers = pool;
    running = true;
    acceptThread =
        new Thread(
            () -> {
              while (!bound.isClosed()) {
                try {
                  Socket c = bound.accept();
                  if (!permits.tryAcquire()) {
                    c.close();
                    continue;
                  }
                  sockets.add(c);
                  try {
                    workers.execute(() -> handle(c, node, workers, bound));
                  } catch (RejectedExecutionException e) {
                    sockets.remove(c);
                    c.close();
                    permits.release();
                  }
                } catch (IOException e) {
                  if (!bound.isClosed()) listener.onUpdate("监听异常，服务已停止");
                  break;
                }
              }
            },
            "cheems-accept");
    acceptThread.start();
    listener.onUpdate("已启动本地代理 · " + node.type + " · 127.0.0.1:8899");
  }

  private void handle(Socket client, Node node, ExecutorService workers, ServerSocket generation) {
    active.incrementAndGet();
    Dialer.Tunnel tunnel = null;
    try (Socket c = client) {
      c.setSoTimeout(15000);
      String headers = Dialer.header(c.getInputStream());
      if (!authorized(headers)) {
        reply(c, "407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Cheems Core\"");
        return;
      }
      String first = headers.split("\r\n")[0];
      String[] parts = first.split(" ");
      if (parts.length != 3 || !parts[0].equals("CONNECT")) {
        reply(c, "405 Method Not Allowed");
        return;
      }
      String authority = parts[1];
      int colon = authority.lastIndexOf(':');
      if (colon < 1) {
        reply(c, "400 Bad Request");
        return;
      }
      String host = authority.substring(0, colon);
      if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
      int port = Integer.parseInt(authority.substring(colon + 1));
      tunnel =
          Dialer.connect(
              node,
              host,
              port,
              s -> {
                sockets.add(s);
                if (generation.isClosed())
                  try {
                    s.close();
                  } catch (IOException ignored) {
                  }
              });
      if (generation.isClosed()) return;
      Dialer.Tunnel remote = tunnel;
      c.setSoTimeout(120000);
      reply(c, "200 Connection Established");
      Future<?> sending = workers.submit(() -> pipe(c, remote, c, true));
      pipe(c, remote, c, false);
      try {
        sending.get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        sending.cancel(true);
      }
    } catch (Exception e) {
      if (!generation.isClosed()) {
        try {
          reply(client, "502 Bad Gateway");
        } catch (Exception ignored) {
        }
        listener.onUpdate("连接未完成（" + e.getClass().getSimpleName() + "），未回退直连");
      }
    } finally {
      if (tunnel != null)
        try {
          tunnel.close();
        } catch (Exception ignored) {
        }
      sockets.remove(client);
      sockets.removeIf(Socket::isClosed);
      active.decrementAndGet();
      permits.release();
    }
  }

  private void pipe(Socket source, Dialer.Tunnel remote, Socket client, boolean upload) {
    try {
      InputStream in = upload ? source.getInputStream() : remote.in;
      OutputStream out = upload ? remote.out : client.getOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
        out.flush();
        (upload ? up : down).addAndGet(n);
      }
    } catch (IOException ignored) {
    } finally {
      try {
        (upload ? remote.socket : client).shutdownOutput();
      } catch (IOException ignored) {
      }
    }
  }

  private static void reply(Socket c, String status) throws IOException {
    c.getOutputStream()
        .write(("HTTP/1.1 " + status + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    c.getOutputStream().flush();
  }

  public synchronized void stop() {
    if (!running) return;
    running = false;
    try {
      server.close();
    } catch (Exception ignored) {
    }
    for (Socket s : sockets)
      try {
        s.close();
      } catch (Exception ignored) {
      }
    pool.shutdownNow();
    listener.onUpdate("代理已停止，连接已关闭");
  }
}
