package win.cheems.core;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Looper;

/**
 * Records only crash category, thread and in-app method name.
 * No exception messages, stack traces, node identifiers or credentials are saved.
 * Native process aborts cannot be caught here; the VPN startup stage is
 * persisted separately before entering the native tunnel.
 */
public final class CheemsApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    CoreService.initializeJournal(this);
    final Thread.UncaughtExceptionHandler previous =
        Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, cause) -> {
      try {
        String site = "unknown";
        for (StackTraceElement frame : cause.getStackTrace()) {
          if (frame.getClassName().startsWith("win.cheems.core.")) {
            site = frame.getClassName() + "#" + frame.getMethodName();
            break;
          }
        }
        SharedPreferences.Editor editor =
            getSharedPreferences("vpn_startup", MODE_PRIVATE).edit();
        editor.putBoolean("crash_pending", true);
        editor.putString("crash_type", cause.getClass().getSimpleName());
        editor.putString("crash_site", site);
        editor.putString("crash_thread",
            thread == Looper.getMainLooper().getThread() ? "main" : "worker");
        // Synchronous write: Android may terminate the process immediately.
        editor.commit();
      } catch (Throwable ignored) {
        // Never replace Android's original crash handler.
      }
      if (previous != null) {
        previous.uncaughtException(thread, cause);
      } else {
        android.os.Process.killProcess(android.os.Process.myPid());
      }
    });
  }
}

