package win.cheems.core.net;

import java.util.Locale;

/**
 * Capability registry reflects actual Dialer implementations, not parsed subscription formats.
 * Import support and transport support are deliberately separate.
 */
public final class TransportCapabilities {
  private TransportCapabilities() {}

  public enum Status { TCP_READY, PARSE_ONLY, NOT_IMPLEMENTED }

  public static Status status(String protocol) {
    if (protocol == null) return Status.NOT_IMPLEMENTED;
    switch (protocol.toLowerCase(Locale.ROOT)) {
      case "ss":
      case "ss2022":
      case "tuic":
      case "trojan":
      case "socks5":
      case "http":
      case "anytls":
      case "vless":
      case "vmess":
        return Status.TCP_READY;
      case "hysteria2":
      case "hy2":
        return Status.PARSE_ONLY;
      default:
        return Status.NOT_IMPLEMENTED;
    }
  }

  public static boolean canDialTcp(String protocol) {
    return status(protocol) == Status.TCP_READY;
  }

  public static boolean canDialUdp(String protocol) {
    // Native AAR-backed VPN transports only; this does not imply server support.
    return protocol != null && (protocol.equalsIgnoreCase("socks5") || protocol.equalsIgnoreCase("vmess") || protocol.equalsIgnoreCase("ss2022") || protocol.equalsIgnoreCase("tuic"));
  }

  public static boolean requiresQuic(String protocol) {
    return protocol != null &&
        (protocol.equalsIgnoreCase("hysteria2") || protocol.equalsIgnoreCase("hy2") ||
         protocol.equalsIgnoreCase("tuic"));
  }
}

