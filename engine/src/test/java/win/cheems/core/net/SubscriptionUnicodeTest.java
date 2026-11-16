package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class SubscriptionUnicodeTest {
  @Test public void emojiAcrossEveryReaderBoundary() throws Exception {
    for (int i = 0; i < 2048; i++) {
      String name = new String(new char[i]).replace('\0', 'x') + "🇨🇳😀";
      String yaml = "proxies:\n - {name: '" + name
          + "', type: hysteria2, server: example.com, port: 443, password: 'test😀'}\n";
      Node node = Subscription.parse(yaml).get(0);
      assertEquals(name, node.name);
      assertEquals("test😀", node.password);
      assertTrue(node.supported());
    }
  }
}
