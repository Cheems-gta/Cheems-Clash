#!/usr/bin/env python3
"""Guard opt-in TLS compatibility: default verified, explicit user choice, visible risk."""
from pathlib import Path

controller = Path("app/src/main/java/com/cheems/clash/CoreController.kt").read_text()
ui = Path("app/src/main/java/com/cheems/clash/MainActivity.kt").read_text()
service = Path("engine/src/main/java/win/cheems/core/CoreService.java").read_text()
native = Path("engine/src/main/java/win/cheems/core/net/Hysteria2Native.java").read_text()

assert "Subscription.parse(file.readText())" in controller, "Subscription parsing changed: review TLS handling"
assert "withoutCertificateChecks()" not in controller, "Imported trust settings must not be stripped"
assert "val allowUnverifiedTls = MutableStateFlow(false)" in controller, "TLS must be verified by default"
assert 'getBoolean("allow_unverified_tls", false)' in controller, "Compatibility must be opt-in with safe default"
assert 'putBoolean("allow_unverified_tls", enabled)' in controller, "Explicit user setting must persist"
assert '.putExtra("hy2.allowUnverifiedTls", allowUnverifiedTls.value)' in controller, "User preference must reach VPN service"
assert '.putExtra("hy2.allowUnverifiedTls", true)' not in controller, "Unconditional unverified TLS is forbidden"
assert 'Switch(checked=allowUnverifiedTls,enabled=!running&&!busy' in ui, "Add disabled-while-connected opt-in control"
assert "中间人攻击" in ui and "证书验证已关闭" in ui, "Unverified mode needs clear risk/active warnings"
assert 'getBooleanExtra("hy2.allowUnverifiedTls", false)' in service, "Service must maintain secure intent default"
assert "Hysteria2Native.start(node, Hysteria2Native.LOCAL_SOCKS_PORT," in service, "Service must forward mode"
assert "allowUnverifiedTLS, customCAPEM, this)" in service, "Service must propagate user choice to native Core"
assert "allowUnverifiedTLS ? \"\" : node.certificateSHA256()" in native, "Native Core must honor unverified mode"
print("TLS compatibility policy checks passed")
