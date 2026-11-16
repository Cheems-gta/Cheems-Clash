package win.cheems.core.net;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class NodeVLESSTest {
  private static final String UUID = "00112233-4455-6677-8899-aabbccddeeff";

  @Test public void vlessTlsTcpShareLinkIsImportedAndSelectable() throws Exception {
    Node node = Subscription.parse("vless://" + UUID + "@edge.example.com:443?type=tcp&security=tls&sni=front.example.com#sample").get(0);
    assertEquals("vless", node.type);
    assertEquals(UUID, node.uuid);
    assertEquals("front.example.com", node.sni);
    assertTrue(node.tls());
    assertTrue(node.unsupported, node.supported());
    assertEquals(TransportCapabilities.Status.TCP_READY, TransportCapabilities.status("vless"));
  }

  @Test public void clashVlessTlsTcpNodeIsAccepted() throws Exception {
    String yaml = "proxies:\n"
        + "  - name: VLESS sample\n"
        + "    type: vless\n"
        + "    server: edge.example.com\n"
        + "    port: 443\n"
        + "    uuid: " + UUID + "\n"
        + "    tls: true\n"
        + "    servername: front.example.com\n"
        + "    network: tcp\n";
    List<Node> nodes = Subscription.parse(yaml);
    assertEquals(1, nodes.size());
    assertTrue(nodes.get(0).unsupported, nodes.get(0).supported());
  }

  @Test public void realityWebsocketFlowAndMissingTlsAreRejected() throws Exception {
    assertFalse(Subscription.parse("vless://" + UUID + "@edge.example.com:443?type=tcp&security=reality").get(0).supported());
    assertTrue(Subscription.parse("vless://" + UUID + "@edge.example.com:443?type=ws&security=tls").get(0).supported());
    assertFalse(Subscription.parse("vless://" + UUID + "@edge.example.com:443?type=tcp&security=none").get(0).supported());
    assertFalse(Subscription.parse("vless://not-a-uuid@edge.example.com:443?type=tcp&security=tls").get(0).supported());
    assertFalse(Subscription.parse("vless://" + UUID + "@edge.example.com:443?type=tcp&security=tls&flow=xtls-rprx-vision").get(0).supported());
  }
}
