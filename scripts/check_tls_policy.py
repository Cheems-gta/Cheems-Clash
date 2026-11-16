#!/usr/bin/env python3
"""Check the explicitly requested Cheems Core no-certificate-validation build."""
from pathlib import Path
controller=Path("app/src/main/java/com/cheems/clash/CoreController.kt").read_text()
ui=Path("app/src/main/java/com/cheems/clash/MainActivity.kt").read_text()
service=Path("engine/src/main/java/win/cheems/core/CoreService.java").read_text()
native=Path("engine/src/main/java/win/cheems/core/net/Hysteria2Native.java").read_text()
assert "Subscription.parse(file.readText())" in controller
assert "withoutCertificateChecks()" not in controller
assert '.putExtra("hy2.allowUnverifiedTls", true)' in controller, "Requested compatibility mode must be enabled"
assert "allowUnverifiedTls.value" not in controller, "No stale opt-in setting may override the requested mode"
assert "TLS 服务器证书验证已关闭" in ui, "Warn on home screen"
assert "中间人攻击" in ui, "Show risk warning"
assert 'getBooleanExtra("hy2.allowUnverifiedTls", false)' in service, "Service must read intent flag"
assert "allowUnverifiedTLS, customCAPEM, this)" in service, "Flag must reach native Core"
assert 'allowUnverifiedTLS ? "" : node.certificateSHA256()' in native, "Native Core must disable pin validation"
print("Requested unverified TLS compatibility checks passed")
