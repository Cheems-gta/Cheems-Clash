from pathlib import Path
path = Path("app/src/main/java/win/cheems/core/CoreService.java")
s = path.read_text(encoding="utf-8")
def replace(old, new):
    global s
    if s.count(old) != 1:
        raise SystemExit("Physical network patch anchor mismatch: " + old[:80])
    s = s.replace(old, new, 1)
replace("    ConnectivityManager cm=getSystemService(ConnectivityManager.class);\n    android.net.Network active=cm.getActiveNetwork();\n    NetworkCapabilities activeCaps=active==null?null:cm.getNetworkCapabilities(active);\n    if(activeCaps!=null && activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)\n        && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active;\n    for(android.net.Network candidate:cm.getAllNetworks()) {\n      NetworkCapabilities caps=cm.getNetworkCapabilities(candidate);\n      if(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)\n          && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return candidate;\n    }\n    return null;", "    ConnectivityManager cm=getSystemService(ConnectivityManager.class);\n    if(cm==null) return null;\n    android.net.Network active=cm.getActiveNetwork();\n    NetworkCapabilities activeCaps=active==null?null:cm.getNetworkCapabilities(active);\n    // Honor the Android default physical network even when regional\n    // connectivity validation is unavailable.\n    if(isPhysicalInternet(activeCaps)) return active;\n    android.net.Network previous=physicalNetwork;\n    NetworkCapabilities previousCaps=previous==null?null:cm.getNetworkCapabilities(previous);\n    if(isPhysicalInternet(previousCaps) && previousCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))\n      return previous;\n    android.net.Network fallback=isPhysicalInternet(previousCaps)?previous:null;\n    for(android.net.Network candidate:cm.getAllNetworks()) {\n      NetworkCapabilities caps=cm.getNetworkCapabilities(candidate);\n      if(!isPhysicalInternet(caps)) continue;\n      if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return candidate;\n      if(fallback==null) fallback=candidate;\n    }\n    return fallback;")
replace("  private android.net.Network physicalNetwork;", """  private volatile android.net.Network physicalNetwork;
  private android.net.Network publishedUnderlyingNetwork;

  private static boolean isPhysicalInternet(NetworkCapabilities caps) {
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
  }

  private synchronized void publishUnderlyingNetwork(android.net.Network network, boolean force) {
    if (force || !network.equals(publishedUnderlyingNetwork)) {
      if (setUnderlyingNetworks(new android.net.Network[]{network}))
        publishedUnderlyingNetwork = network;
    }
  }""")
replace("      setUnderlyingNetworks(new android.net.Network[]{current});", "      publishUnderlyingNetwork(current, false);")
replace("      setUnderlyingNetworks(new android.net.Network[]{physicalNetwork});", "      publishUnderlyingNetwork(physicalNetwork, true);")
path.write_text(s, encoding="utf-8")
print("Physical network preference and duplicate update reduction applied")
