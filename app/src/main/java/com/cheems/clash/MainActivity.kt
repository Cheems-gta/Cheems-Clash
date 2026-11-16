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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import win.cheems.core.CoreService
import win.cheems.core.net.Subscription
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Green = Color(0xFF73F5A2)
private val Background = Color(0xFF090E13)
private val Panel = Color(0xFF1B252B)
private const val MaxConfig = 2 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); CoreController.observe(this); setContent { CheemsApp() } }
}

private fun saveProfile(dir: File, bytes: ByteArray) {
    require(bytes.isNotEmpty() && bytes.size <= MaxConfig) { "配置为空或超过 2 MB" }
    val text = bytes.toString(Charsets.UTF_8)
    require(!text.trimStart().startsWith("<")) { "下载结果是网页，请使用节点订阅链接" }
    Subscription.parse(text) // Validate before atomically replacing the working subscription.
    val temp = File(dir,"profile.tmp")
    temp.writeBytes(bytes)
    check(temp.renameTo(File(dir,"profile.yaml"))) { "保存配置失败" }
}
private data class SubscriptionDownload(val bytes: ByteArray, val usage: String?)
private fun parseUsage(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val values = header.split(';').mapNotNull { entry ->
        val pair = entry.trim().split('=', limit = 2)
        if (pair.size != 2) null else pair[1].trim().toLongOrNull()?.let { pair[0].trim().lowercase(Locale.US) to it }
    }.toMap()
    val uploaded = values["upload"] ?: return null
    val downloaded = values["download"] ?: return null
    val total = values["total"] ?: return null
    fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    val used = uploaded + downloaded
    val expiry = values["expire"]?.takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(it * 1000))
    } ?: "未提供"
    return "已用 ${gb(used)} · 剩余 ${gb((total-used).coerceAtLeast(0))}\n总量 ${gb(total)} · 到期 $expiry"
}
private fun downloadProfile(link: String): SubscriptionDownload {
    var url = URL(link.trim())
    repeat(6) {
        require(url.protocol == "https") { "订阅链接及跳转地址必须使用 HTTPS" }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout=15000; conn.readTimeout=20000
            conn.setRequestProperty("User-Agent","Clash/Cheems-Core")
            val code=conn.responseCode
            if (code in 300..399) { url=URL(url,conn.getHeaderField("Location") ?: error("订阅跳转缺少地址")) }
            else {
                require(code in 200..299) { "订阅服务器返回 HTTP $code" }
                return SubscriptionDownload(conn.inputStream.use { it.readBytesLimited() }, parseUsage(conn.getHeaderField("subscription-userinfo")))
            }
        } finally { conn.disconnect() }
    }
    error("订阅跳转次数过多")
}
private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=MaxConfig) { "配置超过 2 MB" }; out.write(buffer,0,n) }
    return out.toByteArray()
}
private fun speed(value: Long): String = when {
    value>=1024*1024 -> String.format(Locale.US,"%.1f MB/s",value/1048576.0)
    value>=1024 -> String.format(Locale.US,"%.1f KB/s",value/1024.0)
    else -> "$value B/s"
}

