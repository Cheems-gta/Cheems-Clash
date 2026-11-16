package win.cheems.core.net;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses data only: YAML tags cannot instantiate Java classes. */
public final class Subscription {
  public static final int MAX_BYTES = 2 * 1024 * 1024, MAX_NODES = 2000;

  public static List<Node> parse(String text) throws Exception {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("订阅为空或超过 2 MB");
    text = text.trim();
    if (!text.contains("://") && !text.contains("proxies") && !text.startsWith("[")) {
      try {
        text = decode(text).trim();
      } catch (Exception ignored) {
      }
    }
    // Base64 decoding can enlarge the payload; repeat the size check after decoding.
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("解码后的订阅超过 2 MB");
    List<Node> result = new ArrayList<>();
    if (text.startsWith("proxies:")
        || text.contains("\nproxies:")
        || text.startsWith("{")
        || text.startsWith("[")) {
      LoaderOptions opts = new LoaderOptions();
      opts.setMaxAliasesForCollections(0);
      opts.setCodePointLimit(MAX_BYTES);
      opts.setNestingDepthLimit(24);
      opts.setAllowDuplicateKeys(false);
      Object data = new Yaml(new SafeConstructor(opts)).load(text);
      Object list = data instanceof Map ? ((Map<?, ?>) data).get("proxies") : data;
      if (!(list instanceof List)) throw new IllegalArgumentException("没有找到 proxies 节点列表");
      for (Object entry : (List<?>) list) {
        if (!(entry instanceof Map)) continue;
        Map<String, Object> map = new LinkedHashMap<>();
        ((Map<?, ?>) entry).forEach((k, v) -> map.put(String.valueOf(k), v));
        result.add(new Node(map));
        if (result.size() > MAX_NODES) throw new IllegalArgumentException("节点数量超过 2000");
      }
    } else {
      for (String line : text.split("\\r?\\n")) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        result.add(parseUri(line));
        if (result.size() > MAX_NODES) throw new IllegalArgumentException("节点数量超过 2000");
      }
    }
    if (result.isEmpty()) throw new IllegalArgumentException("未识别到节点，请使用 Clash 订阅或节点分享链接");
    return result;
  }

  private static Node parseUri(String link) throws Exception {
    int separator = link.indexOf("://");
    if (separator <= 0)
      throw new IllegalArgumentException("节点分享链接缺少协议头（例如 hysteria2://）");
    String scheme = link.substring(0, separator).toLowerCase(Locale.ROOT);
    if (scheme.equals("tuic")) {
      URI u = new URI(link);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "tuic");
      m.put("server", u.getHost() == null ? "" : u.getHost().replace("[", "").replace("]", ""));
      m.put("port", u.getPort());
      m.put("name", u.getRawFragment() == null ? "TUIC · " + u.getHost() : urlDecode(u.getRawFragment()));
      String credentials = u.getRawUserInfo() == null ? "" : u.getRawUserInfo();
      String[] pair = credentials.split(":", 2);
      m.put("uuid", urlDecode(pair[0]));
      m.put("password", pair.length == 2 ? urlDecode(pair[1]) : "");
      if (u.getRawQuery() != null) for (String part : u.getRawQuery().split("&")) {
        String[] kv = part.split("=", 2);
        String key = urlDecode(kv[0]), value = kv.length == 2 ? urlDecode(kv[1]) : "";
        // Preserve every option; later validation must explicitly reject unsupported values.
        if (!Arrays.asList("sni","peer","alpn","congestion_control","udp_relay_mode").contains(key)) m.put("tuic-unsupported-option", key);
        else m.put(key, value);
        if (key.equals("sni") || key.equals("peer")) m.put("sni", value);
        if (key.equals("congestion_control")) m.put("congestion-controller", value);
        if (key.equals("udp_relay_mode")) m.put("udp-relay-mode", value);
      }
      return new Node(m);
    }
    if (scheme.equals("vmess")) {
      Object decoded = new Yaml(new SafeConstructor(new LoaderOptions())).load(decode(link.substring(separator + 3)));
      if (!(decoded instanceof Map)) throw new IllegalArgumentException("VMess 链接不是有效 JSON 对象");
      Map<?, ?> json = (Map<?, ?>) decoded;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "vmess");
      String host = Node.value(json, "add", "");
      m.put("server", host);
      m.put("port", Node.value(json, "port", "0"));
      m.put("name", Node.value(json, "ps", "VMess · " + host));
      m.put("uuid", Node.value(json, "id", ""));
      m.put("cipher", Node.value(json, "scy", "auto").toLowerCase(Locale.ROOT));
      m.put("alterId", Node.value(json, "aid", "0"));
      m.put("network", Node.value(json, "net", "tcp").toLowerCase(Locale.ROOT));
      m.put("header-type", Node.value(json, "type", "none"));
      if (Node.value(json,"net","tcp").equalsIgnoreCase("grpc")) {
        String mode=Node.value(json,"type","gun");
        m.put("grpc-mode",mode.equals("none") ? "gun" : mode);
        m.put("header-type","none");
        String authority=Node.value(json,"host","");
        if(!authority.isEmpty()) m.put("authority",authority);
      }
      String tls = Node.value(json, "tls", "");
      m.put("tls", tls.equalsIgnoreCase("tls") || tls.equalsIgnoreCase("true"));
      String sni = Node.value(json, "sni", "");
      if (sni.isEmpty() && (tls.equalsIgnoreCase("tls") || tls.equalsIgnoreCase("true")))
        sni = Node.value(json, "host", "");
      m.put("sni", sni.isEmpty() ? host : sni);
      m.put("client-fingerprint", Node.value(json, "fp", ""));
      m.put("ws-host", Node.value(json,"host",host));
      String path = Node.value(json, "path", "");
      if (!path.isEmpty()) m.put("path", path);
      return new Node(m);
    }
    if (scheme.equals("vless")) {
      URI u = new URI(link);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "vless");
      m.put("server", u.getHost() == null ? "" : u.getHost().replace("[", "").replace("]", ""));
      m.put("port", u.getPort());
      m.put("name", u.getRawFragment() == null ? "VLESS · " + u.getHost() : urlDecode(u.getRawFragment()));
      m.put("uuid", u.getRawUserInfo() == null ? "" : urlDecode(u.getRawUserInfo()));
      if (u.getRawQuery() != null) {
        for (String part : u.getRawQuery().split("&")) {
          String[] kv = part.split("=", 2);
          String key = urlDecode(kv[0]), value = kv.length == 2 ? urlDecode(kv[1]) : "";
          if (key.equals("sni") || key.equals("peer")) m.put("sni", value);
          if (key.equals("type")) m.put("network", value);
          if (key.equals("serviceName")) m.put("serviceName",value);
          if (key.equals("authority")) m.put("authority",value);
          if (key.equals("mode")) m.put("grpc-mode",value);
          if (key.equals("path")) m.put("path",value);
          if (key.equals("host")) m.put("ws-host",value);
          if (key.equals("security")) {
            m.put("tls", value.equalsIgnoreCase("tls") || value.equalsIgnoreCase("reality"));
            if (value.equalsIgnoreCase("reality")) m.put("reality-opts", Collections.singletonMap("enabled", true));
          }
          if (key.equals("pbk")) m.put("reality-public-key",value);
          if (key.equals("sid")) m.put("reality-short-id",value);
          if (key.equals("flow")) m.put("flow", value);
          if (key.equals("fp")) m.put("client-fingerprint", value);
          if (key.equals("allowInsecure") || key.equals("insecure"))
            m.put("skip-cert-verify", value.equals("1") || value.equalsIgnoreCase("true"));
        }
      }
      return new Node(m);
    }
    if (scheme.equals("anytls")) {
      URI u = new URI(link);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "anytls");
      m.put("server", u.getHost() == null ? "" : u.getHost().replace("[", "").replace("]", ""));
      m.put("port", (u.getPort() == -1 ? 443 : u.getPort()));
      m.put("name", u.getRawFragment() == null ? "AnyTLS · " + u.getHost() : urlDecode(u.getRawFragment()));
      String password = u.getRawUserInfo() == null ? "" : urlDecode(u.getRawUserInfo());
      m.put("password", password);
      if (u.getRawQuery() != null) {
        for (String part : u.getRawQuery().split("&")) {
          String[] kv = part.split("=", 2);
          String key = urlDecode(kv[0]), value = kv.length == 2 ? urlDecode(kv[1]) : "";
          if (key.equals("sni") || key.equals("peer") || key.equals("serverName")) m.put("sni", value);
          if (key.equals("alpn")) m.put("alpn", value);
          if (key.equals("fp") || key.equals("client-fingerprint")) m.put("client-fingerprint", value);
          if (key.equals("insecure") || key.equals("allowInsecure")) m.put("skip-cert-verify", value.equals("1") || value.equalsIgnoreCase("true"));
        }
      }
      return new Node(m);
    }
    if (scheme.equals("hysteria2") || scheme.equals("hy2")) {
      URI u = new URI(link);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "hysteria2");
      m.put("server", u.getHost() == null ? "" : u.getHost());
      m.put("port", u.getPort());
      m.put("name", u.getRawFragment() == null ? "Hysteria 2 · " + u.getHost() : urlDecode(u.getRawFragment()));
      m.put("password", u.getRawUserInfo() == null ? "" : urlDecode(u.getRawUserInfo()));
      if (u.getRawQuery() != null) {
        for (String part : u.getRawQuery().split("&")) {
          String[] kv = part.split("=", 2);
          String key = urlDecode(kv[0]), value = kv.length == 2 ? urlDecode(kv[1]) : "";
          if (key.equals("sni") || key.equals("peer")) m.put("sni", value);
          if (key.equals("pinSHA256") || key.equals("pin-sha256") || key.equals("pin-sha-256")) m.put("pinSHA256", value);
          if (key.equals("obfs")) m.put("obfs", value);
          if (key.equals("obfs-password")) m.put("obfs-password", value);
          if (key.equals("insecure") || key.equals("allowInsecure"))
            m.put("skip-cert-verify", value.equals("1") || value.equalsIgnoreCase("true"));
        }
      }
      return new Node(m);
    }
    if (!Arrays.asList("ss", "trojan", "socks5", "socks", "http", "https").contains(scheme)) {
      Map<String, Object> unsupported = new LinkedHashMap<>();
      unsupported.put("type", scheme);
      unsupported.put("server", "unsupported");
      unsupported.put("port", 1);
      unsupported.put("name", scheme + " 节点（暂不支持）");
      return new Node(unsupported);
    }
    if (scheme.equals("ss")) {
      String body = link.substring(5), fragment = "";
      int hash = body.indexOf('#');
      if (hash >= 0) {
        fragment = body.substring(hash);
        body = body.substring(0, hash);
      }
      if (!body.contains("@")) body = decode(body);
      link = "ss://" + body + fragment;
    }
    URI u = new URI(link);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", scheme.equals("https") ? "http" : scheme.equals("socks") ? "socks5" : scheme);
    if (scheme.equals("https")) m.put("tls", true);
    m.put("server", u.getHost() == null ? "" : u.getHost().replace("[", "").replace("]", ""));
    m.put(
        "port",
        u.getPort() > 0
            ? u.getPort()
            : scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : 0);
    m.put(
        "name",
        u.getRawFragment() == null ? scheme + " · " + u.getHost() : urlDecode(u.getRawFragment()));
    String user = u.getRawUserInfo() == null ? "" : urlDecode(u.getRawUserInfo());
    if (scheme.equals("ss")) {
      if (!user.contains(":")) user = decode(user);
      int sep = user.indexOf(':');
      if (sep < 0) throw new IllegalArgumentException("SS 链接缺少加密方式");
      m.put("cipher", user.substring(0, sep));
      m.put("password", user.substring(sep + 1));
    } else if (scheme.equals("trojan")) m.put("password", user);
    else {
      String[] pair = user.split(":", 2);
      m.put("username", pair[0]);
      m.put("password", pair.length > 1 ? pair[1] : "");
    }
    if (u.getRawQuery() != null)
      for (String part : u.getRawQuery().split("&")) {
        String[] kv = part.split("=", 2);
        String key = urlDecode(kv[0]), v = kv.length == 2 ? urlDecode(kv[1]) : "";
        if (key.equals("sni") || key.equals("peer")) m.put("sni", v);
        if (key.equals("type")) m.put("network", v);
        if (key.equals("plugin")) m.put("plugin", v);
        if ((key.equals("allowInsecure") || key.equals("insecure"))
            && (v.equals("1") || v.equals("true"))) m.put("skip-cert-verify", true);
      }
    return new Node(m);
  }

  private static String urlDecode(String s) throws Exception {
    return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
  }

  private static String decode(String s) {
    s = s.replaceAll("\\s", "");
    try {
      return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
  }
}

