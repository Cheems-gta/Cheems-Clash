# Application split tunnel (first-stage fix)

- Enabled by default; WeChat (com.tencent.mm), QQ (com.tencent.mobileqq), and Bilibili (tv.danmaku.bili) bypass the VPN as entire applications, including their DNS.
- Turn the switch off while disconnected to restore the original global mode.
- This does not implement GeoIP, domain DNS classification, arbitrary app rules, or a separate direct-only mode. A browser is still globally proxied.
- Lockdown ("Block connections without VPN") conflicts with app bypass. The service refuses this combination rather than claiming direct connectivity.
- No changes to native protocol or TLS handling; existing unverified TLS settings remain a server impersonation risk.
- Real-device acceptance: installed/uninstalled apps, Wi-Fi and cellular, HY2/AnyTLS, IPv4/IPv6, app DNS and domestic access, remaining apps through VPN, global mode, reconnect and lockdown. CI compilation alone is not acceptance.
