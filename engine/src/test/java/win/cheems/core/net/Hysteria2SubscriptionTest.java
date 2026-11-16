package win.cheems.core.net;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

/** Import and capability tests using synthetic credentials, never real subscriptions. */
public final class Hysteria2SubscriptionTest {
  @Test public void clashHysteria2NodeKeepsTlsParameters() throws Exception {
    String yaml =
        "proxies:\n"
            + "  - name: HY2 sample\n"
            + "    type: hysteria2\n"
            + "    server: edge.example.com\n"
            + "    port: 443\n"
            + "    password: test-only-secret\n"
            + "    sni: tls.example.com\n";
    List<Node> nodes = Subscription.parse(yaml);
    assertEquals(1, nodes.size());
    Node n = nodes.get(0);
    assertEquals("hysteria2", n.type);
    assertEquals("edge.example.com", n.host);
    assertEquals(443, n.port);
    assertEquals("tls.example.com", n.sni);
    assertEquals("test-only-secret", n.password);
    assertTrue("plain HY2 node should be eligible for native engine", n.supported());
  }

  @Test public void hy2ShareLinkPreservesEncodedCredential() throws Exception {
    Node n = Subscription.parse(
        "hy2://pass%2Bword%3Aone@edge.example.com:443?sni=tls.example.com#sample"
    ).get(0);
    assertEquals("hysteria2", n.type);
    assertEquals("pass+word:one", n.password);
    assertEquals("tls.example.com", n.sni);
    assertTrue(n.supported());
  }

  @Test public void unsupportedObfuscationFailsClosed() throws Exception {
    String yaml =
        "proxies:\n"
            + "  - {name: obfs, type: hysteria2, server: edge.example.com, port: 443, "
            + "password: test-only, obfs: salamander, obfs-password: test-obfs}\n";
    Node n = Subscription.parse(yaml).get(0);
    assertFalse(n.supported());
    assertTrue(n.unsupported.contains("混淆"));
  }

  @Test public void providerInsecureHintDoesNotSilentlyTurnOffTls() throws Exception {
    Node n = Subscription.parse(
        "hysteria2://test-only@edge.example.com:443?insecure=1"
    ).get(0);
    assertTrue("candidate can try fully verified TLS", n.supported());
    assertTrue("UI must explain provider insecure TLS hint", n.hasInsecureTlsFlag());
  }

  @Test public void incompleteNodeFailsClosed() throws Exception {
    Node n = Subscription.parse(
        "hysteria2://@edge.example.com:443#missing-password"
    ).get(0);
    assertFalse(n.supported());
  }
}
