package win.cheems.core.net;

import win.cheems.core.CoreService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional gomobile Hysteria 2 bridge. The default Java-only APK does not
 * contain the native AAR; only a build that packages it can start this engine.
 * Calls must be made on a background thread.
 */
public final class Hysteria2Native {
  private static final String BINDING = "go.mobilebridge.Mobilebridge";
  public static final int LOCAL_SOCKS_PORT = 10990;
  private static final AtomicReference<String> localToken = new AtomicReference<>();
  private Hysteria2Native() {}

  public static String localSocksToken() {
    String token = localToken.get();
    return token == null ? "" : token;
  }

  private static Class<?> binding() throws ClassNotFoundException {
    return Class.forName(BINDING);
  }

  public static boolean isPackaged() {
    try {
      Class<?> network = Class.forName("go.mobilebridge.Network");
      binding().getMethod("startWithNetwork", String.class, long.class, String.class, String.class,
          long.class, String.class, boolean.class, String.class, String.class, network);
      binding().getMethod("startAnyTLSConfiguredWithNetwork", String.class, long.class, String.class, String.class,
          long.class, String.class, boolean.class, String.class, String.class, String.class, String.class, network);
      binding().getMethod("startVLESSWithNetwork", String.class, long.class, String.class, String.class,
          long.class, String.class, boolean.class, String.class, String.class, network);
      binding().getMethod("startSOCKS5WithNetwork", String.class, long.class, String.class, String.class,
          long.class, String.class, network);
      binding().getMethod("startVMessWithNetwork", String.class, long.class, String.class, String.class,
          String.class, long.class, boolean.class, long.class, String.class, String.class, network);
      binding().getMethod("startShadowsocks2022WithNetwork", String.class, long.class, String.class,
          String.class, long.class, String.class, network);
      binding().getMethod("startTUICWithNetwork", String.class, long.class, String.class, String.class,
          String.class, String.class, String.class, boolean.class, long.class, String.class, String.class, network);
      binding().getMethod("startVLESSWebSocketWithNetwork", String.class, long.class, String.class, String.class, long.class, String.class, boolean.class, String.class, String.class, String.class, String.class, network);
      binding().getMethod("startVLESSGRPCWithNetwork", String.class, long.class, String.class, String.class, long.class, String.class, boolean.class, String.class, String.class, String.class, String.class, network);
      binding().getMethod("startVMessWebSocketWithNetwork", String.class, long.class, String.class, String.class, String.class, long.class, boolean.class, long.class, String.class, String.class, String.class, String.class, network);
      binding().getMethod("startVMessGRPCWithNetwork", String.class, long.class, String.class, String.class, String.class, long.class, boolean.class, long.class, String.class, String.class, String.class, String.class, network);
      binding().getMethod("startVLESSRealityWithNetwork", String.class, long.class, String.class, String.class, String.class, String.class, String.class, long.class, String.class, network);
      binding().getMethod("startVMessTLSWithNetwork", String.class, long.class, String.class,
          String.class, String.class, long.class, boolean.class, long.class, String.class,
          String.class, boolean.class, String.class, String.class, String.class, network);
      binding().getMethod("startTUICTLSWithNetwork", String.class, long.class, String.class,
          String.class, String.class, String.class, String.class, boolean.class, long.class,
          String.class, String.class, boolean.class, network);
      binding().getMethod("startTunnel", long.class);
      binding().getMethod("stopTunnel");
      return true;
    }
    catch (ReflectiveOperationException | LinkageError e) { return false; }
  }

