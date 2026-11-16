package win.cheems.core.net;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.*;

public final class Dialer {
  public static final class Tunnel implements Closeable {
    public final Socket socket;
    public final InputStream in;
    public final OutputStream out;

    Tunnel(Socket s, InputStream i, OutputStream o) {
      socket = s;
      in = i;
      out = o;
    }

    public void close() throws IOException {
      socket.close();
    }
  }

  // Applies only to the proxy-node socket; subscription HTTPS uses platform trust.
  private static SSLSocketFactory nodeSocketFactory(Node node) throws Exception {
    if (!Boolean.parseBoolean(Node.value(node.source, "skip-cert-verify", "false")))
      return (SSLSocketFactory) SSLSocketFactory.getDefault();
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[]{new X509TrustManager() {
      public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
      public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
      public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
    }}, new java.security.SecureRandom());
    return context.getSocketFactory();
  }

  public static Tunnel connect(
      Node node, String host, int port, java.util.function.Consumer<Socket> track)
      throws Exception {
    if (!node.supported()) throw new IOException(node.unsupported);
    Socket socket = new Socket();
    track.accept(socket);
    try {
      boolean internalNative = "hysteria2".equals(node.type) || "anytls".equals(node.type) || "vless".equals(node.type) || "vmess".equals(node.type) || "ss2022".equals(node.type) || "tuic".equals(node.type)
          || ("socks5".equals(node.type) && Hysteria2Native.isRunning());
      socket.connect(
          internalNative
              ? new InetSocketAddress("127.0.0.1", Hysteria2Native.LOCAL_SOCKS_PORT)
              : new InetSocketAddress(node.host, node.port),
          10000);
      socket.setSoTimeout(15000);
      socket.setTcpNoDelay(true);
      if (!internalNative && node.tls()) {
        SSLSocket tls =
            (SSLSocket)
                nodeSocketFactory(node)
                    .createSocket(socket, node.sni, node.port, true);
        SSLParameters params = tls.getSSLParameters();
        params.setEndpointIdentificationAlgorithm(Boolean.parseBoolean(Node.value(node.source, "skip-cert-verify", "false")) ? null : "HTTPS");
        tls.setSSLParameters(params);
        tls.startHandshake();
        socket = tls;
      }
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      switch (node.type) {
        case "ss":
          out = Aead.output(node, out);
          out.write(address(host, port));
          out.flush();
          in = Aead.input(node, in);
          break;
        case "trojan":
          byte[] digest =
              MessageDigest.getInstance("SHA-224")
                  .digest(node.password.getBytes(StandardCharsets.UTF_8));
          StringBuilder hex = new StringBuilder();
          for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
          out.write((hex + "\r\n").getBytes(StandardCharsets.US_ASCII));
          out.write(1);
          out.write(address(host, port));
          out.write(new byte[] {13, 10});
          out.flush();
          break;
        case "hysteria2":
        case "anytls":
        case "vless":
        case "vmess":
        case "ss2022":
        case "tuic":
        case "socks5":
          boolean internalNativeProxy = internalNative;
          boolean auth = internalNativeProxy || !node.username.isEmpty();
          out.write(auth ? new byte[] {5, 1, 2} : new byte[] {5, 1, 0});
          out.flush();
          byte[] method = Aead.readFully(in, 2);
          if (method[0] != 5 || method[1] != (auth ? 2 : 0))
            throw new IOException("SOCKS authentication method rejected");
          if (auth) {
            String authUser = internalNativeProxy ? "cheems" : node.username;
            String authPass = internalNativeProxy ? Hysteria2Native.localSocksToken() : node.password;
            if (internalNativeProxy && authPass.isEmpty()) throw new IOException("native internal SOCKS session unavailable");
            byte[] user = authUser.getBytes(StandardCharsets.UTF_8),
                pass = authPass.getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255)
              throw new IOException("Credentials too long");
            out.write(1);
            out.write(user.length);
            out.write(user);
            out.write(pass.length);
            out.write(pass);
            out.flush();
            byte[] answer = Aead.readFully(in, 2);
            if (answer[0] != 1 || answer[1] != 0)
              throw new IOException("SOCKS authentication rejected");
          }
          out.write(new byte[] {5, 1, 0});
          out.write(address(host, port));
          out.flush();
          byte[] reply = Aead.readFully(in, 4);
          if (reply[0] != 5 || reply[1] != 0) throw new IOException("SOCKS connection rejected");
          int size = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : reply[3] == 3 ? in.read() : -1;
          if (size < 0) throw new IOException("Invalid SOCKS address");
          Aead.readFully(in, size + 2);
          break;
        case "http":
          String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
          String credentials =
              node.username.isEmpty()
                  ? ""
                  : "Proxy-Authorization: Basic "
                      + Base64.getEncoder()
                          .encodeToString(
                              (node.username + ":" + node.password)
                                  .getBytes(StandardCharsets.UTF_8))
                      + "\r\n";
          out.write(
              ("CONNECT "
                      + authority
                      + " HTTP/1.1\r\nHost: "
                      + authority
                      + "\r\n"
                      + credentials
                      + "\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
          out.flush();
          String response = header(in);
          if (!response.split("\r\n")[0].matches("HTTP/1\\.[01] 200(?: .*|)$"))
            throw new IOException("HTTP proxy rejected connection");
          break;
        default:
          throw new IOException("Unsupported protocol");
      }
      socket.setSoTimeout(120000);
      return new Tunnel(socket, in, out);
    } catch (Exception e) {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
      throw e;
    }
  }

  public static byte[] address(String host, int port) throws IOException {
    if (port < 1 || port > 65535 || host.isEmpty()) throw new IOException("Invalid target");
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    if (host.contains(":")) {
      byte[] ip = InetAddress.getByName(host).getAddress();
      if (ip.length != 16) throw new IOException("Invalid IPv6");
      b.write(4);
      b.write(ip);
    } else {
      byte[] name = IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
      if (name.length > 255) throw new IOException("Host too long");
      b.write(3);
      b.write(name.length);
      b.write(name);
    }
    b.write(port >> 8);
    b.write(port);
    return b.toByteArray();
  }

  public static String header(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int state = 0;
    while (buf.size() < 16384) {
      int ch = in.read();
      if (ch < 0) throw new EOFException();
      buf.write(ch);
      state =
          (state == 0 && ch == 13)
              ? 1
              : (state == 1 && ch == 10)
                  ? 2
                  : (state == 2 && ch == 13) ? 3 : (state == 3 && ch == 10) ? 4 : 0;
      if (state == 4) return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
    throw new IOException("Header too large");
  }
}

