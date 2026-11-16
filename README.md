# Cheems Clash · Cheems Core integration

Android app with the original Cheems Clash Compose UI and **independent Cheems Core** VPN service. The project no longer builds or links Mihomo.

## Native engine
The workflow checks out the Cheems Core source at commit `148b1034255a4a097695baf6c173c1f545a51ae1`, builds its gomobile Android AAR and compiles the Android VPN service/transport classes into the same application. No Mihomo module, Mihomo checkout, gVisor adapter from Mihomo, or Mihomo AAR remains in the build. Native QUIC/TLS, proxy forwarding, and TUN are provided by Cheems Core.

The selected node is started with the user's requested **TLS certificate verification bypass** compatibility policy, except Reality (separate identity verification). This does not disable encryption but it prevents normal certificate-based server authentication and may permit server impersonation.

## Feature differences
Imports Clash YAML proxy nodes using Cheems Core's own parser. Connect/disconnect, node selection, subscription downloads, automatic refresh, subscription usage, and VPN diagnostic logs use Cheems Core. Only protocols implemented by Cheems Core are selectable. Mihomo-specific proxy group rules, runtime selectors, hot switching, and Mihomo rule engine are not available; this integration does not claim otherwise.

## Build
GitHub Actions workflow `.github/workflows/android.yml` compiles the native engine and packages a debug APK. A successful CI build verifies compilation and APK signature, **not live VPN connectivity on your actual node**. The app identifier remains `com.cheems.clash`. A debug signature different from the installed previous version may require uninstalling the old APK (back up profile settings first).

Third-party license notices are copied into APK assets during build.
