package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class RealityImportTest {
 private static final String BASE="vless://00112233-4455-6677-8899-aabbccddeeff@edge.example:443?security=reality&type=tcp&sni=front.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0102&fp=chrome";
 @Test public void preservesRealityCredentials() throws Exception {
  Node n=Subscription.parse(BASE).get(0);assertTrue(n.unsupported,n.supported());assertTrue(n.isReality());assertEquals("0102",n.realityShortID());assertEquals("chrome",n.realityFingerprint());assertEquals("front.example",n.sni);
 }
 @Test public void rejectsInvalidKeysAndUnsupportedCombinations() throws Exception {
  for(String link:new String[]{BASE.replace("sid=0102","sid=xyz"),BASE.replace("type=tcp","type=ws"),BASE+"&flow=xtls-rprx-vision",BASE.replace("fp=chrome","fp=unknown"),BASE.replace("pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","pbk=short")}) assertFalse(link,Subscription.parse(link).get(0).supported());
 }
 @Test public void acceptsYamlRealityOptions() throws Exception {
  Node n=Subscription.parse("proxies:\n  - name: test\n    type: vless\n    server: edge.example\n    port: 443\n    uuid: 00112233-4455-6677-8899-aabbccddeeff\n    tls: true\n    network: tcp\n    servername: front.example\n    client-fingerprint: chrome\n    reality-opts:\n      public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n      short-id: '0102'\n").get(0);
  assertTrue(n.unsupported,n.supported());assertEquals("0102",n.realityShortID());
 }
}
