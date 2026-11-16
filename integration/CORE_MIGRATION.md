# Cheems Clash single-engine migration

User decision: replace Mihomo entirely with Cheems Core. No engine selector.

Baseline engine: Cheems-Core commit
`148b1034255a4a097695baf6c173c1f545a51ae1` (0.4.4).
User verified AnyTLS US 4 with actual ChatGPT traffic; HY2 completed preflight
and TUN startup but also reported a later TCP connection failure.

## Implementation requirements

- Replace the Mihomo AAR, Go adapter, build checkout and dependencies.
- Retain the Cheems Clash launcher and notification dog icons and Compose UI.
- Reuse profile.yaml and subscription usage/update settings.
- Parse concrete nodes using Core's importer; report unsupported configurations.
- Connect through the proven Core VPN service and protected physical network.
- Expose actual service state, traffic counters and redacted diagnostics to UI.
- Remove Mihomo-specific policy-group and controller calls.
- Port node selection and health checks to Core before exposing their controls.
- Preserve the user-requested node certificate-skip policy from Core 0.4.4.
- Build with 16 KiB native-library alignment and verify signatures.
- Run Android VPN lifecycle/forwarding checks before offering a replacement APK.

Current status: migration baseline only. App conversion and APK not completed.
