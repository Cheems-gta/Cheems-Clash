package win.cheems.core.net;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public class VMessImportTest {
  private static String link(String json) {
    return "vmess://" + Base64.getEncoder().withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test public void importsSupportedVmessAeadTcpTls() throws Exception {
    Node node = Subscription.parse(link(
        "{\"v\":\"2\",\"ps\":\"test node\",\"add\":\"edge.example\",\"port\":443," +
        "\"id\":\"d342d11e-d424-4583-b36b-524ab1f0afa4\",\"aid\":\"0\"," +
        "\"scy\":\"aes-128-gcm\",\"net\":\"tcp\",\"type\":\"none\"," +
        "\"host\":\"edge.example\",\"tls\":\"tls\",\"sni\":\"edge.example\"}")).get(0);
    assertTrue(node.unsupported, node.supported());
    assertEquals("vmess", node.type);
    assertEquals("d342d11e-d424-4583-b36b-524ab1f0afa4", node.uuid);
    assertEquals("aes-128-gcm", node.cipher);
    assertTrue(node.tls());
  }

  @Test public void importsVmessWebSocket() throws Exception {
    Node node = Subscription.parse(link(
        "{\"ps\":\"ws\",\"add\":\"edge.example\",\"port\":443," +
        "\"id\":\"d342d11e-d424-4583-b36b-524ab1f0afa4\",\"aid\":0," +
        "\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"tls\":\"tls\"}")).get(0);
    assertTrue(node.unsupported,node.supported());
    assertTrue(node.isWebSocket());
  }
}
