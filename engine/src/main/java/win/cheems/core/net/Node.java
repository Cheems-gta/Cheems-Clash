package win.cheems.core.net;

import java.util.*;

/** Immutable imported endpoint. Credentials must never appear in logs. */
public final class Node {
  public final String name, type, host, password, username, cipher, sni, uuid, unsupported;
  public final int port;
  public final Map<String, Object> source;

  public Node(Map<String, Object> m) {
    source = Collections.unmodifiableMap(new LinkedHashMap<>(m));
    String rawType = value(m, "type", "").toLowerCase(Locale.ROOT);
    String parsedCipher = value(m, "cipher", "").toLowerCase(Locale.ROOT);
    type = rawType.equals("hy2") ? "hysteria2"
        : rawType.equals("ss") && parsedCipher.startsWith("2022-blake3-") ? "ss2022" : rawType;
    host = value(m, "server", "");
    int p;
    try {
      p = Integer.parseInt(value(m, "port", "0"));
    } catch (Exception e) {
      p = 0;
    }
    port = p;
    name = value(m, "name", type + " · " + host);
    password = value(m, "password", "");
    uuid = value(m, "uuid", "");
    username = value(m, "username", "");
    cipher = value(m, "cipher", "").toLowerCase(Locale.ROOT);
    sni = value(m, "sni", value(m, "servername", host));
    String reason = "";
    if (host.isEmpty() || port < 1 || port > 65535) reason = "地址或端口无效";
    else if ((type.equals("hysteria2") || type.equals("anytls")) && password.isEmpty()) reason = type.toUpperCase(Locale.ROOT) + " 缺少认证密码";
    else if (type.equals("vless") && !uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) reason = "VLESS UUID 无效";
    else if (type.equals("vless") && !Boolean.parseBoolean(value(m, "tls", "false"))) reason = "当前仅支持 VLESS TCP+TLS";
    else if (type.equals("vless") && !value(m, "flow", "").isEmpty()) reason = "暂不支持 VLESS flow / XTLS Vision";
    else if (type.equals("vless") && !isReality() && !value(m, "client-fingerprint", "").isEmpty()) reason = "VLESS 客户端 TLS 指纹尚未实现";
    else if (type.equals("vmess") && !uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) reason = "VMess UUID 无效";
    else if (type.equals("vmess") && !Arrays.asList("auto", "none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305").contains(cipher)) reason = "暂不支持 VMess 加密方式 " + cipher;
    else if (type.equals("vmess") && !validAlterId(value(m, "alterId", "0"))) reason = "VMess alterId 必须为 0 到 65535";
    else if (type.equals("vmess") && !value(m, "header-type", "none").equalsIgnoreCase("none")) reason = "暂不支持 VMess TCP header type " + value(m, "header-type", "");
    else if (type.equals("vmess") && !value(m, "client-fingerprint", "").isEmpty()) reason = "VMess 客户端 TLS 指纹尚未实现";
    else if (type.equals("vmess") && !isWebSocket() && !isGRPC() && !value(m, "path", "").isEmpty()) reason = "当前 VMess TCP 模式不接受 path 参数";
    else if (type.equals("tuic") && (!uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || password.isEmpty())) reason = "TUIC v5 UUID 或密码无效";
    else if (type.equals("tuic") && !validTuicALPN(tuicALPN())) reason = "TUIC ALPN 参数无效";
    else if (type.equals("tuic") && !Arrays.asList("cubic","new_reno","bbr").contains(value(m,"congestion-controller","cubic"))) reason = "TUIC 拥塞控制参数不支持";
    else if (type.equals("tuic") && !Arrays.asList("native","quic").contains(value(m,"udp-relay-mode","native"))) reason = "TUIC UDP relay mode 不支持";
    else if (type.equals("tuic") && (Boolean.parseBoolean(value(m,"reduce-rtt","false")) || Boolean.parseBoolean(value(m,"zero-rtt","false")) || Boolean.parseBoolean(value(m,"disable-sni","false")))) reason = "TUIC 0-RTT / 禁用 SNI 尚未支持";
    else if (type.equals("tuic") && (m.containsKey("tuic-unsupported-option") || m.containsKey("token") || m.containsKey("pinSHA256") || m.containsKey("pin-sha256") || m.containsKey("client-fingerprint"))) reason = "TUIC 含有未支持的选项";
    else if (type.equals("ss2022") && !validSS2022Key(cipher, password)) reason = "Shadowsocks 2022 PSK 格式或长度无效";
    else if (type.equals("hysteria2") && (!value(m, "obfs", "").isEmpty() || !value(m, "obfs-password", "").isEmpty()))
      reason = "当前独立内核尚不支持 Hysteria 2 混淆（Salamander）";
    else if (type.equals("hysteria2") && m.containsKey("ports"))
      reason = "当前独立内核尚不支持 Hysteria 2 端口跳跃";
    else if (type.equals("anytls") && !Arrays.asList("", "none", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random").contains(anyTLSFingerprint()))
      reason = "AnyTLS 客户端 TLS 指纹名称不支持：" + anyTLSFingerprint();
    else if (type.equals("anytls") && !anyTLSALPN().isEmpty() && !validTuicALPN(anyTLSALPN()))
      reason = "AnyTLS ALPN 参数无效";
    else if (!Arrays.asList("ss", "ss2022", "trojan", "socks5", "http", "hysteria2", "anytls", "vless", "vmess", "tuic").contains(type))
      reason = "暂未实现 " + type + " 协议";
    else if (m.containsKey("plugin") && !value(m, "plugin", "").isEmpty()) reason = "暂不支持插件传输";
    else if (isGRPC() && !validGRPC()) reason = "gRPC 仅支持 TLS 标准 Tun 模式；服务名称或参数无效";
    else if (isWebSocket() && !validWebSocket()) reason = "WebSocket 参数无效或包含未支持的头部/早期数据";
    else if (!isWebSocket() && !isGRPC() && !Arrays.asList("tcp", "").contains(value(m, "network", "tcp")))
      reason = "暂不支持 " + value(m, "network", "") + " 传输";
    else if (!isGRPC() && m.containsKey("grpc-opts")) reason = "grpc-opts 与传输类型不匹配";
    else if (isReality() && !validReality()) reason = "Reality 参数无效或传输组合尚未支持";
    else if ((!type.equals("vless") && m.containsKey("reality-opts")) || (!isWebSocket() && m.containsKey("ws-opts")))
      reason = "暂不支持 Reality / WebSocket";
    else if (type.equals("ss")
        && !Arrays.asList("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305").contains(cipher))
      reason = "暂不支持加密方式 " + cipher;
    else if ((type.equals("ss") || type.equals("trojan")) && password.isEmpty()) reason = "缺少密码";
    unsupported = reason;
  }

  /** Build the app's explicitly requested compatibility policy without altering saved credentials. */
  public Node withoutCertificateChecks() {
    if (isReality()) return this; // Reality uses protocol authentication, not a CA certificate.
    Map<String, Object> config = new LinkedHashMap<>(source);
    config.put("skip-cert-verify", true);
    for (String key : Arrays.asList("ca", "ca-str", "pinSHA256", "pin-sha256", "pin-sha-256")) config.remove(key);
    return new Node(config);
  }

  public String anyTLSFingerprint() {
    return value(source, "client-fingerprint", value(source, "fp", "")).trim().toLowerCase(Locale.ROOT);
  }

  public String anyTLSALPN() {
    Object raw = source.get("alpn");
    if (raw instanceof List) {
      List<String> protocols = new ArrayList<>();
      for (Object entry : (List<?>) raw) protocols.add(String.valueOf(entry));
      return String.join(",", protocols);
    }
    return value(source, "alpn", "");
  }

  /** The provider's original TLS hint; the app applies its connection policy separately. */
  public boolean hasInsecureTlsFlag() {
    return (type.equals("hysteria2") || type.equals("anytls") || type.equals("vless"))
        && Boolean.parseBoolean(value(source, "skip-cert-verify", "false"));
  }

  /** An inline PEM CA imported in the subscription (never a filesystem path). */
  public String trustedCAPEM() {
    if (!type.equals("hysteria2") && !type.equals("anytls") && !type.equals("vless") && !type.equals("tuic") && !(type.equals("vmess") && tls())) return "";
    String pem = value(source, "ca-str", value(source, "ca", ""));
    return pem.contains("-----BEGIN CERTIFICATE-----") ? pem : "";
  }

  /** HY2 server leaf-certificate pin; do not confuse with TLS client fingerprint. */
  public String certificateSHA256() {
    if (!type.equals("hysteria2") && !type.equals("anytls") && !type.equals("vless")) return "";
    return value(source, "pinSHA256",
        value(source, "pin-sha256", value(source, "pin-sha-256", "")));
  }

  public boolean supported() {
    return unsupported.isEmpty();
  }

  public boolean tls() {
    String setting = value(source, "tls", "false");
    return type.equals("trojan") || type.equals("anytls") || type.equals("vless")
        || Boolean.parseBoolean(setting) || (type.equals("vmess") && setting.equalsIgnoreCase("tls"));
  }

  public boolean isReality() { return type.equals("vless") && source.containsKey("reality-opts"); }
  private Map<?,?> realityOptions() { Object o=source.get("reality-opts");return o instanceof Map ? (Map<?,?>)o : Collections.emptyMap(); }
  public String realityPublicKey() { return value(realityOptions(),"public-key",value(source,"reality-public-key","")); }
  public String realityShortID() { return value(realityOptions(),"short-id",value(source,"reality-short-id","")); }
  public String realityFingerprint() { String fp=value(source,"client-fingerprint","chrome");return fp.isEmpty()?"chrome":fp; }
  private boolean validReality() {
    if(!(source.get("reality-opts") instanceof Map) || !value(source,"network","tcp").equals("tcp")) return false;
    for(Object key:realityOptions().keySet()) if(!Arrays.asList("enabled","public-key","short-id").contains(String.valueOf(key))) return false;
    if(!realityPublicKey().matches("[A-Za-z0-9_-]{43}") || !realityShortID().matches("(?:[a-fA-F0-9]{2}){0,8}")) return false;
    try { if(Base64.getUrlDecoder().decode(realityPublicKey()).length!=32) return false; } catch(IllegalArgumentException e) { return false; }
    return Arrays.asList("chrome","firefox").contains(realityFingerprint()) && !sni.isEmpty()
        && !Boolean.parseBoolean(value(source,"skip-cert-verify","false")) && trustedCAPEM().isEmpty() && certificateSHA256().isEmpty();
  }

  public boolean isGRPC() {
    return (type.equals("vmess") || type.equals("vless")) && value(source,"network","tcp").equals("grpc");
  }
  private Map<?,?> grpcOptions() {
    Object options=source.get("grpc-opts");return options instanceof Map ? (Map<?,?>)options : Collections.emptyMap();
  }
  public String grpcServiceName() { return value(grpcOptions(),"grpc-service-name",value(source,"serviceName",value(source,"path",""))); }
  public String grpcAuthority() { return value(grpcOptions(),"authority",value(source,"authority",sni)); }
  private boolean validGRPC() {
    if(!tls() || !grpcServiceName().matches("[A-Za-z0-9_.-]+") || grpcAuthority().isEmpty()) return false;
    if(!value(source,"grpc-mode","gun").equals("gun")) return false;
    if(source.containsKey("grpc-opts") && !(source.get("grpc-opts") instanceof Map)) return false;
    for(Object key:grpcOptions().keySet()) if(!Arrays.asList("grpc-service-name","authority").contains(String.valueOf(key))) return false;
    return !grpcAuthority().contains("/") && !grpcAuthority().contains("\r") && !grpcAuthority().contains("\n");
  }

  public boolean isWebSocket() {
    return (type.equals("vmess") || type.equals("vless")) && value(source,"network","tcp").equals("ws");
  }
  private Map<?,?> wsOptions() {
    Object options=source.get("ws-opts");return options instanceof Map ? (Map<?,?>)options : Collections.emptyMap();
  }
  public String websocketPath() { return value(wsOptions(),"path",value(source,"path","/")); }
  public String websocketHost() {
    Object headers=wsOptions().get("headers");
    if(headers instanceof Map) for(Map.Entry<?,?> e:((Map<?,?>)headers).entrySet())
      if(String.valueOf(e.getKey()).equalsIgnoreCase("host")) return String.valueOf(e.getValue());
    String configured=value(source,"ws-host","");
    return configured.isEmpty() ? (host.contains(":") ? "["+host+"]" : host)+":"+port : configured;
  }
  private boolean validWebSocket() {
    if(source.containsKey("ws-opts") && !(source.get("ws-opts") instanceof Map)) return false;
    for(Object key:wsOptions().keySet()) if(!Arrays.asList("path","headers").contains(String.valueOf(key))) return false;
    Object headers=wsOptions().get("headers");
    if(headers!=null && !(headers instanceof Map)) return false;
    if(headers instanceof Map) for(Object key:((Map<?,?>)headers).keySet()) if(!String.valueOf(key).equalsIgnoreCase("host")) return false;
    String path=websocketPath(),hostHeader=websocketHost();
    return path.startsWith("/") && !path.contains("\r") && !path.contains("\n") && !path.contains("#")
        && !hostHeader.isEmpty() && !hostHeader.contains("\r") && !hostHeader.contains("\n")
        && !path.matches(".*[?&]ed=[1-9][0-9]*.*");
  }

  private static boolean validTuicALPN(String protocols) {
    for (String protocol : protocols.split(",", -1)) {
      if (protocol.isEmpty() || protocol.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
          || protocol.contains("\r") || protocol.contains("\n")) return false;
    }
    return true;
  }

  public String tuicALPN() {
    Object raw = source.get("alpn");
    if (raw instanceof List) {
      List<String> protocols = new ArrayList<>();
      for (Object entry : (List<?>) raw) protocols.add(String.valueOf(entry));
      return String.join(",", protocols);
    }
    return value(source, "alpn", "h3");
  }

  private static boolean validAlterId(String value) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed >= 0 && parsed <= 65535;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean validSS2022Key(String method, String password) {
    int expected = "2022-blake3-aes-128-gcm".equals(method) ? 16
        : ("2022-blake3-aes-256-gcm".equals(method)
            || "2022-blake3-chacha20-poly1305".equals(method)) ? 32 : 0;
    if (expected == 0 || password.isEmpty()) return false;
    try { return Base64.getDecoder().decode(password).length == expected; }
    catch (IllegalArgumentException e) { return false; }
  }

  public static String value(Map<?, ?> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : String.valueOf(v);
  }

  @Override
  public String toString() {
    return name + " · " + type.toUpperCase(Locale.ROOT) + (supported() ? "" : " · " + unsupported);
  }
}

