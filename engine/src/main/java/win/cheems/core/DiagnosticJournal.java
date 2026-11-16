package win.cheems.core;

import android.content.SharedPreferences;
import java.util.ArrayDeque;
import win.cheems.core.net.Node;

/** Bounded private journal, with endpoint and credential redaction before persistence. */
final class DiagnosticJournal {
  private final ArrayDeque<String> entries = new ArrayDeque<>();
  private final SharedPreferences preferences;

  DiagnosticJournal(SharedPreferences preferences) {
    this.preferences = preferences;
    if (preferences != null) {
      for (String entry : preferences.getString("entries", "").split("\n")) {
        if (!entry.isEmpty()) add(entry);
      }
    }
  }

  private void add(String entry) {
    if (entries.size() >= 40) entries.removeFirst();
    entries.addLast(entry.length() > 800 ? entry.substring(0, 800) : entry);
  }

  synchronized void append(String text, Node node, String... secrets) {
    String safe = text == null ? "未知事件" : text;
    if (node != null) {
      for (String value : new String[]{node.password, node.uuid, node.username, node.host, node.name})
        if (value != null && !value.isEmpty()) safe = safe.replace(value, "[已隐藏]");
    }
    for (String secret : secrets)
      if (secret != null && !secret.isEmpty()) safe = safe.replace(secret, "[已隐藏]");
    safe = safe.replaceAll("(?i)(?:https?|anytls|hysteria2|vless|vmess)://\\S+", "[地址已隐藏]");
    add(safe.replace('\n', ' ').replace('\r', ' '));
    if (preferences != null) preferences.edit().putString("entries", snapshot()).commit();
  }

  synchronized String snapshot() { return String.join("\n", entries); }
}
