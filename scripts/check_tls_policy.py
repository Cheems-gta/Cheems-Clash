#!/usr/bin/env python3
"""Fail CI if the app silently disables TLS validation for all nodes."""
from pathlib import Path

controller = Path("app/src/main/java/com/cheems/clash/CoreController.kt").read_text()
assert "Subscription.parse(file.readText())" in controller, "Subscription parsing changed: review TLS handling"
assert "withoutCertificateChecks()" not in controller, "Imported trust settings must not be stripped"
assert '.putExtra("hy2.allowUnverifiedTls", false)' in controller, "VPN must use verified TLS by default"
assert '.putExtra("hy2.allowUnverifiedTls", true)' not in controller, "Unconditional insecure TLS is forbidden"
print("TLS policy source checks passed")
