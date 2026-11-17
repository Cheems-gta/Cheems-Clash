package win.cheems.core;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import win.cheems.core.net.LocalProxyProbe;
import win.cheems.core.net.Hysteria2Native;
import win.cheems.core.net.Node;
import win.cheems.core.net.Subscription;

/** Public entry point for Cheems Clash's Compose UI; delegates to standalone Cheems Core. */
public final class CheemsBridge {
  private CheemsBridge() {}

  private static boolean supportedInVPN(Node n) {
    return n.supported() && java.util.Arrays.asList("hysteria2", "anytls", "vless", "vmess", "ss2022", "tuic", "socks5").contains(n.type);
  }

  public static String listNodes(String profile) throws Exception {
    List<Node> nodes = Subscription.parse(profile);
    JSONArray output = new JSONArray();
    for (int i=0; i<nodes.size(); i++) {
      Node n=nodes.get(i);
      JSONObject item=new JSONObject();
      item.put("index", i);
      item.put("name", n.name);
      item.put("protocol", n.type);
      item.put("supported", supportedInVPN(n));
      item.put("reason", supportedInVPN(n) ? "" : (n.supported() ? "当前全局 VPN 仅支持原生协议" : n.unsupported));
      output.put(item);
    }
    return output.toString();
  }

  public static void connect(Context context, String profile, int index) throws Exception {
    if (CoreService.serviceAlive || CoreService.connecting || CoreService.tunnelEstablished || CoreService.stopping || CoreService.nativeShutdownInProgress || Hysteria2Native.isRunning())
      throw new IllegalStateException("当前已有 VPN 连接或正在连接，请先断开");
    List<Node> nodes = Subscription.parse(profile);
    if (index < 0 || index >= nodes.size()) throw new IllegalArgumentException("请选择一个节点");
    Node original = nodes.get(index);
    if (!supportedInVPN(original)) throw new IllegalArgumentException("该节点尚不支持独立内核全局 VPN");
    CoreService.selected = original.withoutCertificateChecks();
    Intent intent = new Intent(context, CoreService.class);
    // Explicitly requested compatibility mode. Reality uses its own server authentication.
    intent.putExtra("hy2.allowUnverifiedTls", !original.isReality());
    intent.putExtra("hy2.trustedCaPem", "");
    CoreService.lastFailure = null;
    context.startForegroundService(intent);
  }

  public static void disconnect(Context context) {
    // A system-bound VpnService can outlive stopService(). Contact the active
    // instance so Android's VPN FD is closed even if onDestroy() is delayed.
    CoreService.stopping = true;
    CoreService service = CoreService.activeInstance;
    if (service != null) {
      service.requestImmediateDisconnect();
      return;
    }
    boolean requested = context.stopService(new Intent(context, CoreService.class));
    if (!requested && !CoreService.serviceAlive && !CoreService.nativeShutdownInProgress)
      CoreService.stopping = false;
  }

