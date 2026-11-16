package com.cheems.clash

import kotlinx.coroutines.flow.MutableStateFlow

/** UI state is refreshed from the standalone Cheems Core service, not Mihomo. */
object VpnState {
    val status = MutableStateFlow("尚未连接")
    val running = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val startupMetrics = MutableStateFlow("Cheems Core 原生内核")
}

object VpnDiagnostics {
    private const val KEY = "events"
    fun record(context: android.content.Context, event: String) {
        val prefs = context.getSharedPreferences("vpn_diagnostics", 0)
        val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = prefs.getString(KEY, "") ?: ""
        val lines = (old.lines().filter { it.isNotBlank() } + "$timestamp  $event").takeLast(35)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }
    fun history(context: android.content.Context): String =
        context.getSharedPreferences("vpn_diagnostics", 0).getString(KEY, "") ?: ""
    fun inspectPreviousExit(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences("vpn_diagnostics", 0)
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .firstOrNull() ?: return
        val key = "${last.timestamp}:${last.reason}"
        if (prefs.getString("last_exit", "") == key) return
        prefs.edit().putString("last_exit", key).apply()
        val reason = when (last.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH -> "Java 异常"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生内核崩溃"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统请求结束"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
            android.app.ApplicationExitInfo.REASON_ANR -> "应用无响应"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "进程收到终止信号"
            else -> "系统原因代码 ${last.reason}"
        }
        record(context, "上次进程退出：$reason")
    }
}

