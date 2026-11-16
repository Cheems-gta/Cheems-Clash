package win.cheems.core.net;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class NodeHysteria2Test {
  private Node node(String type, Object... fields) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("type", type);
    config.put("server", "edge.example.com");
    config.put("port", 443);
    config.put("password", "secret");
    for (int i = 0; i + 1 < fields.length; i += 2)
      config.put((String) fields[i], fields[i + 1]);
    return new Node(config);
  }

  @Test public void normalHysteria2IsSelectableForNativeBuild() {
    assertTrue(node("hysteria2").unsupported, node("hysteria2").supported());
    assertEquals("hysteria2", node("hy2").type);
  }

  @Test public void providerInsecureHintIsRecognizedWithoutDisablingTlsVerification() {
    Node n = node("hysteria2", "skip-cert-verify", true);
    assertTrue("strict-TLS connection attempts remain selectable", n.supported());
    assertTrue("the UI must warn about the provider's insecure setting", n.hasInsecureTlsFlag());
  }

  @Test public void unsupportedFeaturesCannotBeSilentlyIgnored() {
    assertFalse(node("hysteria2", "obfs", "salamander").supported());
    assertFalse(node("hysteria2", "ports", "443,444").supported());
  }

  @Test public void invalidNodeDoesNotBecomeSelectable() {
    assertFalse(node("hysteria2", "password", "").supported());
    assertFalse(node("hysteria2", "port", -1).supported());
  }
}
