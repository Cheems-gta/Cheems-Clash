# Domestic connectivity recovery experiment

- The physical-network selection/publication change in 48e00b6 is rolled back from the Android build after a user report of failed external connectivity. Restore the pinned CoreService network-selection behavior; this is an isolation step, not a confirmed root-cause fix.
- Keep the first-stage app split tunnel and TCP relay patch. WeChat, QQ and Bilibili bypass the VPN when the explicit app-split switch is on. Other apps retain the existing routing. No GeoIP/domain rules are implemented.
- Keep manual-only latency probes while disconnected and the user-initiated DNS/HTTPS direct comparison in Settings.
- No changes to native protocols, MTU, DNS upstream or certificate handling. Existing certificate bypass remains a security risk.
- Acceptance: same network and node, verify direct apps and proxied apps concurrently, test multi-minute streaming, reconnect and compare latency. Compilation alone is not proof of recovery.
