package com.cheems.clash

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import core.Core
import core.Protector
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors

object VpnState {
    val status = MutableStateFlow("尚未连接")
    val running = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val startupMetrics = MutableStateFlow("尚无连接耗时数据")
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

class ClashVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var device: ParcelFileDescriptor? = null
    @Volatile private var cancelled = false
    private val protector = object : Protector {
        override fun protect(fd: Long): Boolean = this@ClashVpnService.protect(fd.toInt())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { requestStop(); return START_NOT_STICKY }
        if (VpnState.busy.value || VpnState.running.value) return START_NOT_STICKY
        cancelled = false
        VpnDiagnostics.record(this, "收到 VPN 启动请求")
        val startupAt = SystemClock.elapsedRealtime()
        VpnState.busy.value = true
        VpnState.error.value = ""
        VpnState.status.value = "正在启动内核…"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", "VPN 连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ClashVpnService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(1) // Retire notifications posted by releases through v0.2.5.
        startForeground(2, Notification.Builder(this,"vpn").setContentTitle("Cheems Clash")
            .setContentText("VPN 服务运行中 · v0.2.7")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"断开",stop).build()).setOngoing(true).build())
        worker.execute {
            try {
                val workerAt = SystemClock.elapsedRealtime()
                val config = File(filesDir, "profile.yaml")
                require(config.isFile) { "请先导入 YAML 配置或订阅" }
                val text = config.readText()
                check(!cancelled) { "连接已取消" }
                val configAt = SystemClock.elapsedRealtime()
                device = Builder().setSession("Cheems Clash").setMtu(1500)
                    .addAddress("172.19.0.1",30).addRoute("0.0.0.0",0).addDnsServer("172.19.0.2")
                    // Excluding our own sockets prevents provider downloads and bootstrap DNS loops.
                    .addDisallowedApplication(packageName).establish()
                    ?: error("VPN 授权已失效")
                val tunAt = SystemClock.elapsedRealtime()
                Core.start(File(filesDir,"mihomo").absolutePath,text,device!!.fd.toLong(),protector)
                check(!cancelled) { "连接已取消" }
                val readyAt = SystemClock.elapsedRealtime()
                VpnState.startupMetrics.value = "总耗时 ${readyAt - startupAt} ms · 服务排队 ${workerAt - startupAt} ms · 配置读取 ${configAt - workerAt} ms · TUN ${tunAt - configAt} ms · Mihomo ${readyAt - tunAt} ms"
                android.util.Log.i("CheemsStartup", VpnState.startupMetrics.value)
                VpnState.running.value = true
                VpnState.status.value = "已连接"
                VpnDiagnostics.record(this, "VPN 启动成功：${readyAt - startupAt} ms")
            } catch (e: Exception) {
                VpnState.error.value = e.message ?: "内核启动失败"
                VpnDiagnostics.record(this, "VPN 启动失败：${e.javaClass.simpleName}")
                VpnState.status.value = "连接失败"
                cleanup()
                stopSelf()
            } finally { VpnState.busy.value = false }
        }
        return START_NOT_STICKY
    }
    private fun requestStop() {
        VpnDiagnostics.record(this, "收到断开请求")
        cancelled = true
        VpnState.busy.value = true
        VpnState.status.value = "正在断开…"
        worker.execute {
            cleanup()
            VpnState.status.value = "尚未连接"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            VpnState.busy.value = false
        }
    }
    private fun cleanup() {
        runCatching { Core.stop() }
        runCatching { device?.close() }; device = null
        VpnState.running.value = false
    }
    override fun onRevoke() { VpnDiagnostics.record(this, "VPN 授权被系统撤销"); requestStop(); super.onRevoke() }
    override fun onDestroy() {
        VpnDiagnostics.record(this, "VPN 服务 onDestroy")
        cancelled = true
        VpnState.busy.value = true
        worker.execute {
            cleanup()
            if (VpnState.error.value.isEmpty()) VpnState.status.value = "尚未连接"
            VpnState.busy.value = false
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
