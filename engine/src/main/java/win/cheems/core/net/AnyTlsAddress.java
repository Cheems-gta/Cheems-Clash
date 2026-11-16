package win.cheems.core.net;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** SOCKS5 address serialization used as the initial AnyTLS stream payload.
 * This is a wire-format utility, not an AnyTLS connection implementation.
 */
public final class AnyTlsAddress {
  private AnyTlsAddress() {}
  public static byte[] encode(String host, int port) {
    if (host == null || host.isEmpty() || port < 1 || port > 65535)
      throw new IllegalArgumentException("Invalid destination");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      // Avoid resolving domain names locally: only literal IPs are encoded as IP.
      if (host.matches("[0-9.]+")) {
        InetAddress ip = InetAddress.getByName(host);
        if (!(ip instanceof Inet4Address)) throw new IllegalArgumentException("Invalid IPv4");
        out.write(1); out.write(ip.getAddress());
      } else if (host.startsWith("[") && host.endsWith("]")) {
        InetAddress ip = InetAddress.getByName(host.substring(1, host.length()-1));
        if (!(ip instanceof Inet6Address)) throw new IllegalArgumentException("Invalid IPv6");
        out.write(4); out.write(ip.getAddress());
      } else {
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) throw new IllegalArgumentException("Hostname too long");
        out.write(3); out.write(name.length); out.write(name);
      }
      out.write((port >>> 8) & 255); out.write(port & 255);
      return out.toByteArray();
    } catch (java.io.IOException e) {throw new IllegalArgumentException("Invalid destination",e);}
  }
}
