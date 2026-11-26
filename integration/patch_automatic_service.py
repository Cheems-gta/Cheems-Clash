from pathlib import Path

def replace(path, old, new):
    source = path.read_text()
    if source.count(old) != 1:
        raise SystemExit(f"Pinned source mismatch in {path}")
    path.write_text(source.replace(old, new))

root = Path("app/src/main/java/win/cheems/core")
replace(root / "CoreService.java", "Hysteria2Native.startTunnel(vpnInterface.getFd());",
        'boolean automatic = getSharedPreferences("profile", MODE_PRIVATE).getBoolean("automatic_routing", true);\n'
        '      Hysteria2Native.startTunnel(vpnInterface.getFd(), automatic);\n'
        '      log(automatic ? "自动分流已启用" : "全局代理已启用");')
bridge = root / "net/Hysteria2Native.java"
replace(bridge, "void startTunnel(long fd)", "void startTunnel(long fd, boolean automatic)")
replace(bridge, 'getMethod("startTunnel",long.class)', 'getMethod(automatic ? "startAutomaticTunnel" : "startTunnel",long.class)')
