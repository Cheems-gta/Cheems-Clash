# Cheems Clash

Android Kotlin/Compose client with an embedded Mihomo v1.19.34 core, built from pinned source (0455d0d28c4526937380505153e65a843d8612e8) using gomobile. GPL-3.0; see LICENSE and upstream https://github.com/MetaCubeX/mihomo.

## v0.2 beta

Import a Clash/Mihomo YAML file or an HTTPS subscription in 配置, then connect and accept Android's VPN prompt. The existing active VPN will be replaced by Android. 节点 lists strategy groups; manual selector groups support switching. Speed values come from the core. Configurations are private, excluded from backups, and not uploaded to this repository. A subscription must return YAML, not a web page or Base64 node list.

IPv4 TUN with gVisor; Android blocks IPv6 while the VPN is active. The app itself bypasses the VPN for bootstrap/subscription/provider access. Imported listeners, controllers and routing configuration are overridden to avoid conflicts with Android. Provider/GeoIP downloads may be needed on first connection. Core startup does not prove that your remote proxy is reachable.

Supported ABIs: arm64-v8a and x86_64. Minimum Android 8. This is a debug-signed beta; different CI runs may have different debug keys, so an existing preview might need uninstalling before installation (export your configuration first).

## Reproduce

See `.github/workflows/android.yml`: JDK 17, Gradle 8.9, SDK 35, NDK 27.2.12479018, Go 1.26. Clone the pinned Mihomo tag into `mihomo-src`, build `native` into `app/libs/core.aar`, then run `gradle :app:assembleDebug`. Complete corresponding source for the embedded core is available at the pinned upstream commit; this repository contains the Android client, glue and build instructions.
