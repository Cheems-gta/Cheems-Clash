package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebSocketImportTest {
 private static final String UUID="00112233-4455-6677-8899-aabbccddeeff";
 @Test public void preservesVlessWebSocketHostAndPath() throws Exception {
  Node n=Subscription.parse("vless://"+UUID+"@edge.example:443?security=tls&type=ws&host=cdn.example&path=%2Fproxy%3Fa%3D1").get(0);
  assertTrue(n.unsupported,n.supported());assertTrue(n.isWebSocket());assertEquals("cdn.example",n.websocketHost());assertEquals("/proxy?a=1",n.websocketPath());
 }
 @Test public void yamlWsOptionsArePreservedAndUnsupportedHeadersRejected() throws Exception {
  String yaml="proxies:\n  - name: test\n    type: vless\n    server: edge.example\n    port: 443\n    uuid: "+UUID+"\n    tls: true\n    network: ws\n    ws-opts:\n      path: /proxy\n      headers:\n        Host: cdn.example\n";
  Node n=Subscription.parse(yaml).get(0);assertTrue(n.unsupported,n.supported());assertEquals("/proxy",n.websocketPath());assertEquals("cdn.example",n.websocketHost());
  assertFalse(Subscription.parse(yaml.replace("Host: cdn.example","X-Custom: ignored")).get(0).supported());
  assertFalse(Subscription.parse(yaml.replace("path: /proxy","path: /proxy?ed=2048")).get(0).supported());
 }
}
