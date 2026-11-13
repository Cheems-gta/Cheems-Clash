package com.cheems.clash

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import core.Core
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class VpnSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun awaitState(check: () -> Boolean) {
        val deadline=System.currentTimeMillis()+45000
        while(!check() && System.currentTimeMillis()<deadline) Thread.sleep(200)
        assertTrue("VPN state=${VpnState.status.value}, error=${VpnState.error.value}",check())
    }
    @Test
    fun testStartSelectStopRestart() {
        val context=instrumentation.targetContext
        assertNull("CI must grant ACTIVATE_VPN",VpnService.prepare(context))
        File(context.filesDir,"profile.yaml").writeText("""
            mode: rule
            proxies: []
            proxy-groups:
              - name: TEST
                type: select
                proxies: [DIRECT, REJECT]
            rules: ["MATCH,TEST"]
        """.trimIndent())
        try {
            repeat(2) {
                context.startForegroundService(Intent(context,ClashVpnService::class.java))
                awaitState { VpnState.running.value && !VpnState.busy.value }
                assertTrue(Core.proxies().contains("TEST"))
                Core.select("TEST","REJECT")
                assertTrue(Core.proxies().contains("REJECT"))
                Core.select("TEST","DIRECT")
                assertTrue(Core.traffic().contains("up"))
                val descriptor = instrumentation.uiAutomation.executeShellCommand("sh /data/local/tmp/cheems-probe.sh")
                val response = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                assertTrue("TUN HTTP probe failed: $response", response.contains("CHEEMS_TUN_OK"))
                val totals = JSONObject(Core.totalTraffic())
                assertTrue("Probe bypassed TUN", totals.getLong("up") > 0 && totals.getLong("down") > 0)
                context.startService(Intent(context,ClashVpnService::class.java).setAction("STOP"))
                awaitState { !VpnState.running.value && !VpnState.busy.value }
            }
        } finally { context.stopService(Intent(context,ClashVpnService::class.java)) }
    }
}
