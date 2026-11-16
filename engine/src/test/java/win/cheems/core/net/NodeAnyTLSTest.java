package win.cheems.core.net;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class NodeAnyTLSTest {
  private Node node(Object... entries) {
    Map<String, Object> config = new HashMap<>();
    config.put("type", "anytls");
    config.put("server", "node.example");
    config.put("port", 443);
    config.put("password", "secret");
    for (int i = 0; i < entries.length; i += 2) config.put((String) entries[i], entries[i + 1]);
    return new Node(config);
  }

  @Test public void standardTlsAnyTlsNodeIsSelectable() {
    Node n = node();
    assertTrue(n.unsupported, n.supported());
    assertTrue(n.tls());
    assertEquals(TransportCapabilities.Status.TCP_READY, TransportCapabilities.status(n.type));
  }

  @Test public void skipCertificateHintDoesNotBlockImport() {
    Node n = node("skip-cert-verify", true);
    assertTrue(n.unsupported, n.supported());
    assertTrue(n.hasInsecureTlsFlag());
  }

  @Test public void missingPasswordAndUnsupportedFingerprintFailClosed() {
    assertFalse(node("password", "").supported());
    assertFalse(node("client-fingerprint", "unknown-browser").supported());
  }
  @Test public void commonFingerprintsAndALPNAreAccepted() throws Exception {
    for (String fp : new String[]{"chrome", "firefox", "safari", "iOS", "android", "edge", "360", "qq", "random"}) {
      Node n = node("client-fingerprint", fp, "alpn", java.util.Arrays.asList("h2", "http/1.1"));
      assertTrue(n.unsupported, n.supported());
      assertEquals("h2,http/1.1", n.anyTLSALPN());
      Node uri = Subscription.parse("anytls://p%40ss@node.example?fp=" + fp + "&alpn=h2%2Chttp%2F1.1#test").get(0);
      assertTrue(uri.unsupported, uri.supported());
      assertEquals(443, uri.port);
      assertEquals("p@ss", uri.password);
      assertEquals(n.anyTLSFingerprint(), uri.anyTLSFingerprint());
      assertEquals(n.anyTLSALPN(), uri.anyTLSALPN());
    }
  }

  @Test public void connectionPolicySkipsCertificateChecksWithoutChangingSavedNode() {
    Node original = node("client-fingerprint", "chrome", "pinSHA256", "old-pin", "ca-str", "old-ca", "sni", "provider.example");
    Node connection = original.withoutCertificateChecks();
    assertTrue(connection.unsupported, connection.supported());
    assertTrue(connection.hasInsecureTlsFlag());
    assertEquals("", connection.certificateSHA256());
    assertEquals("", connection.trustedCAPEM());
    assertEquals("chrome", connection.anyTLSFingerprint());
    assertEquals("provider.example", connection.sni);
    assertEquals(original.password, connection.password);
    assertFalse(original.hasInsecureTlsFlag());
    assertEquals("old-pin", original.certificateSHA256());
  }
  @Test public void ipv6URIKeepsAnUnbracketedAddressForNativeDialer() throws Exception {
    Node n = Subscription.parse("anytls://secret@[2001:db8::1]:8443?fp=chrome").get(0);
    assertTrue(n.unsupported, n.supported());
    assertEquals("2001:db8::1", n.host);
    assertEquals(8443, n.port);
  }

}

