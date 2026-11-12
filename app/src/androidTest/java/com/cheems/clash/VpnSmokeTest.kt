package com.cheems.clash

import android.content.Intent
import android.net.VpnService
import android.test.InstrumentationTestCase
import core.Core
import java.io.File

@Suppress("DEPRECATION")
class VpnSmokeTest : InstrumentationTestCase() {
    private fun awaitState(check: () -> Boolean) {
        val deadline=System.currentTimeMillis()+45000
        while(!check() && System.currentTimeMillis()<deadline) Thread.sleep(200)
        assertTrue("VPN state=${VpnState.status.value}, error=${VpnState.error.value}",check())
    }
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
                context.startService(Intent(context,ClashVpnService::class.java).setAction("STOP"))
                awaitState { !VpnState.running.value && !VpnState.busy.value }
            }
        } finally { context.stopService(Intent(context,ClashVpnService::class.java)) }
    }
}
