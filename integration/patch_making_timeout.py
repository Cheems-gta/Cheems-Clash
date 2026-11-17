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


# The in-app disconnect action must not run gomobile Stop() on Android's UI
# thread: it closes QUIC/TUN and may take noticeable time on a live connection.
replace_once(
    'import android.os.IBinder;\n',
    'import android.os.IBinder;\nimport android.os.Handler;\nimport android.os.Looper;\n',
)
replace_once(
    '  private static final String ACTION_STOP_VPN = "win.cheems.core.STOP_VPN";\n',
    '  static final String ACTION_STOP_VPN = "win.cheems.core.STOP_VPN";\n',
)
replace_once(
    '  static volatile boolean connecting;\n',
    '  static volatile boolean connecting;\n  static volatile boolean stopping;\n',
)
replace_once(
    '  // Tracks a native handshake still in progress during service teardown.\n',
    '  // Ensure native teardown runs at most once for an in-app disconnect.\n'
    '  private final AtomicBoolean stopRequested = new AtomicBoolean();\n'
    '  private volatile boolean nativeStopFinished;\n'
    '  // Tracks a native handshake still in progress during service teardown.\n',
)
replace_once(
    '          while (!destroyed && core == ownedCore) {\n',
    '          while (!destroyed && !stopping && core == ownedCore) {\n',
)
replace_once(
    '  @Override\n  public void onCreate() {\n',
    '''  // Foreground service stays alive while native cleanup runs off the main thread.
  // stopSelf() is posted only after native Stop has finished.
  private void requestStop() {
    if (!stopRequested.compareAndSet(false, true)) return;
    stopping = true;
    destroyed = true; // Prevent pending startup from creating a fresh TUN.
    connecting = false;
    lastFailure = null;
    log("用户请求断开 VPN，正在清理原生连接");
    new Thread(() -> {
      try {
        if (ownedCore != null) ownedCore.stop();
        if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();
        log("原生连接已关闭");
      } catch (Exception | LinkageError failure) {
        log("断开清理异常：" + failure.getClass().getSimpleName());
      } finally {
        nativeStopFinished = true;
        new Handler(Looper.getMainLooper()).post(this::stopSelf);
      }
    }, "cheems-vpn-stop").start();
  }

  @Override
  public void onCreate() {
''',
)
replace_once(
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      log("用户通过通知栏请求停止 VPN");\n'
    '      try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) {}\n'
    '      stopSelf();\n'
    '      return START_NOT_STICKY;\n'
    '    }\n',
    '    if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {\n'
    '      requestStop();\n'
    '      return START_NOT_STICKY;\n'
    '    }\n',
)
replace_once(
    '                if (destroyed) {\n'
    '                  if (nativeNode) Hysteria2Native.stop();\n'
    '                  return;\n'
    '                }\n',
    '                if (destroyed) {\n'
    '                  if (nativeNode && !stopRequested.get()) Hysteria2Native.stop();\n'
    '                  return;\n'
    '                }\n',
)
replace_once(
    '                if (destroyed) {\n'
    '                  ownedCore.stop();\n'
    '                  if (nativeNode) Hysteria2Native.stop();\n'
    '                }\n',
    '                if (destroyed && !stopRequested.get()) {\n'
    '                  ownedCore.stop();\n'
    '                  if (nativeNode) Hysteria2Native.stop();\n'
    '                }\n',
)
replace_once(
    '              } catch (Exception | LinkageError e) {\n'
    '                updateConnecting(false);\n',
    '              } catch (Exception | LinkageError e) {\n'
    '                updateConnecting(false);\n'
    '                if (stopRequested.get()) return;\n',
)
replace_once(
    '      if (nativeCleanupNeeded && Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n',
    '      if (!nativeStopFinished && nativeCleanupNeeded && Hysteria2Native.isPackaged()) Hysteria2Native.stop();\n',
)
replace_once(
    '    super.onDestroy();\n',
    '    super.onDestroy();\n    stopping = false;\n',
)

path.write_text(source, encoding="utf-8")
print("Making Core timeout/teardown guard applied")