  public static void start(Node node, int localPort, boolean allowUnverifiedTLS, String customCAPEM,
      CoreService vpnService) throws Exception {
    boolean hy2 = "hysteria2".equals(node.type);
    boolean anytls = "anytls".equals(node.type);
    boolean vless = "vless".equals(node.type);
    boolean vmess = "vmess".equals(node.type) && isPackaged();
    boolean tuic = "tuic".equals(node.type) && isPackaged();
    boolean ss2022 = "ss2022".equals(node.type) && isPackaged();
    boolean socks5 = "socks5".equals(node.type) && isPackaged();
    if (!hy2 && !anytls && !vless && !vmess && !ss2022 && !tuic && !socks5) throw new IllegalArgumentException("Native node protocol required");
    if (((vless || vmess || tuic) && node.uuid.isEmpty()) || (!vless && !vmess && !socks5 && node.password.isEmpty())) throw new IllegalArgumentException("Missing node credentials");
    if (localPort < 1 || localPort > 65535) throw new IllegalArgumentException("Invalid local port");
    String platformAndCustomCAPEM = "";
    if (!allowUnverifiedTLS && !socks5 && !ss2022 && !node.isReality()) {
      String trustedCAPEM = customCAPEM == null || customCAPEM.isBlank()
          ? node.trustedCAPEM() : customCAPEM;
      if (trustedCAPEM.length() > 64 * 1024)
        throw new IllegalArgumentException("Custom CA certificate exceeds 64 KiB");
      platformAndCustomCAPEM = PlatformTrust.withCustomCA(trustedCAPEM);
    }
    byte[] nonce = new byte[32];
    new SecureRandom().nextBytes(nonce);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    localToken.set(token);
    try {
      Class<?> networkType=Class.forName("go.mobilebridge.Network");
      Object network=Proxy.newProxyInstance(networkType.getClassLoader(),new Class<?>[]{networkType},
          (proxy,method,args)->{
            if(method.getName().equals("protect")) return vpnService.protectAndBind((Long)args[0]);
            if(method.getName().equals("resolve")) return vpnService.resolveOnPhysical((String)args[0]);
            if(method.getName().equals("toString")) return "CheemsPhysicalNetwork";
            throw new UnsupportedOperationException(method.getName());
          });
      if (socks5) {
        Method start = binding().getMethod("startSOCKS5WithNetwork", String.class, long.class,
            String.class, String.class, long.class, String.class, networkType);
        start.invoke(null, node.host, (long) node.port, node.username, node.password,
            (long) localPort, token, network);
      } else if (vless && node.isReality()) {
        Method start=binding().getMethod("startVLESSRealityWithNetwork", String.class, long.class, String.class, String.class, String.class, String.class, String.class, long.class, String.class, networkType);
        start.invoke(null,node.host,(long)node.port,node.sni,node.uuid,node.realityPublicKey(),node.realityShortID(),node.realityFingerprint(),(long)localPort,token,network);
      } else if (vless && node.isGRPC()) {
        Method start=binding().getMethod("startVLESSGRPCWithNetwork", String.class, long.class, String.class, String.class, long.class, String.class, boolean.class, String.class, String.class, String.class, String.class, networkType);
        start.invoke(null,node.host,(long)node.port,node.sni,node.uuid,(long)localPort,token,allowUnverifiedTLS,platformAndCustomCAPEM,allowUnverifiedTLS ? "" : node.certificateSHA256(),node.grpcServiceName(),node.grpcAuthority(),network);
      } else if (vless && node.isWebSocket()) {
        Method start=binding().getMethod("startVLESSWebSocketWithNetwork", String.class, long.class, String.class, String.class, long.class, String.class, boolean.class, String.class, String.class, String.class, String.class, networkType);
        start.invoke(null,node.host,(long)node.port,node.sni,node.uuid,(long)localPort,token,allowUnverifiedTLS,platformAndCustomCAPEM,allowUnverifiedTLS ? "" : node.certificateSHA256(),node.websocketPath(),node.websocketHost(),network);
      } else if (vmess) {
        Method start = binding().getMethod("startVMessTLSWithNetwork", String.class, long.class,
            String.class, String.class, String.class, long.class, boolean.class, long.class,
            String.class, String.class, boolean.class, String.class, String.class, String.class, networkType);
        String transport = node.isGRPC() ? "grpc" : node.isWebSocket() ? "ws" : "tcp";
        String path = node.isGRPC() ? node.grpcServiceName() : node.isWebSocket() ? node.websocketPath() : "";
        String authority = node.isGRPC() ? node.grpcAuthority() : node.isWebSocket() ? node.websocketHost() : "";
        start.invoke(null, node.host, (long) node.port, node.sni, node.uuid, node.cipher,
            Long.parseLong(Node.value(node.source, "alterId", "0")), node.tls(),
            (long) localPort, token, platformAndCustomCAPEM, allowUnverifiedTLS, transport, path, authority, network);
      } else if (tuic) {
        Method start = binding().getMethod("startTUICTLSWithNetwork", String.class, long.class, String.class,
            String.class, String.class, String.class, String.class, boolean.class, long.class,
            String.class, String.class, boolean.class, networkType);
        start.invoke(null, node.host, (long) node.port, node.sni, node.uuid, node.password,
            Node.value(node.source, "congestion-controller", "cubic"), node.tuicALPN(),
            "quic".equals(Node.value(node.source, "udp-relay-mode", "native")),
            (long) localPort, token, platformAndCustomCAPEM, allowUnverifiedTLS, network);
      } else if (anytls) {
        Method start = binding().getMethod("startAnyTLSConfiguredWithNetwork", String.class, long.class,
            String.class, String.class, long.class, String.class, boolean.class, String.class,
            String.class, String.class, String.class, networkType);
        start.invoke(null, node.host, (long) node.port, node.sni, node.password, (long) localPort,
            token, allowUnverifiedTLS, platformAndCustomCAPEM, allowUnverifiedTLS ? "" : node.certificateSHA256(),
            node.anyTLSFingerprint(), node.anyTLSALPN(), network);
      } else if (ss2022) {
        Method start = binding().getMethod("startShadowsocks2022WithNetwork", String.class, long.class,
            String.class, String.class, long.class, String.class, networkType);
        start.invoke(null, node.host, (long) node.port, node.cipher, node.password,
            (long) localPort, token, network);
      } else {
        String methodName = hy2 ? "startWithNetwork" : "startVLESSWithNetwork";
        Method start = binding().getMethod(methodName, String.class, long.class, String.class,
            String.class, long.class, String.class, boolean.class, String.class, String.class, networkType);
        start.invoke(null, node.host, (long) node.port, node.sni, vless ? node.uuid : node.password,
            (long) localPort, token, allowUnverifiedTLS, platformAndCustomCAPEM, allowUnverifiedTLS ? "" : node.certificateSHA256(),network);
      }
    } catch (ClassNotFoundException e) {
      localToken.compareAndSet(token, null);
      throw new IllegalStateException("Native protocol AAR is not packaged in this APK", e);
    } catch (InvocationTargetException e) {
      localToken.compareAndSet(token, null);
      Throwable cause = e.getCause();
      String detail = cause == null ? "未知错误" : cause.getMessage();
      if (detail == null || detail.isBlank()) detail = "未知错误";
      throw new IllegalStateException("节点建连失败：" + detail, cause);
    } catch (ReflectiveOperationException | LinkageError e) {
      localToken.compareAndSet(token, null);
      throw new IllegalStateException("Android 原生协议桥接异常", e);
    }
  }

