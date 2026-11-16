package com.cheems.clash

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import win.cheems.core.ClashCoreFacade
import win.cheems.core.CoreService
import java.io.File

@RunWith(AndroidJUnit4::class)
class VpnSmokeTest {
    @Test fun testNativeCorePackageAndProfile() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("CI must grant VPN permission", VpnService.prepare(context))
        assertNotNull(Class.forName("go.mobilebridge.Mobilebridge"))
        assertNotNull(Class.forName("go.mobilebridge.Network"))
        val profile=File(context.filesDir,"profile.yaml")
        profile.writeText("""
            proxies:
              - name: TEST-HY2
                type: hysteria2
                server: 127.0.0.1
                port: 443
                password: test-only
                skip-cert-verify: true
        """.trimIndent())
        try {
            ClashCoreFacade.reload(context)
            val groups=JSONObject(ClashCoreFacade.proxies(context))
            val group=groups.getJSONObject("Cheems Core")
            assertEquals("TEST-HY2",group.getString("now"))
            assertEquals(1,group.getJSONArray("all").length())
            ClashCoreFacade.select(context,"TEST-HY2")
            assertTrue(JSONObject(ClashCoreFacade.traffic()).has("up"))
            assertFalse(ClashCoreFacade.isRunning())

            val appIcon=context.packageManager.getApplicationIcon(context.packageName)
            assertNotNull(appIcon)
            assertEquals(R.mipmap.cheems_brand_icon,context.applicationInfo.icon)
            val service=context.packageManager.getServiceInfo(ComponentName(context,CoreService::class.java),0)
            assertEquals(R.mipmap.cheems_brand_icon,service.icon)
            assertNotNull(context.packageManager.getActivityInfo(ComponentName(context,MainActivity::class.java),0))
        } finally {
            profile.delete()
            ClashCoreFacade.reload(context)
        }
    }
}
