# Single-engine integration

Application: Cheems Clash, application ID com.cheems.clash.
Native engine: Cheems Core only.
Pinned upstream source: https://github.com/Cheems-gta/Cheems-Core/tree/148b1034255a4a097695baf6c173c1f545a51ae1

CoreController owns node selection and serializes UI connection transitions.
CoreService owns the foreground notification, protected outbound sockets, local
proxy and Android VPN TUN lifecycle. Native startup and teardown are serialized
so canceled startup cannot stop its successor. The old native module, JNI calls,
service and build workflow have been removed from this branch.

Remaining limitations are documented in README; protocol support does not imply
all options or all provider endpoints have been verified. Emulator forwarding
checks use a local SOCKS fixture, not the user's Flybit subscription.
