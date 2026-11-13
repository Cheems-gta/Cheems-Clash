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
        assertTrue("VPN state=${VpnState.status.value}, running=${VpnState.running.value}, busy=${VpnState.busy.value}, error=${VpnState.error.value}",check())
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
                val notifications = context.getSystemService(android.app.NotificationManager::class.java)
                val posted = notifications.activeNotifications.single { it.id == 2 }.notification
                assertEquals(R.drawable.ic_stat_cheems, posted.smallIcon.resId)
                assertNotNull("Cheems large icon must be set", posted.getLargeIcon())
                assertEquals(android.graphics.drawable.Icon.TYPE_BITMAP, posted.getLargeIcon().type)
                for (icon in listOf(posted.smallIcon, posted.getLargeIcon())) {
                    val drawable = icon.loadDrawable(context) as android.graphics.drawable.BitmapDrawable
                    val bitmap = drawable.bitmap
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Notification icon must contain visible pixels",
                        pixels.any { android.graphics.Color.alpha(it) > 0 })
                }
                assertFalse("Legacy notification must be removed", notifications.activeNotifications.any { it.id == 1 })
                assertTrue(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("v0.2.7"))
                val appIcon = context.packageManager.getApplicationIcon(context.packageName)
                assertTrue("Application identity must load a flat bitmap", appIcon is android.graphics.drawable.BitmapDrawable)
                assertEquals(R.mipmap.cheems_brand_icon, context.applicationInfo.icon)
                val expected = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.cheems_brand_icon)
                assertTrue("Application icon pixels must match Cheems artwork",
                    (appIcon as android.graphics.drawable.BitmapDrawable).bitmap.sameAs(expected))
                val activity = context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0)
                val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, ClashVpnService::class.java), 0)
                assertEquals(R.mipmap.cheems_brand_icon, activity.icon)
                assertEquals(R.mipmap.cheems_brand_icon, service.icon)
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
