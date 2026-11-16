package com.cheems.clash

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import win.cheems.core.CoreService
import win.cheems.core.net.Hysteria2Native
import java.io.File

@RunWith(AndroidJUnit4::class)
class VpnSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun awaitState(check: () -> Boolean) {
        val deadline = System.currentTimeMillis()+60000
        while (!check() && System.currentTimeMillis()<deadline) Thread.sleep(150)
        assertTrue("Core state: ${CoreService.logs()}", check())
    }
    @Test fun testSingleCoreStartSwitchStopRestart() {
        val context = instrumentation.targetContext
        assertNull(VpnService.prepare(context))
        assertTrue("Core native binding absent", Hysteria2Native.isPackaged())
        assertTrue("Legacy JNI classes must not be packaged", runCatching { Class.forName("core.Core") }.isFailure)
        val services = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES).services
        assertEquals(listOf("win.cheems.core.CoreService"), services.orEmpty().map { it.name })
        File(context.filesDir, "profile.yaml").writeText("""
            proxies:
              - {name: fixture-one, type: socks5, server: 10.0.2.2, port: 10880}
              - {name: fixture-two, type: socks5, server: 10.0.2.2, port: 10880}
        """.trimIndent())
        runBlocking { CoreController.reload(context) }
        assertEquals(2, CoreController.nodes.value.size)
        assertTrue("Imported nodes must retain original TLS settings", CoreController.nodes.value.all { !it.hasInsecureTlsFlag() })
        fun probe() {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("sh /data/local/tmp/cheems-probe.sh")
            val response = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertTrue("Full TUN forwarding failed: $response", response.contains("CHEEMS_CORE_TUN_OK"))
            assertTrue("Probe bypassed TUN", Hysteria2Native.tunnelUploaded()>0 && Hysteria2Native.tunnelDownloaded()>0)
        }
        try {
            repeat(2) {
                instrumentation.runOnMainSync { CoreController.connect(context) }
                awaitState { CoreService.tunnelEstablished && !CoreService.connecting }
                probe()
                assertTrue(runBlocking { CoreController.probe() } >= 0)
                val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id==1 }.notification
                assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
                assertEquals(R.mipmap.cheems_brand_icon, context.applicationInfo.icon)
                val next = CoreController.nodes.value.last()
                instrumentation.runOnMainSync { CoreController.select(context, next) }
                awaitState { CoreController.selected.value==CoreController.key(next) && CoreService.selected?.name==next.name && CoreService.tunnelEstablished && !CoreService.connecting }
                probe()
                // Return to home: the foreground VPN must survive activity backgrounding.
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                Thread.sleep(1500)
                assertTrue(CoreService.tunnelEstablished)
                probe()
                instrumentation.runOnMainSync { CoreController.disconnect(context) }
                awaitState { !CoreService.serviceAlive && !CoreService.tunnelEstablished }
                assertFalse(Hysteria2Native.isRunning())
                instrumentation.uiAutomation.executeShellCommand("am start -n com.cheems.clash/.MainActivity").close()
                Thread.sleep(1000)
            }
        } finally {
            context.stopService(Intent(context, CoreService::class.java))
        }
    }
}