  public static void startTunnel(long fd) throws Exception {
    try {binding().getMethod("startTunnel",long.class).invoke(null,fd);}
    catch(InvocationTargetException e){Throwable c=e.getCause();throw new IllegalStateException(c==null?"TUN启动失败":c.getMessage(),c);}
  }

  public static void stopTunnel() throws Exception {
    try {binding().getMethod("stopTunnel").invoke(null);}
    catch(InvocationTargetException e){Throwable c=e.getCause();throw new IllegalStateException(c==null?"TUN关闭失败":c.getMessage(),c);}
  }

  public static void stop() throws Exception {
    localToken.set(null);
    binding().getMethod("stop").invoke(null);
  }

  private static long tunnelMetric(String name) {
    try { return ((Number)binding().getMethod(name).invoke(null)).longValue(); }
    catch (ReflectiveOperationException | LinkageError e) { return 0; }
  }
  public static long tunnelUploaded() { return tunnelMetric("tunnelUploaded"); }
  public static long tunnelDownloaded() { return tunnelMetric("tunnelDownloaded"); }
  public static long tunnelConnections() { return tunnelMetric("tunnelConnections"); }
  public static long tunnelDNSQueries() { return tunnelMetric("tunnelDNSQueries"); }
  public static String tunnelLastError() {
    try { return (String)binding().getMethod("tunnelLastError").invoke(null); }
    catch (ReflectiveOperationException | LinkageError e) { return ""; }
  }

  public static boolean isRunning() {
    try { return (Boolean) binding().getMethod("isRunning").invoke(null); }
    catch (ReflectiveOperationException | LinkageError e) { return false; }
  }
}

