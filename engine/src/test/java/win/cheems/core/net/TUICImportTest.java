package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class TUICImportTest {
  private static final String BASE="tuic://d342d11e-d424-4583-b36b-524ab1f0afa4:p%3Aa%2Bss@edge.example:443";
  @Test public void preservesV5CredentialsAndRelayOptions() throws Exception {
    Node n=Subscription.parse(BASE+"?sni=tls.example&alpn=h3&congestion_control=bbr&udp_relay_mode=quic#test").get(0);
    assertTrue(n.unsupported,n.supported());
    assertEquals("p:a+ss",n.password);assertEquals("tls.example",n.sni);
    assertEquals("h3",n.tuicALPN());assertEquals("quic",Node.value(n.source,"udp-relay-mode",""));
  }
  @Test public void rejectsUnsupportedAndMalformedOptions() throws Exception {
    for(String query:new String[]{"zero_rtt=1","alpn=","udp_relay_mode=unknown","congestion_control=unknown"})
      assertFalse(query,Subscription.parse(BASE+"?"+query).get(0).supported());
  }
  @Test public void importsYamlAlpnList() throws Exception {
    Node n=Subscription.parse("proxies:\n  - name: test\n    type: tuic\n    server: edge.example\n    port: 443\n    uuid: d342d11e-d424-4583-b36b-524ab1f0afa4\n    password: test\n    alpn: [h3, test]\n").get(0);
    assertTrue(n.unsupported,n.supported());assertEquals("h3,test",n.tuicALPN());
  }
}
