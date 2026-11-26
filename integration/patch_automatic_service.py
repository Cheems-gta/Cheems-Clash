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

service = root / "CoreService.java"
replace(service, "private android.net.Network physicalNetwork;",
        "private volatile android.net.Network physicalNetwork;")
replace(service, """    if(rawFd<0 || rawFd>Integer.MAX_VALUE || !protect((int)rawFd)) return false;
    android.net.Network current=choosePhysicalNetwork();
    if(current==null) return false;
    try (ParcelFileDescriptor descriptor=ParcelFileDescriptor.fromFd((int)rawFd)) {
      current.bindSocket(descriptor.getFileDescriptor());
      physicalNetwork=current;
      setUnderlyingNetworks(new android.net.Network[]{current});
      return true;
    }
    catch(IOException e) { return false; }""", """    if (rawFd < 0 || rawFd > Integer.MAX_VALUE) return false;
    try {
      if (!protect((int) rawFd)) return false;
      android.net.Network current = choosePhysicalNetwork();
      if (current == null) return false;
      try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.fromFd((int) rawFd)) {
        current.bindSocket(descriptor.getFileDescriptor());
      }
      synchronized (this) {
        if (!current.equals(physicalNetwork) &&
            setUnderlyingNetworks(new android.net.Network[]{current})) {
          physicalNetwork = current;
        }
      }
      return true;
    } catch (IOException | RuntimeException rejected) {
      // A rejected socket must be reported through the Go callback's boolean result.
      return false;
    }""")

if service.read_text().count(".addDisallowedApplication(getPackageName())") != 1:
    raise SystemExit("Automatic direct sockets require the service UID to bypass its VPN")
