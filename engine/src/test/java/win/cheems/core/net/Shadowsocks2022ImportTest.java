package win.cheems.core.net;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public class Shadowsocks2022ImportTest {
  private static String link(String method, byte[] key) {
    String credentials = method + ":" + Base64.getEncoder().encodeToString(key);
    String encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    return "ss://" + encoded + "@edge.example:443#ss2022";
  }

  @Test public void importsSupported2022Aes128Tcp() throws Exception {
    Node node = Subscription.parse(link("2022-blake3-aes-128-gcm", "0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).get(0);
    assertTrue(node.unsupported, node.supported());
    assertEquals("ss2022", node.type);
    assertEquals("2022-blake3-aes-128-gcm", node.cipher);
    assertEquals("edge.example", node.host);
  }

  @Test public void rejectsWrongKeyLengthAndUnknown2022Cipher() throws Exception {
    Node shortKey = Subscription.parse(link("2022-blake3-aes-128-gcm", new byte[]{1, 2, 3})).get(0);
    assertFalse(shortKey.supported());
    assertTrue(shortKey.unsupported.contains("PSK"));

    Node unknown = Subscription.parse(link("2022-blake3-unknown", "0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).get(0);
    assertFalse(unknown.supported());
  }
}
