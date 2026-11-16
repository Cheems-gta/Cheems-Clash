# Cheems Core transport preview notices

The combined preview is distributed under GNU GPL version 3 or later, as required
by its included GPL components. The license text is in LICENSE. Corresponding
application source, vendored Go sources and dependency license files accompany
the preview in the source archive. Build scripts and exact commit ID are included.

GPL-3.0-or-later components include anytls/sing-anytls, sagernet/sing-vmess,
sagernet/sing-shadowsocks, sagernet/sing-quic and adapted Reality client logic.
Reality protocol/client references: XTLS/REALITY and
SagerNet/sing-box common/tls/reality_client.go. See the pinned Core source docs/REALITY_IMPLEMENTATION.md.

Other native components include metacubex/uTLS and Go TLS sources (BSD-style),
gorilla/websocket (BSD-2-Clause), quic-go and x/net/http2 (MIT/BSD-style), and
sagernet/sing/sing-tun components under their individual licenses. This list does
not replace the actual dependency licenses: the APK assets/licenses/native tree
contains module license/notice files and native-modules.json records versions.
Vendored source contains the applicable per-file copyright notices.

Android Java dependency org.yaml:snakeyaml 2.3 is Apache-2.0. Android platform,
Gradle/Android build tooling and gomobile tooling retain their upstream licenses.
The preview is experimental; no warranty of stability or provider interoperability
is made. Keep user subscription credentials out of source archives and logs.
