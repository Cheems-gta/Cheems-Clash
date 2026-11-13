package com.cheems.clash

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
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
            .setSmallIcon(R.drawable.ic_stat_cheems)
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"断开",stop).build()).setOngoing(true).build())
        worker.execute {
            try {
                val config = File(filesDir, "profile.yaml")
                require(config.isFile) { "请先导入 YAML 配置或订阅" }
                val text = config.readText()
                check(!cancelled) { "连接已取消" }
                device = Builder().setSession("Cheems Clash").setMtu(1500)
                    .addAddress("172.19.0.1",30).addRoute("0.0.0.0",0).addDnsServer("172.19.0.2")
                    // Excluding our own sockets prevents provider downloads and bootstrap DNS loops.
                    .addDisallowedApplication(packageName).establish()
                    ?: error("VPN 授权已失效")
                Core.start(File(filesDir,"mihomo").absolutePath,text,device!!.fd.toLong(),protector)
                check(!cancelled) { "连接已取消" }
                VpnState.running.value = true
                VpnState.status.value = "已连接"
            } catch (e: Exception) {
                VpnState.error.value = e.message ?: "内核启动失败"
                VpnState.status.value = "连接失败"
                cleanup()
                stopSelf()
            } finally { VpnState.busy.value = false }
        }
        return START_NOT_STICKY
    }
    private fun requestStop() {
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
    override fun onRevoke() { requestStop(); super.onRevoke() }
    override fun onDestroy() {
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
