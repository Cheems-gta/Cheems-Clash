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
