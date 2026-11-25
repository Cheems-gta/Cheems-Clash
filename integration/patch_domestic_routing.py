"""Apply an explicit, user-visible Android app split-tunnel policy."""
from pathlib import Path

path = Path("app/src/main/java/win/cheems/core/CoreService.java")
source = path.read_text(encoding="utf-8")
anchor = '    markStage("申请 Android TUN 接口");'
replacement = '''    if (getSharedPreferences("profile", MODE_PRIVATE).getBoolean("domestic_app_bypass", true)) {
      if (android.os.Build.VERSION.SDK_INT >= 29 && isLockdownEnabled()) {
        throw new IOException("系统 VPN 已开启阻止非 VPN 连接；应用直连模式不可用");
      }
      String[] directApps = {"com.tencent.mm", "com.tencent.mobileqq", "tv.danmaku.bili"};
      for (String app : directApps) {
        try {
          builder.addDisallowedApplication(app);
          log("应用直连：" + app);
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
          // Not installed for this Android user.
        }
      }
    }
    markStage("申请 Android TUN 接口");'''
if source.count(anchor) != 1:
    raise SystemExit("Pinned CoreService VPN builder anchor mismatch")
path.write_text(source.replace(anchor, replacement, 1), encoding="utf-8")
print("Doreena: optional application split tunnel configured")
