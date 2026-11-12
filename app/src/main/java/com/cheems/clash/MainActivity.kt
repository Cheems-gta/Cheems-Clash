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
import core.Core
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Green = Color(0xFF73F5A2)
private val Background = Color(0xFF090E13)
private val Panel = Color(0xFF1B252B)
private const val MaxConfig = 4 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { CheemsApp() } }
}

private fun saveProfile(dir: File, bytes: ByteArray) {
    require(bytes.isNotEmpty() && bytes.size <= MaxConfig) { "配置为空或超过 4 MB" }
    val text = bytes.toString(Charsets.UTF_8)
    require(!text.trimStart().startsWith("<")) { "下载结果是网页，请使用 Clash/Mihomo YAML 订阅链接" }
    require(Regex("(?m)^\\s*(proxies|proxy-providers|rules|proxy-groups)\\s*:").containsMatchIn(text)) {
        "需要 Clash/Mihomo YAML 配置，暂不支持 Base64 节点列表"
    }
    val temp = File(dir,"profile.tmp")
    temp.writeBytes(bytes)
    check(temp.renameTo(File(dir,"profile.yaml"))) { "保存配置失败" }
}
private fun downloadProfile(link: String): ByteArray {
    var url = URL(link.trim())
    repeat(6) {
        require(url.protocol == "https") { "订阅链接及跳转地址必须使用 HTTPS" }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout=15000; conn.readTimeout=20000
            conn.setRequestProperty("User-Agent","Clash.Meta/Cheems-Clash")
            val code=conn.responseCode
            if (code in 300..399) { url=URL(url,conn.getHeaderField("Location") ?: error("订阅跳转缺少地址")) }
            else {
                require(code in 200..299) { "订阅服务器返回 HTTP $code" }
                return conn.inputStream.use { it.readBytesLimited() }
            }
        } finally { conn.disconnect() }
    }
    error("订阅跳转次数过多")
}
private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=MaxConfig) { "配置超过 4 MB" }; out.write(buffer,0,n) }
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
    val running by VpnState.running.collectAsState()
    val busy by VpnState.busy.collectAsState()
    val status by VpnState.status.collectAsState()
    val error by VpnState.error.collectAsState()
    var hasProfile by remember { mutableStateOf(File(context.filesDir,"profile.yaml").isFile) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(context.getSharedPreferences("profile",0).getString("url","") ?: "") }
    var up by remember { mutableLongStateOf(0) }; var down by remember { mutableLongStateOf(0) }
    var proxies by remember { mutableStateOf(JSONObject()) }
    var group by remember { mutableStateOf<String?>(null) }
    var delayMessage by remember { mutableStateOf("") }
    fun start() { context.startForegroundService(Intent(context,ClashVpnService::class.java)) }
    val vpnPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode==Activity.RESULT_OK) start() else message="未授权 VPN，连接未启动"
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val openFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !running && !busy) scope.launch {
            importing=true
            try { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { saveProfile(context.filesDir,it.readBytesLimited()) } }; hasProfile=true; message="配置已导入，可以连接" }
            catch(e:Exception) { message=e.message ?: "导入失败" }
            finally { importing=false }
        }
    }
    LaunchedEffect(running) {
        up=0; down=0
        while(running) {
            val result=withContext(Dispatchers.IO) { runCatching { JSONObject(Core.traffic()) to JSONObject(Core.proxies()) }.getOrNull() }
            if(result!=null) { up=result.first.optLong("up"); down=result.first.optLong("down"); proxies=result.second }
            delay(1000)
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
                            Spacer(Modifier.height(8.dp)); Text("Mihomo · Android VPN",color=Color.LightGray,fontSize=12.sp)
                            if(error.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(error,color=Color(0xFFFFB4AB)) }
                            Spacer(Modifier.height(22.dp))
                            Button(onClick={
                                if(running) context.startService(Intent(context,ClashVpnService::class.java).setAction("STOP"))
                                else if(!hasProfile) { tab=2; message="请先导入配置" }
                                else {
                                    if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    val request=VpnService.prepare(context)
                                    if(request==null) start() else vpnPermission.launch(request)
                                }
                            },enabled=!busy&&!importing,modifier=Modifier.fillMaxWidth().height(56.dp)) { Text(if(busy) "处理中…" else if(running) "断开连接" else "连接") }
                        }
                        Spacer(Modifier.height(14.dp))
                        PanelCard {
                            Text("实时流量",color=Color.White,fontWeight=FontWeight.Bold); Spacer(Modifier.height(16.dp))
                            Text("↑ ${speed(up)}     ↓ ${speed(down)}",color=Green,fontSize=18.sp)
                            Spacer(Modifier.height(8.dp)); Text(if(running) "VPN 已启动，流量由内核统计" else "尚未启动 VPN 服务",color=Color.LightGray)
                        }
                    }
                    1 -> {
                        if(!running) PanelCard { Text("连接后显示节点与策略组",color=Color.LightGray) }
                        else {
                            Text(delayMessage,color=Green)
                            proxies.keys().asSequence().toList().sorted().forEach { name ->
                                val p=proxies.optJSONObject(name)
                                if(p?.has("all")==true) {
                                    PanelCard {
                                        Text(name,color=Green,fontSize=20.sp)
                                        Text("当前：${p.optString("now")}",color=Color.White)
                                        Row {
                                            TextButton(onClick={group=name},enabled=p.optString("type")=="Selector") { Text("切换节点") }
                                            TextButton(onClick={ scope.launch {
                                                delayMessage="正在测试 $name…"
                                                delayMessage=withContext(Dispatchers.IO) { runCatching { "$name：${Core.delay(name)} ms" }.getOrElse { "测速失败：${it.message}" } }
                                            } }) { Text("测速") }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                    2 -> PanelCard {
                        Text("配置管理",color=Green,fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(12.dp)); Text(if(hasProfile) "已保存一份配置" else "尚未导入配置",color=Color.White)
                        Text("支持 Clash/Mihomo YAML；更换配置前请先断开。",color=Color.LightGray,fontSize=12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick={openFile.launch(arrayOf("*/*"))},enabled=!running&&!busy&&!importing,modifier=Modifier.fillMaxWidth()) { Text("导入本地 YAML") }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value=url,onValueChange={url=it},label={Text("HTTPS 订阅链接")},singleLine=true,enabled=!running&&!busy&&!importing,modifier=Modifier.fillMaxWidth())
                        Button(onClick={scope.launch {
                            importing=true; message="正在下载订阅…"
                            try {
                                withContext(Dispatchers.IO) { saveProfile(context.filesDir,downloadProfile(url)) }
                                context.getSharedPreferences("profile",0).edit().putString("url",url.trim()).apply()
                                hasProfile=true; message="订阅已更新，可以连接"
                            } catch(e:Exception) { message=e.message ?: "订阅下载失败" }
                            finally { importing=false }
                        }},enabled=!running&&!busy&&!importing&&url.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(importing) "正在导入…" else "下载 / 更新订阅") }
                    }
                    else -> PanelCard {
                        Text("Cheems Clash",color=Green,fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(12.dp)); Text("版本 0.2.0 · ${Core.version()}\n支持 arm64 / x86_64\nIPv4 VPN；IPv6 在 VPN 连接期间阻断。\n本应用自身流量不进入 VPN。",color=Color.White)
                        Spacer(Modifier.height(12.dp)); Text("Mihomo 采用 GPL-3.0 许可证。源码与构建脚本：github.com/Cheems-gta/Cheems-Clash",color=Color.LightGray,fontSize=12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("主页","节点","配置","设置").forEachIndexed { index,label -> TextButton(onClick={tab=index}) { Text(label,color=if(tab==index) Green else Color.LightGray) } }
            }
        }
        group?.let { name ->
            AlertDialog(onDismissRequest={group=null},title={Text(name)},text={
                Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
                    val all=proxies.optJSONObject(name)?.optJSONArray("all")
                    if(all!=null) for(i in 0 until all.length()) { val node=all.getString(i)
                        TextButton(onClick={scope.launch {
                            val result=withContext(Dispatchers.IO) { runCatching { Core.select(name,node) } }
                            message=if(result.isSuccess) "已切换至 $node" else "切换失败：${result.exceptionOrNull()?.message}"
                            group=null
                        }}) { Text(node) }
                    }
                }
            },confirmButton={TextButton(onClick={group=null}) { Text("关闭") }})
        }
    }
}
@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(24.dp)).border(1.dp,Color(0xFF35454A),RoundedCornerShape(24.dp)).padding(22.dp),content=content)
}
