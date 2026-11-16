package win.cheems.core.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Exercises the complete local HTTP CONNECT path, not merely native startup:
 * localhost HTTP proxy -> selected outbound dialer -> remote HTTP response.
 * This never silently connects directly to the test website.
 */
public final class LocalProxyProbe {
  private static final int PROXY_PORT = 8899;
  private static final int IO_TIMEOUT_MS = 15000;

  public static final class Result {
    public final long elapsedMs;
    public final String responseStatus;

    private Result(long elapsedMs, String responseStatus) {
      this.elapsedMs = elapsedMs;
      this.responseStatus = responseStatus;
    }
  }

  private LocalProxyProbe() {}

  public static Result check(String temporaryPassword) throws IOException {
    return check(temporaryPassword, PROXY_PORT, "example.com", 80);
  }

  // Package-private for offline tests using a loopback mock proxy.
  static Result check(String temporaryPassword, int localPort, String host, int port)
      throws IOException {
    if (temporaryPassword == null || temporaryPassword.isEmpty())
      throw new IOException("本地 HTTP 代理未启动，无法获取临时认证信息");
    if (localPort < 1 || localPort > 65535 || port < 1 || port > 65535)
      throw new IOException("无效的诊断端口");
    if (host == null || host.isEmpty() || host.contains("\r") || host.contains("\n"))
      throw new IOException("无效的测试目标");

    long start = System.nanoTime();
    try (Socket socket = new Socket()) {
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", localPort), 3000);
      } catch (IOException e) {
        throw new IOException("阶段 1：本地 HTTP 代理连接失败", e);
      }
      socket.setSoTimeout(IO_TIMEOUT_MS);
      InputStream input = socket.getInputStream();
      OutputStream output = socket.getOutputStream();
      String authority = host + ":" + port;
      String credentials = Base64.getEncoder().encodeToString(
          ("cheems:" + temporaryPassword).getBytes(StandardCharsets.UTF_8));
      String connectRequest =
          "CONNECT " + authority + " HTTP/1.1\r\n"
              + "Host: " + authority + "\r\n"
              + "Proxy-Authorization: Basic " + credentials + "\r\n"
              + "Connection: close\r\n\r\n";
      try {
        output.write(connectRequest.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String line = readStatus(input);
        int code = statusCode(line);
        if (code == 407) throw new IOException("阶段 2：本地代理认证失败（HTTP 407）");
        if (code == 502) throw new IOException("阶段 2：节点无法建立上游 TCP 连接（HTTP 502）");
        if (code != 200) throw new IOException("阶段 2：CONNECT 被拒绝（HTTP " + code + "）");
      } catch (IOException e) {
        if (e.getMessage() != null && e.getMessage().startsWith("阶段 2：")) throw e;
        throw new IOException("阶段 2：CONNECT 握手失败", e);
      }

      try {
        output.write(("GET / HTTP/1.1\r\nHost: " + host
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String response = readStatus(input);
        int code = statusCode(response);
        // A valid upstream HTTP response, including 4xx or 5xx, proves
        // bidirectional TCP forwarding. This probe is a transport check,
        // not a website availability / application-success assertion.
        if (code < 100 || code > 599)
          throw new IOException("阶段 3：上游返回无效 HTTP 状态 " + code);
        return new Result((System.nanoTime() - start) / 1000000, response);
      } catch (IOException e) {
        if (e.getMessage() != null && e.getMessage().startsWith("阶段 3：")) throw e;
        throw new IOException("阶段 3：远程 HTTP 收发失败", e);
      }
    }
  }

  private static int statusCode(String line) throws IOException {
    if (line == null || !line.matches("HTTP/1\\.[01] [0-9]{3}(?: .*|)$"))
      throw new IOException("代理或远端返回非标准 HTTP 状态行");
    return Integer.parseInt(line.substring(9, 12));
  }

  private static String readStatus(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int state = 0;
    while (buffer.size() < 16384) {
      int b = input.read();
      if (b < 0) throw new EOFException("HTTP 响应在头部结束前关闭");
      buffer.write(b);
      state = state == 0 && b == '\r' ? 1
          : state == 1 && b == '\n' ? 2
          : state == 2 && b == '\r' ? 3
          : state == 3 && b == '\n' ? 4 : 0;
      if (state == 4) {
        String headers = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        return headers.split("\r\n", 2)[0];
      }
    }
    throw new IOException("HTTP 头部过长");
  }
}
