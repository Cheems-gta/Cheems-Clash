#!/usr/bin/env python3
"""Pin-compatible Making Core failure/teardown guard.

Runs only against the pinned upstream Java service copied by android.yml.
Fails the build rather than silently applying to an unknown revision.
"""
from pathlib import Path

path = Path("app/src/main/java/win/cheems/core/CoreService.java")
source = path.read_text(encoding="utf-8")

def replace_once(before: str, after: str) -> None:
    global source
    count = source.count(before)
    if count != 1:
        raise SystemExit(f"Making service patch mismatch (wanted one match, found {count}): {before[:72]}")
    source = source.replace(before, after, 1)

replace_once(
    '  private volatile boolean destroyed;\n',
    '  private volatile boolean destroyed;\n'
    '  // Tracks a native handshake still in progress during service teardown.\n'
    '  private volatile boolean nativeStartInFlight;\n',
)
replace_once(
    '                  Hysteria2Native.start(node, Hysteria2Native.LOCAL_SOCKS_PORT,\n'
    '                      allowUnverifiedTLS, customCAPEM, this);\n',
    '                  nativeStartInFlight = true;\n'
    '                  try {\n'
    '                    Hysteria2Native.start(node, Hysteria2Native.LOCAL_SOCKS_PORT,\n'
    '                        allowUnverifiedTLS, customCAPEM, this);\n'
    '                  } finally {\n'
    '                    nativeStartInFlight = false;\n'
    '                  }\n',
)
replace_once(
    '                try { stopForeground(STOP_FOREGROUND_REMOVE); }\n'
    '                catch (RuntimeException ignored) {}\n'
    '                // stopSelfResult(id) can refuse to stop after a duplicate start\n'
    '                // request, leaving an indefinite foreground VPN notification.\n'
    '                stopSelf();\n',
    '                // Let onDestroy own foreground and native cleanup exactly once.\n'
    '                // Do not remove the foreground notification from this worker thread.\n'
    '                stopSelf();\n',
)
replace_once(
    '    if (ownedCore != null) ownedCore.stop();\n'
    '    try { if(Hysteria2Native.isPackaged()) Hysteria2Native.stopTunnel(); }\n'
    '    catch(Exception e) { log("TUN关闭异常"); }\n',
    '    try { if (ownedCore != null) ownedCore.stop(); }\n'
    '    catch (RuntimeException e) { log("本地代理关闭异常：" + e.getClass().getSimpleName()); }\n'
    '    // A timed-out handshake already rolled back native state in the Go bridge.\n'
    '    // Avoid a redundant native stop call after such a failure.\n'
    '    boolean nativeCleanupNeeded = nativeStartInFlight || tunnelEstablished\n'
    '        || Hysteria2Native.isRunning();\n',
)
replace_once(
    '    try {\n'
    '      if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n'
    '    } catch (Exception e) {\n'
    '      log("原生连接关闭异常");\n'
    '    }\n'
    '    finishStage(lastFailure == null ? "用户或系统已停止 VPN" : "启动或连接失败，服务已关闭");\n'
    '    stopForeground(STOP_FOREGROUND_REMOVE);\n'
    '    super.onDestroy();\n',
    '    try {\n'
    '      // Go Stop already calls StopTunnel; do not double-stop the TUN.\n'
    '      if (nativeCleanupNeeded && Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n'
    '    } catch (Exception | LinkageError e) {\n'
    '      log("原生连接关闭异常：" + e.getClass().getSimpleName());\n'
    '    }\n'
    '    try {\n'
    '      finishStage(lastFailure == null ? "用户或系统已停止 VPN" : "启动或连接失败，服务已关闭");\n'
    '    } catch (RuntimeException ignored) {}\n'
    '    try { stopForeground(STOP_FOREGROUND_REMOVE); }\n'
    '    catch (RuntimeException ignored) {}\n'
    '    super.onDestroy();\n',
)

path.write_text(source, encoding="utf-8")
print("Making Core timeout/teardown guard applied")


