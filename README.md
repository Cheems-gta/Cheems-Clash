# Cheems Clash · native Cheems Core preview

The `cheems-core-native` integration branch replaces the original Mihomo runtime with the standalone [Cheems Core](https://github.com/Cheems-gta/Cheems-Core) Go/Android VPN implementation. The Cheems Clash Compose UI and dog icon remain.

## Build
The GitHub Actions workflow checks out pinned Cheems Core revision `148b1034255a4a097695baf6c173c1f545a51ae1`, builds its Go mobile bridge into an Android ARM32/ARM64 AAR, copies its Java VPN service, and bundles GPL-3.0 and third-party notices. **Mihomo is not built or loaded.**

## Features
- Import a Clash YAML subscription with `proxies:`, refresh subscriptions when disconnected, and choose supported endpoints before connecting.
- Cheems Core native VPN tunnel, IPv4/IPv6 forwarding, proxy preflight and native traffic counters.
- Provider-flagged `skip-cert-verify` is honored. A separate all-node TLS compatibility toggle requires explicit risk confirmation.

## Limitations
This is a **preview, not feature parity**: Clash group routing, uninterrupted endpoint switching, and standalone per-node latency tests have not been migrated. Some Mihomo protocols and transport options are unsupported. Minimum Android 9 (API 28); ARM32 and ARM64 native libraries. GitHub's x86_64 emulator can test configuration and UI packaging but cannot execute the ARM native library; real-device network testing remains necessary.

Production `main` remains unchanged until verified. Licensed under GPL-3.0; dependency license notices are shipped inside the APK.
