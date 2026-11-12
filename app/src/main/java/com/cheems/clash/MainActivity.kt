package com.cheems.clash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF73F5A2)
private val Background = Color(0xFF090E13)
private val Panel = Color(0xFF1B252B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CheemsApp() }
    }
}

@Composable
fun CheemsApp() {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Background, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Background).systemBarsPadding().padding(20.dp)) {
            Text("CHEEMS  /  CLASH", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(listOf("控制台", "节点", "配置", "设置")[tab], color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        PanelCard {
                            Text("连接状态", color = Color.LightGray)
                            Spacer(Modifier.height(18.dp))
                            Text("尚未连接", color = Green, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("V0.1 界面预览 · 尚未集成代理内核", color = Color.LightGray, fontSize = 12.sp)
                            Spacer(Modifier.height(22.dp))
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text("连接功能开发中")
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        PanelCard {
                            Text("实时流量", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Text("↑  0 B/s                 ↓  0 B/s", color = Green, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("尚未启动 VPN 服务", color = Color.LightGray)
                        }
                    }
                    1 -> PanelCard {
                        Text("节点管理", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("内核集成后将支持策略组、节点切换和延迟测试。", color = Color.White)
                    }
                    2 -> PanelCard {
                        Text("配置管理", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("后续版本将支持导入本地 YAML 和订阅链接。", color = Color.White)
                    }
                    else -> PanelCard {
                        Text("关于 Cheems Clash", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("版本 0.1.0 · Android 原生应用\n当前版本仅供 UI 测试，不提供代理连接。", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("主页", "节点", "配置", "设置").forEachIndexed { index, label ->
                    TextButton(onClick = { tab = index }) {
                        Text(label, color = if (tab == index) Green else Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp))
            .border(1.dp, Color(0xFF35454A), RoundedCornerShape(24.dp))
            .padding(22.dp),
        content = content
    )
}