# Disconnect handling: run native Stop off Android main thread; let stopSelf()
# execute only after native shutdown. Neither the UI nor onDestroy may block on
# an otherwise healthy native tunnel teardown.
replace_once(
    '  private static final String ACTION_STOP_VPN = "win.cheems.core.STOP_VPN";\n',
    '  public static final String ACTION_STOP_VPN = "win.cheems.core.STOP_VPN";\n',
)
replace_once(
    '  private volatile boolean nativeStartInFlight;\n',
    '  private volatile boolean nativeStartInFlight;\n'
    '  static volatile boolean serviceAlive;\n'
    '  static volatile boolean stopping;\n'
    '  private volatile boolean nativeStopped;\n'
    '  private final AtomicBoolean stopRequested = new AtomicBoolean();\n',
)
replace_once(
    '    destroyed = false;\n    tunnelEstablished = false;\n',
    '    destroyed = false;\n    serviceAlive = true;\n    stopping = false;\n'
    '    nativeStopped = false;\n    tunnelEstablished = false;\n',
)
replace_once(
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      log("用户通过通知栏请求停止 VPN");\n'
    '      try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) {}\n'
    '      stopSelf();\n'
    '      return START_NOT_STICKY;\n'
    '    }\n',
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      if (stopRequested.compareAndSet(false, true)) {\n'
    '        stopping = true;\n'
    '        connecting = false;\n'
    '        lastFailure = null;\n'
    '        log("收到断联请求，后台关闭 Making 内核和 Android VPN");\n'
    '        Thread shutdown = new Thread(() -> {\n'
    '          try {\n'
    '            if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n'
    '            log("Making 原生停止指令已执行");\n'
    '          } catch (Exception | LinkageError e) {\n'
    '            log("Making 断联异常：" + e.getClass().getSimpleName());\n'
    '          } finally {\n'
    '            nativeStopped = true;\n'
    '            stopSelf();\n'
    '          }\n'
    '        }, "making-disconnect");\n'
    '        shutdown.setDaemon(true);\n'
    '        shutdown.start();\n'
    '      }\n'
    '      return START_NOT_STICKY;\n'
    '    }\n'
    '    if (stopping) return START_NOT_STICKY;\n',
)
replace_once(
    '    if (destroyed) return;\n    startupStage = stage;\n',
    '    if (destroyed || stopping) return;\n    startupStage = stage;\n',
)
replace_once(
    '                if (destroyed) return;\n                boolean nativeNode =',
    '                if (destroyed || stopping) return;\n                boolean nativeNode =',
)
replace_once(
    '                if (destroyed) {\n'
    '                  if (nativeNode) Hysteria2Native.stop();\n'
    '                  return;\n'
    '                }\n',
    '                if (destroyed || stopping) {\n'
    '                  if (nativeNode && !stopping) Hysteria2Native.stop();\n'
    '                  return;\n'
    '                }\n',
)
# Ensure a canceled native handshake cannot proceed to local proxy or TUN.
source = source.replace(
    'if (destroyed || core != ownedCore) return;',
    'if (destroyed || stopping || core != ownedCore) return;'
)
replace_once(
    '                updateConnecting(false);\n'
    '                finishStage(nativeNode ? "VPN 接口与节点转发均已启动" : "本地代理运行中");\n',
    '                if (stopping) return;\n'
    '                updateConnecting(false);\n'
    '                finishStage(nativeNode ? "VPN 接口与节点转发均已启动" : "本地代理运行中");\n',
)
replace_once(
    '              } catch (Exception | LinkageError e) {\n'
    '                updateConnecting(false);\n',
    '              } catch (Exception | LinkageError e) {\n'
    '                if (stopping) {\n'
    '                  log("连接已由用户取消");\n'
    '                  return;\n'
    '                }\n'
    '                updateConnecting(false);\n',
)
replace_once(
    '    boolean nativeCleanupNeeded = nativeStartInFlight || tunnelEstablished\n'
    '        || Hysteria2Native.isRunning();\n',
    '    boolean nativeCleanupNeeded = !nativeStopped && !stopping &&\n'
    '        (nativeStartInFlight || tunnelEstablished || Hysteria2Native.isRunning());\n',
)
replace_once(
    '    super.onDestroy();\n  }\n\n  @Override\n  public void onRevoke()',
    '    serviceAlive = false;\n'
    '    stopping = false;\n'
    '    super.onDestroy();\n'
    '  }\n\n  @Override\n  public void onRevoke()',
)
path.write_text(source, encoding="utf-8")
print("Making connection cancellation and async disconnect patch applied")

replace_once(
    '      if (destroyed || core != ownedCore)\n'
    '        throw new IOException("VPN 启动期间服务已停止");\n',
    '      if (destroyed || stopping || core != ownedCore)\n'
    '        throw new IOException("VPN 启动期间服务已停止");\n',
)
replace_once(
    '          while (!destroyed && core == ownedCore) {\n',
    '          while (!destroyed && !stopping && core == ownedCore) {\n',
)
path.write_text(source, encoding="utf-8")


# Disconnect v2: Stop must not wait for the native Go tunnel teardown before
# Android receives its stopSelf / VPN descriptor close. The old ordering waited
# for a possibly blocked StopTunnel() before stopSelf(), so the button appeared
# inert and Android could keep displaying an active VPN.
replace_once(
    '  static volatile boolean stopping;\n',
    '  static volatile boolean stopping;\n'
    '  static volatile boolean nativeShutdownInProgress;\n',
)
old_stop = (
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      if (stopRequested.compareAndSet(false, true)) {\n'
    '        stopping = true;\n'
    '        connecting = false;\n'
    '        lastFailure = null;\n'
    '        log("收到断联请求，后台关闭 Making 内核和 Android VPN");\n'
    '        Thread shutdown = new Thread(() -> {\n'
    '          try {\n'
    '            if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n'
    '            log("Making 原生停止指令已执行");\n'
    '          } catch (Exception | LinkageError e) {\n'
    '            log("Making 断联异常：" + e.getClass().getSimpleName());\n'
    '          } finally {\n'
    '            nativeStopped = true;\n'
    '            stopSelf();\n'
    '          }\n'
    '        }, "making-disconnect");\n'
    '        shutdown.setDaemon(true);\n'
    '        shutdown.start();\n'
    '      }\n'
    '      return START_NOT_STICKY;\n'
    '    }\n'
)
new_stop = (
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      if (stopRequested.compareAndSet(false, true)) {\n'
    '        stopping = true;\n'
    '        connecting = false;\n'
    '        lastFailure = null;\n'
    '        log("收到断联请求：先关闭 Android VPN，再后台清理 Making 内核");\n'
    '        // Never await Hysteria2Native.stop() here: StopTunnel can block.\n'
    '        // Android immediately runs onDestroy() and releases the VPN FD.\n'
    '        stopSelf();\n'
    '      }\n'
    '      return START_NOT_STICKY;\n'
    '    }\n'
)
replace_once(old_stop, new_stop)

start_marker = '  @Override\n  public void onDestroy() {\n'
end_marker = '  @Override\n  public void onRevoke() {\n'
if source.count(start_marker) != 1 or source.count(end_marker) != 1:
    raise SystemExit("Unable to identify one onDestroy block")
begin = source.index(start_marker)
end = source.index(end_marker, begin)
new_destroy = '''  @Override
  public void onDestroy() {
    // Service teardown runs on the Android main thread. It MUST NOT wait for
    // gomobile StopTunnel / Go network workers, which can block on active flows.
    destroyed = true;
    stopping = true;
    connecting = false;
    Thread watcher = connectionMonitor;
    if (watcher != null) watcher.interrupt();

    // Release Android's VPN endpoint and foreground notification first.
    // This is independent of the speed of the native engine cleanup.
    ParcelFileDescriptor descriptor = vpnInterface;
    vpnInterface = null;
    if (descriptor != null) {
      try { descriptor.close(); }
      catch (IOException e) { log("Android VPN 关闭接口异常"); }
    }
    if (core == ownedCore) {
      tunnelEstablished = false;
      forwardingVerified = false;
      core = null;
    }
    if (ownedHttpPassword != null && ownedHttpPassword.equals(localHttpPassword))
      localHttpPassword = null;

    try { stopForeground(STOP_FOREGROUND_REMOVE); }
    catch (RuntimeException ignored) {}
    try {
      finishStage(lastFailure == null ? "VPN 接口已关闭，正在清理原生内核"
          : "连接失败，VPN 接口已关闭，正在清理内核");
    } catch (RuntimeException ignored) {}
    serviceAlive = false;

    final ProxyCore retiringCore = ownedCore;
    nativeShutdownInProgress = true;
    Thread cleanup = new Thread(() -> {
      try {
        if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();
        log("Making 原生内核已停止");
      } catch (Exception | LinkageError e) {
        log("原生停止失败：" + e.getClass().getSimpleName());
      } finally {
        try {
          if (retiringCore != null) retiringCore.stop();
        } catch (RuntimeException e) {
          log("本地代理清理异常：" + e.getClass().getSimpleName());
        }
        nativeShutdownInProgress = false;
        stopping = false;
        log("VPN 服务和 Making 内核清理流程结束");
        try { finishStage("VPN 服务已停止"); }
        catch (RuntimeException ignored) {}
      }
    }, "making-native-teardown");
    cleanup.setDaemon(true);
    cleanup.start();

    super.onDestroy();
  }

'''
source = source[:begin] + new_destroy + source[end:]
# Guard JNI startup after a stop was requested, including the race where the
# worker passed its first destroyed check and native handshake has not begun.
replace_once(
    '                  nativeStartInFlight = true;\n'
    '                  try {\n',
    '                  if (destroyed || stopping) return;\n'
    '                  nativeStartInFlight = true;\n'
    '                  try {\n',
)
path.write_text(source, encoding="utf-8")
print("Making disconnect v2: Android VPN released before asynchronous Go teardown")


# The Go TUN shutdown must first close the device to release blocked readers,
# then await stack cleanup. This preserves startup behavior and native routing.
native_path = Path("core-upstream/native/quic/mobilebridge/vpn_tunnel.go")
native_source = native_path.read_text(encoding="utf-8")
native_before = (
    '\tif stack != nil {\n'
    '\t\t_ = stack.Close()\n'
    '\t}\n'
    '\tif device != nil {\n'
    '\t\t_ = device.Close()\n'
    '\t}\n'
)
native_after = (
    '\t// Close the TUN device first so packet readers wake before stack shutdown.\n'
    '\tif device != nil {\n'
    '\t\t_ = device.Close()\n'
    '\t}\n'
    '\tif stack != nil {\n'
    '\t\t_ = stack.Close()\n'
    '\t}\n'
)
if native_source.count(native_before) != 1:
    raise SystemExit("Pinned Go TUN StopTunnel shutdown order has changed")
native_path.write_text(native_source.replace(native_before, native_after, 1),
                       encoding="utf-8")
print("Making TUN teardown: close device before waiting for stack")


# v3: direct Android stopService releases the VPN FD without restarting the
# foreground service, and an active service reference supports protected QUIC
# latency probes without changing the selected/active node.
replace_once(
    '  static volatile boolean nativeShutdownInProgress;\n',
    '  static volatile boolean nativeShutdownInProgress;\n'
    '  static volatile CoreService activeInstance;\n',
)
replace_once(
    '    destroyed = false;\n    serviceAlive = true;\n',
    '    destroyed = false;\n    activeInstance = this;\n    serviceAlive = true;\n',
)
replace_once(
    '    destroyed = true;\n    stopping = true;\n',
    '    destroyed = true;\n    if (activeInstance == this) activeInstance = null;\n'
    '    stopping = true;\n',
)
path.write_text(source, encoding="utf-8")

# Never leave Go IsRunning=true when TUN cleanup is slow: invalidate the
# session and cancel dialing *before* awaiting TUN teardown. TUN device is
# closed immediately and its stack teardown gets a bounded wait.
bridge_path = Path("core-upstream/native/quic/mobilebridge/bridge.go")
go = bridge_path.read_text(encoding="utf-8")
old = "func Stop() {\n\tStopTunnel()\n\tmu.Lock()"
if go.count(old) != 1:
    raise SystemExit("Go Stop ordering no longer matches pinned source")
go = go.replace(old, "func Stop() {\n\tmu.Lock()", 1)
old = "\tif cancel != nil {\n\t\tcancel()\n\t}\n\tif oldServer != nil {"
if go.count(old) != 1:
    raise SystemExit("Go Stop cancellation block no longer matches")
go = go.replace(old,
    "\tif cancel != nil {\n\t\tcancel()\n\t}\n\tStopTunnel()\n\tif oldServer != nil {", 1)

# This is a separate, authenticated Hysteria2 QUIC session. It does not
# mutate the currently selected node or the global VPN backend.
go += """

// ProbeHysteria2WithNetwork times a complete authenticated QUIC handshake.
// Physical network protection is required to avoid VPN routing loops.
func ProbeHysteria2WithNetwork(host string, port int, sni string,
    password string, allowUnverifiedTLS bool, customCAPEM string,
    certificateSHA256 string, network Network) (int64, error) {
    if network == nil || host == "" || password == "" || port < 1 || port > 65535 {
        return 0, errors.New("invalid protected Hysteria2 probe target")
    }
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    started := time.Now()
    candidate, err := quictransport.DialHysteria2(ctx, quictransport.Hysteria2Config{
        Address: net.JoinHostPort(host, strconv.Itoa(port)),
        ServerName: sni,
        Password: password,
        Timeout: 5*time.Second,
        AllowUnverifiedTLS: allowUnverifiedTLS,
        TrustedCAPEM: customCAPEM,
        CertificateSHA256: certificateSHA256,
        Network: network,
    })
    if err != nil {
        return 0, err
    }
    elapsed := time.Since(started).Milliseconds()
    _ = candidate.Close()
    if elapsed < 1 {
        elapsed = 1
    }
    return elapsed, nil
}
"""
bridge_path.write_text(go, encoding="utf-8")

# The pinned sing-tun shutdown occasionally waits for a lingering flow.
# The Android descriptor is already closed; never block JNI forever on
# stack.Close, and let its cleanup goroutine complete when it can.
native_path = Path("core-upstream/native/quic/mobilebridge/vpn_tunnel.go")
native_source = native_path.read_text(encoding="utf-8")
old = "\tif device != nil {\n\t\t_ = device.Close()\n\t}\n\tif stack != nil {\n\t\t_ = stack.Close()\n\t}\n}\n"
new = "\tif device != nil {\n\t\t_ = device.Close()\n\t}\n\tif stack != nil {\n\t\tdone := make(chan struct{})\n\t\tgo func() { defer close(done); _ = stack.Close() }()\n\t\tselect {\n\t\tcase <-done:\n\t\tcase <-time.After(2500 * time.Millisecond):\n\t\t}\n\t}\n}\n"
if native_source.count(old) != 1:
    raise SystemExit("Go StopTunnel bounded shutdown block mismatch")
native_path.write_text(native_source.replace(old, new, 1), encoding="utf-8")
print("Making stopService + bounded native teardown + HY2 RTT probe enabled")


# Immediate on-demand VPN descriptor release.
# Android's system may hold a binding to VpnService, so stopService() alone
# need not invoke onDestroy() promptly. Stop requests must close the live
# ParcelFileDescriptor directly, then retire the Go session asynchronously.
old_stop = (
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      if (stopRequested.compareAndSet(false, true)) {\n'
    '        stopping = true;\n'
    '        connecting = false;\n'
    '        lastFailure = null;\n'
    '        log("收到断联请求：先关闭 Android VPN，再后台清理 Making 内核");\n'
    '        // Never await Hysteria2Native.stop() here: StopTunnel can block.\n'
    '        // Android immediately runs onDestroy() and releases the VPN FD.\n'
    '        stopSelf();\n'
    '      }\n'
    '      return START_NOT_STICKY;\n'
    '    }\n'
)
new_stop = (
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      requestImmediateDisconnect();\n'
    '      return START_NOT_STICKY;\n'
    '    }\n'
)
replace_once(old_stop, new_stop)

start_marker = '  @Override\n  public void onDestroy() {\n'
end_marker = '  @Override\n  public void onRevoke() {\n'
if source.count(start_marker) != 1 or source.count(end_marker) != 1:
    raise SystemExit("Cannot safely replace onDestroy after Making patches")
begin, end = source.index(start_marker), source.index(end_marker)
shutdown_code = '''  /**
   * Directly tears down the live VPN interface. A bound VpnService may survive
   * stopService() for some time, so onDestroy() cannot be the only teardown path.
   */
  public void requestImmediateDisconnect() {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      releaseVpnNow();
      stopSelf();
    } else {
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        releaseVpnNow();
        stopSelf();
      });
    }
  }

  private void releaseVpnNow() {
    // Idempotent across notification button, app button, onDestroy, and revoke.
    if (!stopRequested.compareAndSet(false, true)) return;
    destroyed = true;
    stopping = true;
    connecting = false;
    nativeShutdownInProgress = true;
    Thread watcher = connectionMonitor;
    if (watcher != null) watcher.interrupt();

    // Release OS VPN descriptor before any Go, proxy, or database operation.
    ParcelFileDescriptor descriptor = vpnInterface;
    vpnInterface = null;
    if (descriptor != null) {
      try { descriptor.close(); }
      catch (IOException e) { log("Android VPN 接口关闭异常"); }
    }
    if (core == ownedCore) {
      tunnelEstablished = false;
      forwardingVerified = false;
      core = null;
    }
    if (ownedHttpPassword != null && ownedHttpPassword.equals(localHttpPassword))
      localHttpPassword = null;

    try { stopForeground(STOP_FOREGROUND_REMOVE); }
    catch (RuntimeException ignored) {}
    serviceAlive = false;
    if (activeInstance == this) activeInstance = null;
    final ProxyCore retiringCore = ownedCore;
    Thread cleanup = new Thread(() -> {
      try {
        if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();
        log("Making 原生会话已关闭");
      } catch (Exception | LinkageError e) {
        log("原生内核清理失败：" + e.getClass().getSimpleName());
      } finally {
        try {
          if (retiringCore != null) retiringCore.stop();
        } catch (RuntimeException e) {
          log("本地代理清理失败：" + e.getClass().getSimpleName());
        } finally {
          nativeShutdownInProgress = false;
          stopping = false;
          try { finishStage("VPN 已断开"); }
          catch (RuntimeException ignored) {}
          log("VPN 清理流程结束");
        }
      }
    }, "making-release-after-fd-close");
    cleanup.setDaemon(true);
    cleanup.start();
  }

  @Override
  public void onDestroy() {
    releaseVpnNow();
    super.onDestroy();
  }

'''
source = source[:begin] + shutdown_code + source[end:]
path.write_text(source, encoding="utf-8")
print("Making: direct live TUN closure independent of bound service onDestroy")


# AnyTLS protocol latency is the time to establish a separate protected TLS
# session and authenticated AnyTLS outbound stream. NewAnyTLSClient alone only
# constructs a lazy client and therefore MUST NOT be reported as latency.
bridge_path = Path("core-upstream/native/quic/mobilebridge/bridge.go")
go = bridge_path.read_text(encoding="utf-8")
go += """
// ProbeAnyTLSWithNetwork measures an authenticated AnyTLS proxy stream, not
// merely a TCP SYN or the lazy NewAnyTLSClient constructor. It never touches
// global VPN sessions. The probe runs on a protected physical network.
func ProbeAnyTLSWithNetwork(host string, port int, sni string,
    password string, fingerprint string, alpn string,
    allowUnverifiedTLS bool, customCAPEM string,
    certificateSHA256 string, network Network) (int64, error) {
    if network == nil || host == "" || password == "" || port < 1 || port > 65535 {
        return 0, errors.New("invalid protected AnyTLS probe target")
    }
    ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
    defer cancel()
    var protocols []string
    if alpn != "" {
        protocols = strings.Split(alpn, ",")
    }
    started := time.Now()
    candidate, err := quictransport.NewAnyTLSClient(ctx, quictransport.AnyTLSConfig{
        Address: net.JoinHostPort(host, strconv.Itoa(port)),
        ServerName: sni,
        Password: password,
        Fingerprint: fingerprint,
        ALPN: protocols,
        Timeout: 6*time.Second,
        AllowUnverifiedTLS: allowUnverifiedTLS,
        TrustedCAPEM: customCAPEM,
        CertificateSHA256: certificateSHA256,
        Network: network,
    })
    if err != nil {
        return 0, err
    }
    defer candidate.Close()
    // The client is lazy. Force an actual authenticated outbound stream.
    stream, err := candidate.OpenTCP(ctx, "example.com", 80)
    if err != nil {
        return 0, err
    }
    elapsed := time.Since(started).Milliseconds()
    _ = stream.Close()
    if elapsed < 1 {
        elapsed = 1
    }
    return elapsed, nil
}
"""
bridge_path.write_text(go, encoding="utf-8")
print("AnyTLS authenticated stream latency probe added")