  /** Choose an actual non-VPN network, also when Cheems VPN is stopped. */
  private static Network physicalNetwork(Context context) {
    ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
    if (cm == null) throw new IllegalStateException("无法获取 Android 网络服务");
    Network fallback = null;
    for (Network candidate : cm.getAllNetworks()) {
      NetworkCapabilities cap = cm.getNetworkCapabilities(candidate);
      if (cap == null
          || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        continue;
      if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return candidate;
      if (fallback == null) fallback = candidate;
    }
    if (fallback == null) throw new IllegalStateException("未找到可用的 Wi-Fi 或移动网络");
    return fallback;
  }

  /** Measures a real protocol handshake/stream, not misleading TCP port latency. */
  public static long measureNodeLatency(Context context, String profile, int index) throws Exception {
    if (CoreService.stopping || CoreService.nativeShutdownInProgress)
      throw new IllegalStateException("VPN 正在断开，请稍后测速");
    List<Node> nodes = Subscription.parse(profile);
    if (index < 0 || index >= nodes.size()) throw new IllegalArgumentException("节点下标无效");
    Node node = nodes.get(index);
    if (!node.supported() || (!"hysteria2".equals(node.type) && !"anytls".equals(node.type)))
      throw new UnsupportedOperationException("此协议尚未提供独立握手测速");
    Network physical = physicalNetwork(context);
    CoreService service = CoreService.activeInstance;
    Class<?> networkType = Class.forName("go.mobilebridge.Network");
    Object network = java.lang.reflect.Proxy.newProxyInstance(
        networkType.getClassLoader(), new Class<?>[]{networkType},
        (proxy, method, args) -> {
          if ("protect".equals(method.getName())) {
            long rawFd = ((Number) args[0]).longValue();
            if (rawFd < 0 || rawFd > Integer.MAX_VALUE) return false;
            if (service != null && CoreService.serviceAlive
                && !CoreService.stopping && !CoreService.nativeShutdownInProgress)
              return service.protectAndBind(rawFd);
            // Without an active VPN, explicitly bind to a physical Android
            // network. fromFd duplicates the descriptor and closes the copy.
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.fromFd((int)rawFd)) {
              physical.bindSocket(fd.getFileDescriptor());
              return true;
            } catch (IOException | RuntimeException error) {
              return false;
            }
          }
          if ("resolve".equals(method.getName())) {
            InetAddress[] answers = physical.getAllByName((String)args[0]);
            if (answers.length == 0) throw new IOException("Physical DNS returned no addresses");
            return answers[0].getHostAddress();
          }
          if ("toString".equals(method.getName())) return "CheemsLatencyPhysicalNetwork";
          throw new UnsupportedOperationException(method.getName());
        });
    try {
      Class<?> bridge = Class.forName("go.mobilebridge.Mobilebridge");
      Object result;
      if ("anytls".equals(node.type)) {
        result = bridge.getMethod("probeAnyTLSWithNetwork",
                String.class, long.class, String.class, String.class,
                String.class, String.class, boolean.class, String.class,
                String.class, networkType)
            .invoke(null, node.host, (long)node.port, node.sni, node.password,
                node.anyTLSFingerprint(), node.anyTLSALPN(),
                true, "", "", network);
      } else {
        result = bridge.getMethod("probeHysteria2WithNetwork",
                String.class, long.class, String.class, String.class,
                boolean.class, String.class, String.class, networkType)
            .invoke(null, node.host, (long)node.port, node.sni, node.password,
                true, "", "", network);
      }
      return Math.max(1L, ((Number)result).longValue());
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      throw new IllegalStateException("节点握手失败", cause == null ? e : cause);
    }
  }
  public static boolean stopping() { return CoreService.stopping || CoreService.nativeShutdownInProgress; }
  public static boolean fullyStopped() {
    return !CoreService.serviceAlive && !CoreService.stopping
        && !CoreService.nativeShutdownInProgress && !CoreService.tunnelEstablished
        && !Hysteria2Native.isRunning();
  }
  public static boolean serviceAlive() { return CoreService.serviceAlive; }
  public static String verifyConnection() throws Exception {
    if (CoreService.stopping) throw new IllegalStateException("正在断联，无法检测");
    if (!CoreService.tunnelEstablished) throw new IllegalStateException("Android VPN 尚未建立");
    if (!Hysteria2Native.isRunning()) throw new IllegalStateException("Making 节点会话已经中断");
    if (!CoreService.forwardingVerified) throw new IllegalStateException("节点转发尚未验证成功");
    LocalProxyProbe.Result result = LocalProxyProbe.check(CoreService.localHttpPassword);
    if (CoreService.stopping || !CoreService.tunnelEstablished || !Hysteria2Native.isRunning())
      throw new IllegalStateException("检测期间 VPN 已断开");
    return "连接正常 · VPN、Making 节点及双向 TCP 转发已通过 · "
        + result.elapsedMs + " ms · " + result.responseStatus;
  }
  public static boolean running() {
    return CoreService.tunnelEstablished && Hysteria2Native.isRunning();
  }
  public static boolean connecting() { return CoreService.connecting || stopping(); }
  public static String status() {
    if (stopping()) return CoreService.serviceAlive ? "正在断开 VPN…" : "Android VPN 已关闭，正在清理 Making 内核…";
    if (CoreService.lastFailure != null && !CoreService.lastFailure.isEmpty())
      return "连接失败：" + CoreService.lastFailure;
    if (CoreService.connecting) return "正在连接…";
    if (running()) return "已连接";
    return "尚未连接";
  }
  public static long up() {
    ProxyCore c=CoreService.core;
    return c == null ? 0 : c.uploaded();
  }
  public static long down() {
    ProxyCore c=CoreService.core;
    return c == null ? 0 : c.downloaded();
  }
  public static String diagnostics() { return CoreService.logs(); }
  public static String version() { return "Making · 0.3.2 连接修复版"; }
}
