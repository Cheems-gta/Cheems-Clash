package com.cheems.clash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import win.cheems.core.CheemsBridge

private val Green = Color(0xFF73F5A2)
private val Background = Color(0xFF090E13)
private val Panel = Color(0xFF1B252B)
private const val MaxConfig = 2 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CheemsApp() }
    }
}

private data class NodeRow(val index: Int, val name: String, val protocol: String, val supported: Boolean, val reason: String)
private data class Download(val text: String, val usage: String?)

private fun parseNodes(text: String): List<NodeRow> {
    if (text.isBlank()) return emptyList()
    val data = JSONArray(CheemsBridge.listNodes(text))
    return (0 until data.length()).map { i ->
        val row = data.getJSONObject(i)
        NodeRow(row.getInt("index"), row.optString("name"), row.optString("protocol"),
            row.optBoolean("supported"), row.optString("reason"))
    }
}

private fun InputStream.limitedBytes(): ByteArray {
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= MaxConfig) { "订阅超过 2 MB，请精简配置" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun storeProfile(dir: File, text: String) {
    require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MaxConfig) { "配置为空或超过 2 MB" }
    require(parseNodes(text).isNotEmpty()) { "没有找到可识别的节点" }
    val temp = File(dir, "profile.tmp")
    temp.writeText(text, Charsets.UTF_8)
    val target = File(dir, "profile.yaml")
    if (!temp.renameTo(target)) {
        temp.delete()
        error("无法保存订阅配置")
    }
}

private fun parseUsage(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val values = header.split(';').mapNotNull { entry ->
        val pair = entry.trim().split('=', limit = 2)
        if (pair.size == 2) pair[1].toLongOrNull()?.let { pair[0].trim().lowercase(Locale.US) to it } else null
    }.toMap()
    val total = values["total"] ?: return null
    val used = (values["upload"] ?: 0L) + (values["download"] ?: 0L)
    fun gigabytes(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    val expires = values["expire"]?.takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(it * 1000L))
    } ?: "未知"
    return "已用 " + gigabytes(used) + " · 剩余 " + gigabytes((total - used).coerceAtLeast(0)) +
        "\n总量 " + gigabytes(total) + " · 到期 " + expires
}

private fun downloadProfile(address: String): Download {
    var url = URL(address.trim())
    repeat(6) {
        require(url.protocol == "https") { "订阅链接及跳转地址必须是 HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Cheems-Core/0.3")
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: error("订阅跳转缺少地址")
                url = URL(url, next)
            } else {
                require(code in 200..299) { "订阅服务器响应 HTTP " + code }
                val bytes = connection.inputStream.use { it.limitedBytes() }
                return Download(bytes.toString(Charsets.UTF_8), parseUsage(connection.getHeaderField("subscription-userinfo")))
            }
        } finally {
            connection.disconnect()
        }
    }
    error("订阅重定向次数过多")
}

private fun speed(bytes: Long): String = when {
    bytes >= 1048576 -> String.format(Locale.US, "%.1f MB/s", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytes / 1024.0)
    else -> bytes.toString() + " B/s"
}

private data class BridgeSnapshot(
    val status: String,
    val running: Boolean,
    val busy: Boolean,
    val up: Long,
    val down: Long
)

