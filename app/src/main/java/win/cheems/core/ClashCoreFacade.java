package win.cheems.core;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import win.cheems.core.net.Node;
import win.cheems.core.net.Subscription;
import win.cheems.core.net.Hysteria2Native;

/**
 * UI adapter for the standalone Cheems Core. No Mihomo packages are loaded.
 * A Clash YAML subscription is used as a list of native endpoints, not as a
 * Mihomo rules/groups configuration. Credentials never enter diagnostics.
 */
public final class ClashCoreFacade {
  private static volatile List<Node> nodes = Collections.emptyList();
  private static volatile int chosen = 0;
  private ClashCoreFacade() {}

  public static synchronized void reload(Context context) throws Exception {
    File profile = new File(context.getFilesDir(), "profile.yaml");
    if (!profile.isFile()) {
      nodes = Collections.emptyList();
      chosen = 0;
      return;
    }
    byte[] bytes = Files.readAllBytes(profile.toPath());
    if (bytes.length > Subscription.MAX_BYTES)
      throw new IllegalArgumentException("订阅超过独立内核 2 MB 的限制");
    List<Node> parsed = new ArrayList<>(Subscription.parse(new String(bytes, StandardCharsets.UTF_8)));
    if (parsed.stream().noneMatch(Node::supported))
      throw new IllegalArgumentException("订阅中没有独立内核支持的节点");
    nodes = Collections.unmodifiableList(parsed);
    int index = context.getSharedPreferences("native_nodes", Context.MODE_PRIVATE).getInt("selected", 0);
    chosen = index >= 0 && index < parsed.size() && parsed.get(index).supported()
        ? index : firstSupported(parsed);
  }

  private static int firstSupported(List<Node> candidates) {
    for (int i = 0; i < candidates.size(); i++)
      if (candidates.get(i).supported()) return i;
    return 0;
  }

  private static void ensureLoaded(Context context) throws Exception {
    if (nodes.isEmpty()) reload(context);
  }

  public static synchronized String proxies(Context context) throws Exception {
    ensureLoaded(context);
    JSONArray available = new JSONArray();
    for (Node node : nodes) if (node.supported()) available.put(node.name);
    JSONObject group = new JSONObject();
    group.put("type", "Selector");
    group.put("now", nodes.isEmpty() ? "" : nodes.get(chosen).name);
    group.put("all", available);
    return new JSONObject().put("Cheems Core", group).toString();
  }

  public static synchronized void select(Context context, String name) throws Exception {
    if (CoreService.connecting || CoreService.tunnelEstablished)
      throw new IllegalStateException("请先断开 VPN，再切换节点");
    ensureLoaded(context);
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).supported() && nodes.get(i).name.equals(name)) {
        chosen = i;
        context.getSharedPreferences("native_nodes", Context.MODE_PRIVATE)
            .edit().putInt("selected", i).apply();
        return;
      }
    }
    throw new IllegalArgumentException("所选节点无效或独立内核暂不支持该协议");
  }

  public static synchronized void start(Context context) throws Exception {
    ensureLoaded(context);
    if (nodes.isEmpty()) throw new IllegalStateException("请先导入订阅");
    Node node = nodes.get(chosen);
    if (!node.supported()) throw new IllegalStateException("此节点尚不支持：" + node.unsupported);
    if (!Hysteria2Native.isPackaged()) throw new IllegalStateException("APK 中缺少 Cheems Core 原生库");
    if (CoreService.connecting || CoreService.tunnelEstablished)
      throw new IllegalStateException("VPN 已启动或正在连接");
    CoreService.selected = node;
    CoreService.lastFailure = null;
    CoreService.initializeJournal(context);
    Intent service = new Intent(context, CoreService.class);
    // Only provider-flagged nodes bypass CA validation by default. An explicit
    // user compatibility toggle can also request this for a selected endpoint.
    boolean insecure = node.hasInsecureTlsFlag()
        || context.getSharedPreferences("native_nodes", Context.MODE_PRIVATE)
            .getBoolean("allow_unverified_tls", false);
    service.putExtra("hy2.allowUnverifiedTls", insecure);
    service.putExtra("hy2.trustedCaPem", "");
    context.startForegroundService(service);
  }

  public static void stop(Context context) {
    context.stopService(new Intent(context, CoreService.class));
  }

  public static boolean isRunning() {
    return CoreService.tunnelEstablished && CoreService.forwardingVerified && Hysteria2Native.isRunning();
  }

  public static boolean isConnecting() {
    return CoreService.connecting;
  }

  public static String status() {
    if (isRunning()) return "已连接 · Cheems Core";
    if (CoreService.connecting) return "Cheems Core 正在连接…";
    return CoreService.lastFailure == null ? "尚未连接" : "连接失败";
  }

  public static String error() {
    return CoreService.lastFailure == null ? "" : CoreService.lastFailure;
  }

  public static String traffic() {
    JSONObject result = new JSONObject();
    try {
      result.put("up", Hysteria2Native.tunnelUploaded());
      result.put("down", Hysteria2Native.tunnelDownloaded());
    } catch (Exception ignored) {}
    return result.toString();
  }

  public static String diagnostics() {
    return CoreService.logs();
  }

  public static void setInsecureTls(Context context, boolean checked) {
    context.getSharedPreferences("native_nodes", Context.MODE_PRIVATE)
        .edit().putBoolean("allow_unverified_tls", checked).apply();
  }

  public static boolean insecureTls(Context context) {
    return context.getSharedPreferences("native_nodes", Context.MODE_PRIVATE)
        .getBoolean("allow_unverified_tls", false);
  }

  public static String version() { return "Cheems Core (原生)"; }
}
