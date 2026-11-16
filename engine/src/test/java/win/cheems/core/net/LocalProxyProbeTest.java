package win.cheems.core.net;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** End-to-end HTTP CONNECT diagnostics using a local fake proxy, no provider keys. */
public final class LocalProxyProbeTest {
  private static final String PASS = "synthetic-local-proxy-password-for-tests";

  @Test public void forwardsThroughAuthenticatedProxyAndReceivesHttp() throws Exception {
    try (ServerSocket server = loopback()) {
      CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
        try (Socket s = server.accept()) {
          s.setSoTimeout(3000);
          InputStream in = s.getInputStream();
          OutputStream out = s.getOutputStream();
          String connect = headers(in);
          String expected = Base64.getEncoder().encodeToString(
              ("cheems:" + PASS).getBytes(StandardCharsets.UTF_8));
          assertTrue(connect.startsWith("CONNECT example.com:80 HTTP/1.1\r\n"));
          assertTrue(connect.contains("\r\nProxy-Authorization: Basic " + expected + "\r\n"));
          out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();

          String get = headers(in);
          assertTrue(get.startsWith("GET / HTTP/1.1\r\n"));
          assertTrue(get.contains("\r\nHost: example.com\r\n"));
          out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
              .getBytes(StandardCharsets.US_ASCII));
          out.flush();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      LocalProxyProbe.Result result =
          LocalProxyProbe.check(PASS, server.getLocalPort(), "example.com", 80);
      assertEquals("HTTP/1.1 200 OK", result.responseStatus);
      assertTrue(result.elapsedMs >= 0);
      peer.get(5, TimeUnit.SECONDS);
    }
  }

  @Test public void remoteHttp403StillProvesBidirectionalTransport() throws Exception {
    try (ServerSocket server = loopback()) {
      CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(3000);
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          headers(in); // Authenticated HTTP CONNECT.
          out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
              .getBytes(StandardCharsets.US_ASCII));
          out.flush();
          headers(in); // GET sent through the established tunnel.
          out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
              .getBytes(StandardCharsets.US_ASCII));
          out.flush();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      LocalProxyProbe.Result result =
          LocalProxyProbe.check(PASS, server.getLocalPort(), "example.com", 80);
      assertEquals("HTTP/1.1 403 Forbidden", result.responseStatus);
      peer.get(5, TimeUnit.SECONDS);
    }
  }

  @Test public void distinguishesRejectedProxyPassword() throws Exception {
    assertReportedStage("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n",
        "阶段 2", "407");
  }

  @Test public void distinguishesUpstreamTcpFailure() throws Exception {
    assertReportedStage("HTTP/1.1 502 Bad Gateway\r\n\r\n",
        "阶段 2", "502");
  }

  @Test public void missingTemporaryProxyPasswordNeverConnects() throws Exception {
    try {
      LocalProxyProbe.check("", 8899, "example.com", 80);
      fail("expected missing local credential rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("认证信息"));
    }
  }

  private static void assertReportedStage(String reply, String stage, String status) throws Exception {
    try (ServerSocket server = loopback()) {
      CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
        try (Socket s = server.accept()) {
          s.setSoTimeout(3000);
          headers(s.getInputStream());
          s.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
          s.getOutputStream().flush();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      try {
        LocalProxyProbe.check(PASS, server.getLocalPort(), "example.com", 80);
        fail("expected diagnostic failure");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(stage));
        assertTrue(e.getMessage(), e.getMessage().contains(status));
        assertFalse(e.getMessage(), e.getMessage().contains(PASS));
      }
      peer.get(5, TimeUnit.SECONDS);
    }
  }

  private static ServerSocket loopback() throws IOException {
    return new ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"));
  }

  private static String headers(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int state = 0;
    while (b.size() < 8192) {
      int x = in.read();
      if (x < 0) throw new IOException("unexpected EOF");
      b.write(x);
      state = state == 0 && x == '\r' ? 1
          : state == 1 && x == '\n' ? 2
          : state == 2 && x == '\r' ? 3
          : state == 3 && x == '\n' ? 4 : 0;
      if (state == 4)
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }
    throw new IOException("oversized HTTP header");
  }
}