@Composable
fun CheemsApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("profile", 0) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf(runCatching { File(context.filesDir, "profile.yaml").takeIf { it.isFile }?.readText() ?: "" }.getOrDefault("")) }
    val nodes = remember(profile) { runCatching { parseNodes(profile) }.getOrDefault(emptyList()) }
    var selected by remember { mutableIntStateOf(prefs.getInt("core_selected_index", 0)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var autoRefresh by remember { mutableStateOf(prefs.getBoolean("auto_refresh", true)) }
    var refreshMinutes by remember { mutableStateOf(prefs.getInt("refresh_minutes", 60).toString()) }
    var usage by remember { mutableStateOf(prefs.getString("usage", null)) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未连接") }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf("") }
    var upSpeed by remember { mutableLongStateOf(0L) }
    var downSpeed by remember { mutableLongStateOf(0L) }
    val startupPrefs = remember { context.getSharedPreferences("vpn_startup", 0) }
    fun recentCrash(): String = if (startupPrefs.getBoolean("crash_pending", false)) {
        "最近一次进程异常：" + startupPrefs.getString("crash_type", "未知") +
            " · " + startupPrefs.getString("crash_thread", "未知线程") +
            " · " + startupPrefs.getString("crash_site", "unknown")
    } else "未记录到 Java 层崩溃；原生进程异常需要系统日志才能定位"
    var crashSummary by remember { mutableStateOf(recentCrash()) }

    fun connect() {
        runCatching {
            check(profile.isNotBlank()) { "请先导入节点" }
            CheemsBridge.connect(context, profile, selected)
            busy = true
            status = "正在连接…"
            message = "正在启动 Making 内核"
        }.onFailure { message = it.message ?: "无法启动连接"; busy = false }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) connect()
        else message = "未授予 VPN 权限"
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !running && !busy) scope.launch {
            importing = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.limitedBytes() }
                        ?: error("无法读取所选文件")
                    val result = data.toString(Charsets.UTF_8)
                    storeProfile(context.filesDir, result)
                    result
                }
                profile = text
                selected = 0
                prefs.edit().putInt("core_selected_index", 0).apply()
                message = "导入完成，已识别 " + nodes.size + " 个节点"
            } catch (e: Exception) { message = e.message ?: "导入失败" }
            finally { importing = false }
        }
    }

    LaunchedEffect(Unit) {
        var previousUp = 0L
        var previousDown = 0L
        while (true) {
            try {
                // A failed native start must not crash the Compose polling coroutine.
                // Binder/Go native status can block while QUIC shuts down; never poll it on the Compose main thread.
                val (nextStatus, nextRunning, nextBusy, currentUp, currentDown) =
                    withContext(Dispatchers.IO) {
                        BridgeSnapshot(
                            CheemsBridge.status(),
                            CheemsBridge.running(),
                            CheemsBridge.connecting(),
                            CheemsBridge.up(),
                            CheemsBridge.down()
                        )
                    }
                status = nextStatus
                running = nextRunning
                busy = nextBusy
                if (!nextBusy && message == "正在启动 Making 内核") message = ""
                upSpeed = (currentUp - previousUp).coerceAtLeast(0L)
                downSpeed = (currentDown - previousDown).coerceAtLeast(0L)
                previousUp = currentUp
                previousDown = currentDown
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                running = false
                status = "状态读取失败；请查看运行诊断"
                diagnostics = "状态读取异常：" + e.javaClass.simpleName
            } catch (e: LinkageError) {
                busy = false
                running = false
                status = "内核接口异常；请查看运行诊断"
                diagnostics = "内核接口异常：" + e.javaClass.simpleName
            }
            delay(1000L)
        }
    }
    LaunchedEffect(autoRefresh, refreshMinutes, url, running, busy, importing) {
        val interval = refreshMinutes.toLongOrNull()
        if (!autoRefresh || interval == null || interval !in 5L..10080L || url.isBlank() || running || busy || importing) return@LaunchedEffect
        while (true) {
            delay(60000L)
            if (System.currentTimeMillis() - prefs.getLong("last_refresh", 0L) < interval * 60000L) continue
            importing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    downloadProfile(url).also { storeProfile(context.filesDir, it.text) }
                }
                profile = result.text
                usage = result.usage
                prefs.edit().putString("url", url.trim()).putString("usage", usage)
                    .putLong("last_refresh", System.currentTimeMillis()).apply()
                message = "订阅已自动更新"
            } catch (e: Exception) {
                message = "自动更新失败，保留原配置：" + (e.message ?: "未知错误")
            } finally { importing = false }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Background, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Background).systemBarsPadding().padding(20.dp)) {
            Text("CHEEMS  /  CLASH", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(listOf("控制台", "节点", "配置", "设置")[tab], color = Color.White,
                fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (message.isNotEmpty()) { Text(message, color = Green); Spacer(Modifier.height(12.dp)) }
                when (tab) {
                    0 -> {
                        PanelCard {
                            Text("连接状态", color = Color.LightGray)
                            Spacer(Modifier.height(14.dp))
                            Text(status, color = Green, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Making · 独立原生内核（不含 Mihomo）", color = Color.LightGray, fontSize = 12.sp)
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = {
                                if (CheemsBridge.stopping()) {
                                    message = "正在断开 VPN…"
                                } else if (CheemsBridge.canDisconnect() || running || busy) {
                                    runCatching {
                                        CheemsBridge.disconnect(context)
                                        busy = true
                                        status = "正在断开 VPN…"
                                        message = "正在断开 VPN…"
                                    }.onFailure {
                                        message = "断开请求失败：" + (it.message ?: it.javaClass.simpleName)
                                    }
                                } else if (nodes.isEmpty()) {
                                    tab = 2; message = "请先导入配置"
                                } else {
                                    val intent = VpnService.prepare(context)
                                    if (intent == null) connect() else permission.launch(intent)
                                }
                            }, enabled = !CheemsBridge.stopping() && (!importing || CheemsBridge.canDisconnect() || running || busy),
                                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (CheemsBridge.stopping()) "正在断开…"
                                    else if (busy) "断开正在连接的 VPN"
                                    else if (CheemsBridge.canDisconnect() || running) "断开连接" else "连接")
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        PanelCard {
                            Text("实时流量", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Text("↑ " + speed(upSpeed) + "    ↓ " + speed(downSpeed), color = Green, fontSize = 17.sp)
                            Spacer(Modifier.height(10.dp))
                            Text(if (running) "Android VPN 隧道已启动" else "当前没有活动的 VPN 隧道",
                                color = Color.LightGray, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        PanelCard {
                            Text("当前节点", color = Green, fontWeight = FontWeight.Bold)
                            Text(nodes.firstOrNull { it.index == selected }?.name ?: "未选择", color = Color.White)
                        }
                    }
                    1 -> {
                        Text("已导入 " + nodes.size + " 个节点 · 连接前选择", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        nodes.forEach { node ->
                            PanelCard {
                                Text(node.name, color = if (selected == node.index) Green else Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(node.protocol.uppercase(Locale.US) +
                                    (if (node.supported) "" else " · 暂不支持"),
                                    color = Color.LightGray, fontSize = 12.sp)
                                if (!node.supported) Text(node.reason, color = Color(0xFFFFB4AB), fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    selected = node.index
                                    prefs.edit().putInt("core_selected_index", selected).apply()
                                    message = "已选择：" + node.name
                                }, enabled = node.supported && !running && !busy) {
                                    Text(if (selected == node.index) "当前选择" else "选择节点")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    2 -> PanelCard {
                        Text("配置管理", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text("已识别 " + nodes.size + " 个节点", color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("套餐流量", color = Green, fontWeight = FontWeight.Bold)
                        Text(usage ?: "订阅服务商未提供流量信息", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { openFile.launch(arrayOf("*/*")) },
                            enabled = !running && !busy && !importing, modifier = Modifier.fillMaxWidth()) {
                            Text("导入本地配置")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("HTTPS 订阅链接") }, singleLine = true,
                            enabled = !running && !busy && !importing, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自动更新订阅", color = Color.White, modifier = Modifier.weight(1f))
                            Switch(checked = autoRefresh, onCheckedChange = {
                                autoRefresh = it; prefs.edit().putBoolean("auto_refresh", it).apply()
                            })
                        }
                        OutlinedTextField(value = refreshMinutes, onValueChange = {
                            if (it.length <= 5 && it.all(Char::isDigit)) {
                                refreshMinutes = it
                                it.toIntOrNull()?.takeIf { value -> value in 5..10080 }?.let { value ->
                                    prefs.edit().putInt("refresh_minutes", value).apply()
                                }
                            }
                        }, label = { Text("更新间隔（分钟，5～10080）") },
                            singleLine = true, enabled = autoRefresh, modifier = Modifier.fillMaxWidth())
                        Text("VPN 连接期间暂停自动更新，避免更换正在使用的配置。",
                            color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            scope.launch {
                                importing = true
                                message = "正在下载订阅…"
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        downloadProfile(url).also { storeProfile(context.filesDir, it.text) }
                                    }
                                    profile = result.text
                                    usage = result.usage
                                    prefs.edit().putString("url", url.trim()).putString("usage", usage)
                                        .putLong("last_refresh", System.currentTimeMillis()).apply()
                                    message = "订阅更新成功，已导入节点"
                                } catch (e: Exception) {
                                    message = e.message ?: "订阅下载失败"
                                } finally { importing = false }
                            }
                        }, enabled = !running && !busy && !importing && url.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (importing) "正在更新…" else "更新节点 / 订阅")
                        }
                    }
                    else -> PanelCard {
                        Text("Doreena", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(CheemsBridge.version() + "\n原生支持：HY2 / AnyTLS / VLESS / VMess / SS2022 / TUIC / SOCKS5 等已实现协议。",
                            color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("运行诊断", color = Green, fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            diagnostics = runCatching { CheemsBridge.diagnostics() }
                                .getOrElse { "读取运行记录失败：" + it.javaClass.simpleName }
                            crashSummary = recentCrash()
                        }) { Text("刷新记录") }
                        Spacer(Modifier.height(8.dp))
                        Text(crashSummary, color = Color(0xFFFFB4AB), fontSize = 12.sp)
                        Text("上次 VPN 阶段：" + (startupPrefs.getString("last_stage", "无") ?: "无"),
                            color = Color.LightGray, fontSize = 12.sp)
                        Text(if (diagnostics.isEmpty()) "暂无记录" else diagnostics,
                            color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("此集成版不含 Mihomo 的策略组热切换或规则引擎；新内核仅提供实际实现的协议功能。",
                            color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("主页", "节点", "配置", "设置").forEachIndexed { index, name ->
                    TextButton(onClick = { tab = index }) {
                        Text(name, color = if (tab == index) Green else Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp))
        .border(1.dp, Color(0xFF35454A), RoundedCornerShape(24.dp)).padding(22.dp),
        content = content)
}
