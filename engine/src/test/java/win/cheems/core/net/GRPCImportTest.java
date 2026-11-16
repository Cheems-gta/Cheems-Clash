package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class GRPCImportTest {
 private static final String BASE="vless://00112233-4455-6677-8899-aabbccddeeff@edge.example:443?security=tls&type=grpc";
 @Test public void preservesServiceAndAuthority() throws Exception {
  Node n=Subscription.parse(BASE+"&serviceName=proxy.Service&authority=cdn.example").get(0);
  assertTrue(n.unsupported,n.supported());assertTrue(n.isGRPC());assertEquals("proxy.Service",n.grpcServiceName());assertEquals("cdn.example",n.grpcAuthority());
 }
 @Test public void rejectsUnsupportedModesAndInvalidService() throws Exception {
  assertFalse(Subscription.parse(BASE+"&serviceName=proxy&mode=multi").get(0).supported());
  assertFalse(Subscription.parse(BASE+"&serviceName=..%2Fproxy").get(0).supported());
 }
 @Test public void acceptsYamlServiceOptions() throws Exception {
  Node n=Subscription.parse("proxies:\n  - name: test\n    type: vless\n    server: edge.example\n    port: 443\n    uuid: 00112233-4455-6677-8899-aabbccddeeff\n    tls: true\n    network: grpc\n    grpc-opts:\n      grpc-service-name: proxy\n").get(0);
  assertTrue(n.unsupported,n.supported());assertEquals("proxy",n.grpcServiceName());
 }
}