@Composable
fun CheemsApp() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val running by CoreController.running.collectAsState()
    val busy by CoreController.busy.collectAsState()
    val status by CoreController.status.collectAsState()
    val error by CoreController.error.collectAsState()
    val nodes by CoreController.nodes.collectAsState()
    val selected by CoreController.selected.collectAsState()
    val up by CoreController.upload.collectAsState()
    val down by CoreController.download.collectAsState()
    val connections by CoreController.connections.collectAsState()
    var hasProfile by remember { mutableStateOf(File(context.filesDir,"profile.yaml").isFile) }
    var importing by remember { mutableStateOf(false) }
    var subscriptionUsage by remember { mutableStateOf(context.getSharedPreferences("profile",0).getString("usage",null)) }
    var refreshMinutes by remember { mutableStateOf(context.getSharedPreferences("profile",0).getInt("refresh_minutes",60).toString()) }
    var autoRefresh by remember { mutableStateOf(context.getSharedPreferences("profile",0).getBoolean("auto_refresh",true)) }
    var message by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(context.getSharedPreferences("profile",0).getString("url","") ?: "") }
    var delayMessage by remember { mutableStateOf("") }
    var diagnosticLog by remember { mutableStateOf(CoreService.logs()) }
    fun start() { CoreController.connect(context) }
    val vpnPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode==Activity.RESULT_OK) start() else message="未授权 VPN，连接未启动"
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val openFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !running && !busy) scope.launch {
            importing=true
            try { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { saveProfile(context.filesDir,it.readBytesLimited()) } }; CoreController.reload(context); hasProfile=true; message="配置已导入，可以连接" }
            catch(e:Exception) { message=e.message ?: "导入失败" }
            finally { importing=false }
        }
    }
    LaunchedEffect(autoRefresh,refreshMinutes,running,busy,url,importing) {
        val minutes=refreshMinutes.toLongOrNull()
        if(!autoRefresh || minutes==null || minutes<5 || url.isBlank() || running || busy || importing) return@LaunchedEffect
        while(true) {
            delay(60_000L)
            val prefs=context.getSharedPreferences("profile",0)
            val last=prefs.getLong("last_refresh",0L)
            if(System.currentTimeMillis()-last < minutes*60_000L) continue
            prefs.edit().putLong("last_refresh",System.currentTimeMillis()).apply()
            importing=true
            try {
                val result=withContext(Dispatchers.IO) { downloadProfile(url).also { saveProfile(context.filesDir,it.bytes) } }
                subscriptionUsage=result.usage
                prefs.edit().putString("url",url.trim()).putString("usage",result.usage).apply()
                CoreController.reload(context); hasProfile=true
                message="订阅已自动更新"
            } catch(e:Exception) {
                message="自动更新失败，已保留旧配置：${e.message}"
            } finally { importing=false }
        }
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=Green,background=Background,surface=Panel)) {
        Column(Modifier.fillMaxSize().background(Background).systemBarsPadding().padding(20.dp)) {
            Text("CHEEMS  /  CLASH",color=Green,fontSize=14.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(listOf("控制台","节点","配置","设置")[tab],color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if(message.isNotEmpty()) { Text(message,color=Green); Spacer(Modifier.height(12.dp)) }
                when(tab) {
                    0 -> {
                        PanelCard {
                            Text("连接状态",color=Color.LightGray); Spacer(Modifier.height(18.dp))
                            Text(status,color=Green,fontSize=27.sp,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(8.dp)); Text("Cheems Core · Android 全局 VPN",color=Color.LightGray,fontSize=12.sp)
                            if(error.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(error,color=Color(0xFFFFB4AB)) }
                            Spacer(Modifier.height(22.dp))
                            Button(onClick={
                                if(running) CoreController.disconnect(context)
                                else if(!hasProfile || CoreController.chosen()==null) { tab=2; message="请先导入并选择支持全局 VPN 的节点" }
                                else {
                                    val request=VpnService.prepare(context)
                                    if(request==null) start() else vpnPermission.launch(request)
                                }
                            },enabled=!busy&&!importing,modifier=Modifier.fillMaxWidth().height(56.dp)) { Text(if(busy) "处理中…" else if(running) "断开连接" else "连接") }
                        }
                        Spacer(Modifier.height(14.dp))
                        PanelCard {
                            Text("实时流量",color=Color.White,fontWeight=FontWeight.Bold); Spacer(Modifier.height(16.dp))
                            Text("↑ ${speed(up)}     ↓ ${speed(down)}",color=Green,fontSize=18.sp)
                            Spacer(Modifier.height(8.dp)); Text(if(running) "活动连接 $connections · IPv4 / IPv6 隧道" else "尚未启动 VPN 服务",color=Color.LightGray)
                            CoreController.chosen()?.let { Text("节点：${it.name}",color=Color.LightGray,fontSize=12.sp) }
                        }
                    }
                    1 -> {
                        Text("${nodes.size} 个节点 · ${nodes.count { CoreController.vpnCapable(it) }} 个支持全局 VPN", color=Green)
                        Text("选择节点后连接；连接中切换会重新建立 VPN。",color=Color.LightGray,fontSize=12.sp)
                        TextButton(onClick={ scope.launch {
                            delayMessage="正在验证当前节点转发…"
                            delayMessage=runCatching { "当前节点转发通过：${CoreController.probe()} ms" }
                                .getOrElse { "转发未通过：${it.message}" }
                        } },enabled=running&&!busy) { Text("检测当前连接") }
                        Text(delayMessage,color=Green)
                        nodes.forEach { node ->
                            PanelCard {
                                Text(node.name,color=Color.White,fontWeight=FontWeight.Bold)
                                val reason=CoreController.reason(node)
                                Text(node.type.uppercase()+if(reason.isEmpty()) " · 可连接" else " · $reason",color=Color.LightGray,fontSize=12.sp)
                                TextButton(onClick={ CoreController.select(context,node) },enabled=!busy&&reason.isEmpty()) {
                                    Text(if(CoreController.key(node)==selected) "✓ 已选择" else "选择节点")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    2 -> PanelCard {
                        Text("配置管理",color=Green,fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(12.dp)); Text(if(hasProfile) "已保存一份配置" else "尚未导入配置",color=Color.White)
                        Text("支持含 proxies 的 YAML、Base64 节点列表和分享链接。当前使用全局模式，不执行订阅中的策略组和分流规则。",color=Color.LightGray,fontSize=12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("套餐流量",color=Green,fontWeight=FontWeight.Bold)
                        Text(subscriptionUsage ?: "服务商未提供流量信息",color=Color.White,fontSize=13.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick={openFile.launch(arrayOf("*/*"))},enabled=!running&&!busy&&!importing,modifier=Modifier.fillMaxWidth()) { Text("导入节点文件") }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value=url,onValueChange={url=it},label={Text("HTTPS 订阅链接")},singleLine=true,enabled=!running&&!busy&&!importing,modifier=Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Text("自动更新订阅",color=Color.White,modifier=Modifier.weight(1f))
                            Switch(checked=autoRefresh,onCheckedChange={
                                autoRefresh=it
                                context.getSharedPreferences("profile",0).edit().putBoolean("auto_refresh",it).apply()
                            })
                        }
                        OutlinedTextField(value=refreshMinutes,onValueChange={input ->
                            if(input.length<=5 && input.all { it.isDigit() }) {
                                refreshMinutes=input
                                input.toIntOrNull()?.takeIf { it in 5..10080 }?.let { valid ->
                                    context.getSharedPreferences("profile",0).edit().putInt("refresh_minutes",valid).apply()
                                }
                            }
                        },label={Text("更新间隔（分钟，5～10080）")},singleLine=true,enabled=autoRefresh,modifier=Modifier.fillMaxWidth())
                        Text("为避免断开正在使用的 VPN，连接期间暂缓更新；断开后恢复定时检查。",color=Color.LightGray,fontSize=12.sp)
                        Button(onClick={scope.launch {
                            importing=true; message="正在下载订阅…"
                            try {
                                val result=withContext(Dispatchers.IO) { downloadProfile(url).also { saveProfile(context.filesDir,it.bytes) } }
                                subscriptionUsage=result.usage
                                context.getSharedPreferences("profile",0).edit().putString("url",url.trim()).putString("usage",subscriptionUsage).apply()
                                context.getSharedPreferences("profile",0).edit().putLong("last_refresh",System.currentTimeMillis()).apply()
                                CoreController.reload(context); hasProfile=true; message="订阅已更新，可以连接"
                            } catch(e:Exception) { message=e.message ?: "订阅下载失败" }
                            finally { importing=false }
                        }},enabled=!running&&!busy&&!importing&&url.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(importing) "正在导入…" else "更新节点 / 订阅") }
                    }
                    else -> PanelCard {
                        Text("Cheems Clash",color=Green,fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(12.dp)); Text("版本 0.3.0 · Cheems Core\n支持 arm64 / x86_64 · IPv4 / IPv6\n本应用自身流量不进入 VPN。\nAnyTLS 暂无通用 UDP 转发；DNS 使用 TCP 回退。",color=Color.White)
                        Spacer(Modifier.height(18.dp))
                        Text("VPN 稳定性诊断",color=Green,fontSize=18.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("显示内核启动、连接和隧道诊断；日志会遮蔽节点密钥。",color=Color.LightGray,fontSize=12.sp)
                        TextButton(onClick={diagnosticLog=CoreService.logs()}) { Text("刷新诊断记录") }
                        Text(if(diagnosticLog.isBlank()) "暂无诊断记录" else diagnosticLog,color=Color.White,fontSize=12.sp)
                        Spacer(Modifier.height(12.dp)); Text("Cheems Core 单内核版本。源码、依赖与许可证：github.com/Cheems-gta/Cheems-Clash",color=Color.LightGray,fontSize=12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("主页","节点","配置","设置").forEachIndexed { index,label -> TextButton(onClick={tab=index}) { Text(label,color=if(tab==index) Green else Color.LightGray) } }
            }
        }

    }
}
@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(24.dp)).border(1.dp,Color(0xFF35454A),RoundedCornerShape(24.dp)).padding(22.dp),content=content)
}

