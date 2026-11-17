#!/usr/bin/env python3
"""Keep Android's UI and VPN service teardown responsive during native QUIC shutdown.

Run after patch_making_timeout.py. Fail closed if upstream service changed.
"""
from pathlib import Path

path = Path("app/src/main/java/win/cheems/core/CoreService.java")
source = path.read_text(encoding="utf-8")

def replace_once(before: str, after: str) -> None:
    global source
    n = source.count(before)
    if n != 1:
        raise SystemExit(f"Disconnect patch mismatch (expected one, got {n}): {before[:90]!r}")
    source = source.replace(before, after, 1)

# Track an attempted native session without querying Go from Android's main thread.
replace_once(
    "  private volatile boolean nativeStartInFlight;\n",
    "  private volatile boolean nativeStartInFlight;\n"
    "  private volatile boolean nativeStartAttempted;\n",
)
replace_once(
    "                  nativeStartInFlight = true;\n",
    "                  nativeStartAttempted = true;\n"
    "                  nativeStartInFlight = true;\n",
)

# Closing Java-owned TUN must not wait for sockets, the local proxy, or Go mutexes.
replace_once(
    """    try { if (ownedCore != null) ownedCore.stop(); }
    catch (RuntimeException e) { log("本地代理关闭异常：" + e.getClass().getSimpleName()); }
    // A timed-out handshake already rolled back native state in the Go bridge.
    // Avoid a redundant native stop call after such a failure.
    boolean nativeCleanupNeeded = nativeStartInFlight || tunnelEstablished
        || Hysteria2Native.isRunning();
""",
    """    // Release Android's VPN descriptor first; proxy/Go cleanup is asynchronous.
    // Do not call Hysteria2Native.isRunning() on Android's main thread.
""",
)
replace_once(
    """      try {
        log("开始异步释放原生 Making 内核");
        if (Hysteria2Native.isPackaged()) Hysteria2Native.stop();
        log("原生内核已关闭");
      } catch (Exception | LinkageError failure) {
        log("原生清理异常：" + failure.getClass().getSimpleName());
      } finally {
        nativeStopFinished = true;
        stopping = false;
      }
""",
    """      try {
        log("开始异步释放本地代理和 Making 内核");
        try {
          if (ownedCore != null) ownedCore.stop();
        } catch (RuntimeException failure) {
          log("本地代理清理异常：" + failure.getClass().getSimpleName());
        }
        try {
          if (nativeStartAttempted && Hysteria2Native.isPackaged()) Hysteria2Native.stop();
        } catch (Exception | LinkageError failure) {
          log("原生内核清理异常：" + failure.getClass().getSimpleName());
        }
        log("后台清理完成");
      } finally {
        nativeStopFinished = true;
        stopping = false;
      }
""",
)
replace_once(
    "      if (nativeCleanupNeeded && !nativeStopFinished) launchNativeCleanup();\n",
    "      launchNativeCleanup();\n",
)
path.write_text(source, encoding="utf-8")
print("Disconnect nonblocking teardown patch applied")
