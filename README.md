# Cheems Clash — Cheems Core edition

Android client using a single Cheems Core engine. The application contains no
Mihomo engine, JNI bridge, service, controller API, build input or fallback.
Application ID: `com.cheems.clash`; Android 9+; arm64-v8a and x86_64.

## Current behavior

Import node YAML (`proxies`), Base64 lists or node sharing URIs; choose a node;
authorize Android VPN and connect. Selecting another node closes the previous
VPN before establishing the new connection. Subscription usage and timed updates
are retained. Updates are deferred while connected and run while the app process
is alive; this is not an OS-scheduled background updater.

This edition uses global routing, not subscription rule/group evaluation.
Native VPN adapters: AnyTLS, Hysteria 2, VLESS, VMess, Shadowsocks 2022, TUIC v5,
and SOCKS5, subject to each adapter's supported options. Incomplete transports are
shown with an explicit reason and cannot be selected for global VPN. AnyTLS has
TCP and DNS-over-TCP fallback, not general UDP. The connection compatibility
policy currently skips conventional node TLS certificate verification; Reality
protocol authentication remains enabled. Do not interpret import or a running
VPN interface as proof every provider endpoint works.

## Reproducible build

`.github/workflows/android.yml` pins the Core source revision and gomobile,
runs native/unit tests, produces the APK, checks signatures and 16 KiB alignment,
and runs an Android emulator test of actual TUN forwarding and service lifecycle.
The host SOCKS fixture produces its marker only through the proxy; direct traffic
cannot satisfy the test. No real subscription secrets are needed in CI.

GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md. The build packages dependency
notices and the native dependency version manifest in APK assets. Full pinned
native source is linked in `integration/CORE_MIGRATION.md`.
