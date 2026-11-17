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


# Keep Android onDestroy responsive: native/Go teardown can wait on active sockets.
replace_once(
    "  static volatile boolean connecting;\n",
    "  static volatile boolean connecting;\n  static volatile boolean shuttingDown;\n",
)
start = source.index("  @Override\n  public void onDestroy() {\n")
end = source.index("  @Override\n  public void onRevoke()", start)
if end <= start: raise SystemExit("Cannot identify VPN onDestroy lifecycle block")
source = source[:start] + "  @Override\n  public void onDestroy() {\n    destroyed = true;\n    shuttingDown = true;\n    Thread watcher = connectionMonitor;\n    if (watcher != null) watcher.interrupt();\n    updateConnecting(false);\n    final ProxyCore oldProxy = ownedCore;\n    final ParcelFileDescriptor oldInterface = vpnInterface;\n    vpnInterface = null;\n    final boolean hadTunnel = tunnelEstablished;\n    final boolean hadHandshake = nativeStartInFlight;\n    if (core == ownedCore) {\n      tunnelEstablished = false;\n      forwardingVerified = false;\n      core = null;\n    }\n    if (ownedHttpPassword != null && ownedHttpPassword.equals(localHttpPassword))\n      localHttpPassword = null;\n    // Never call Go StopTunnel/Stop from Android's main service thread.\n    // Keep new connections blocked until all native resources are released.\n    Thread cleanup = new Thread(() -> {\n      try {\n        log(\"正在后台关闭本地代理\");\n        try { if (oldProxy != null) oldProxy.stop(); }\n        catch (RuntimeException e) { log(\"本地代理关闭异常：\" + e.getClass().getSimpleName()); }\n        try {\n          if (hadHandshake || hadTunnel || Hysteria2Native.isRunning()) {\n            if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n          }\n        } catch (Exception | LinkageError e) {\n          log(\"原生连接关闭异常：\" + e.getClass().getSimpleName());\n        }\n      } finally {\n        if (oldInterface != null) {\n          try { oldInterface.close(); } catch (IOException ignored) {}\n        }\n        try {\n          finishStage(lastFailure == null ? \"用户或系统已停止 VPN\" : \"启动或连接失败，服务已关闭\");\n          log(\"VPN 已停止，原生清理完成\");\n        } catch (RuntimeException ignored) {}\n        shuttingDown = false;\n      }\n    }, \"making-vpn-cleanup\");\n    cleanup.start();\n    try { stopForeground(STOP_FOREGROUND_REMOVE); }\n    catch (RuntimeException ignored) {}\n    super.onDestroy();\n  }\n\n" + source[end:]

path.write_text(source, encoding="utf-8")
print("Making Core timeout/teardown guard applied")
