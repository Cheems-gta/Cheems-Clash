package win.cheems.core;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
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
    if (CoreService.connecting || CoreService.tunnelEstablished || CoreService.stopping)
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
    if (CoreService.stopping) return;
    // Acknowledge the tap before Android dispatches the service command.
    // This flag is also the guard against a new connect while shutdown is in flight.
    CoreService.stopping = true;
    try {
      Intent request = new Intent(context, CoreService.class).setAction(CoreService.ACTION_STOP_VPN);
      if (context.startService(request) == null)
        throw new IllegalStateException("无法发送 VPN 断开指令");
    } catch (RuntimeException failure) {
      CoreService.stopping = false;
      throw failure;
    }
  }
  public static boolean stopping() { return CoreService.stopping; }
  public static boolean canDisconnect() { return CoreService.serviceAlive || CoreService.connecting || CoreService.tunnelEstablished || CoreService.stopping; }
  public static boolean running() {
    return CoreService.tunnelEstablished && Hysteria2Native.isRunning();
  }
  public static boolean connecting() { return CoreService.connecting || CoreService.stopping; }
  public static String status() {
    if (CoreService.stopping) return "正在断开 VPN…";
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
  public static String version() { return "Cheems Core · 0.3.0 集成版"; }
}
