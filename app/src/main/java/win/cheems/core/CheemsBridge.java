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
    if (CoreService.shuttingDown)
      throw new IllegalStateException("正在清理上一条 VPN 连接，请稍后重试");
    if (CoreService.connecting || CoreService.tunnelEstablished)
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

  public static boolean disconnect(Context context) {
    if (CoreService.shuttingDown) return true;
    CoreService.shuttingDown = true;
    try {
      CoreService.log("用户点击断开连接");
      boolean accepted = context.stopService(new Intent(context, CoreService.class));
      if (!accepted) {
        CoreService.shuttingDown = false;
        CoreService.log("停止请求未找到运行中的 VPN 服务");
      }
      return accepted;
    } catch (RuntimeException e) {
      CoreService.shuttingDown = false;
      CoreService.log("停止请求异常：" + e.getClass().getSimpleName());
      throw e;
    }
  }
  public static boolean stopping() { return CoreService.shuttingDown; }
  public static boolean running() {
    // Do not enter native Go getters while the cleanup worker is stopping Go.
    if (CoreService.shuttingDown) return false;
    return CoreService.tunnelEstablished && Hysteria2Native.isRunning();
  }
  public static boolean connecting() { return CoreService.connecting; }
  public static String status() {
    if (CoreService.shuttingDown) return "正在断开 VPN…";
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
