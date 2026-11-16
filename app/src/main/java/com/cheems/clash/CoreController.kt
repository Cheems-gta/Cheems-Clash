package com.cheems.clash

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import win.cheems.core.CoreService
import win.cheems.core.net.Hysteria2Native
import win.cheems.core.net.Node
import win.cheems.core.net.Subscription
import java.io.File
import java.security.MessageDigest

/** The only UI-to-engine adapter. No alternate native engine or controller API. */
object CoreController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nodes = MutableStateFlow<List<Node>>(emptyList())
    val selected = MutableStateFlow("")
    val running = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("尚未连接")
    val error = MutableStateFlow("")
    val upload = MutableStateFlow(0L)
    val download = MutableStateFlow(0L)
    val connections = MutableStateFlow(0L)
    private var operation = false
    private var watcher: Job? = null
    fun key(node: Node): String = MessageDigest.getInstance("SHA-256")
        .digest("${node.name}\u0000${node.type}\u0000${node.host}\u0000${node.port}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun vpnCapable(node: Node) = node.supported() && node.type in
        setOf("hysteria2", "anytls", "vless", "vmess", "ss2022", "tuic", "socks5")
    fun reason(node: Node) = if (!node.supported()) node.unsupported else
        if (!vpnCapable(node)) "此协议目前仅有本地代理实现，尚未接入全局 VPN" else ""
    fun chosen(): Node? = nodes.value.firstOrNull { key(it) == selected.value }
    suspend fun reload(context: Context) {
        val imported = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "profile.yaml")
            if (file.isFile) Subscription.parse(file.readText()) else emptyList()
        }
        nodes.value = imported
        val previous = context.getSharedPreferences("profile", 0).getString("selected_core", "")
        selected.value = imported.firstOrNull { key(it) == previous && vpnCapable(it) }?.let(::key)
            ?: imported.firstOrNull(::vpnCapable)?.let(::key).orEmpty()
    }
    fun observe(context: Context) {
        if (watcher != null) return
        val app = context.applicationContext
        watcher = scope.launch {
            runCatching { reload(app) }.onFailure { error.value = "旧配置无法读取，请重新导入节点订阅" }
            var lastUp = 0L; var lastDown = 0L; var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val active = CoreService.tunnelEstablished
                running.value = active
                busy.value = operation || CoreService.connecting
                status.value = when {
                    operation -> "正在切换连接…"
                    CoreService.connecting -> "正在连接节点…"
                    active -> "已连接 · Cheems Core"
                    !CoreService.lastFailure.isNullOrBlank() -> "连接失败"
                    else -> "尚未连接"
                }
                error.value = CoreService.lastFailure.orEmpty()
                val totals = withContext(Dispatchers.IO) {
                    if (active) Triple(Hysteria2Native.tunnelUploaded(), Hysteria2Native.tunnelDownloaded(), Hysteria2Native.tunnelConnections())
                    else Triple(0L, 0L, 0L)
                }
                val now = SystemClock.elapsedRealtime(); val elapsed = (now - lastAt).coerceAtLeast(1)
                upload.value = (totals.first - lastUp).coerceAtLeast(0) * 1000 / elapsed
                download.value = (totals.second - lastDown).coerceAtLeast(0) * 1000 / elapsed
                connections.value = totals.third
                lastUp = totals.first; lastDown = totals.second; lastAt = now
                delay(700)
            }
        }
    }
    private suspend fun stopAndWait(context: Context) {
        if (CoreService.serviceAlive) {
            context.startService(Intent(context, CoreService::class.java).setAction(CoreService.ACTION_STOP_VPN))
        } else {
            context.stopService(Intent(context, CoreService::class.java))
        }
        withTimeout(30_000) { while (CoreService.serviceAlive) delay(100) }
    }
    fun connect(context: Context) {
        if (operation || CoreService.connecting || CoreService.tunnelEstablished) return
        val node = chosen() ?: run { error.value = "请先选择可用的 VPN 节点"; return }
        if (!vpnCapable(node)) { error.value = reason(node); return }
        operation = true; busy.value = true
        scope.launch {
            try {
                stopAndWait(context)
                CoreService.selected = node
                CoreService.lastFailure = null
                CoreService.connecting = true
                context.startForegroundService(Intent(context, CoreService::class.java)
                    .putExtra("hy2.allowUnverifiedTls", true))
            } catch (e: Exception) {
                CoreService.connecting = false
                CoreService.lastFailure = "无法启动 VPN：${e.javaClass.simpleName}"
            } finally { operation = false }
        }
    }
    fun disconnect(context: Context) {
        if (operation) return
        operation = true; busy.value = true
        scope.launch {
            try { stopAndWait(context); CoreService.lastFailure = null }
            catch (e: Exception) { CoreService.lastFailure = "停止服务超时，请重试" }
            finally { operation = false }
        }
    }
    fun select(context: Context, node: Node) {
        if (operation || CoreService.connecting || !vpnCapable(node)) return
        val restart = CoreService.tunnelEstablished
        operation = true; busy.value = true
        scope.launch {
            try {
                stopAndWait(context)
                selected.value = key(node)
                context.getSharedPreferences("profile", 0).edit().putString("selected_core", selected.value).apply()
                CoreService.selected = node
                CoreService.lastFailure = null
            } catch (e: Exception) { CoreService.lastFailure = "切换节点失败：${e.javaClass.simpleName}" }
            finally { operation = false }
            if (restart && CoreService.lastFailure == null) connect(context)
        }
    }
    suspend fun probe(): Long = withContext(Dispatchers.IO) { CoreService.verifyForwarding() }
}
