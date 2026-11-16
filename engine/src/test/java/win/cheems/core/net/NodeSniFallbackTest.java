package win.cheems.core.net;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class NodeSniFallbackTest {
  private Map<String,Object> base() {
    Map<String,Object> node=new LinkedHashMap<>();
    node.put("name","test");node.put("type","hysteria2");node.put("server","192.0.2.10");
    node.put("port",443);node.put("password","example");return node;
  }
  @Test public void usesServernameWhenSniMissing() {
    Map<String,Object> m=base();m.put("servername","edge.example.org");
    assertEquals("edge.example.org",new Node(m).sni);
  }
  @Test public void usesPeerWhenSniAndServernameMissing() {
    Map<String,Object> m=base();m.put("peer","edge.example.org");
    assertEquals("edge.example.org",new Node(m).sni);
  }
  @Test public void blankSniFallsBackToServername() {
    Map<String,Object> m=base();m.put("sni"," ");m.put("servername","edge.example.org");
    assertEquals("edge.example.org",new Node(m).sni);
  }
  @Test public void explicitSniOverridesAliases() {
    Map<String,Object> m=base();m.put("sni","tls.example.org");m.put("peer","edge.example.org");
    assertEquals("tls.example.org",new Node(m).sni);
  }
  @Test public void noAliasesFallsBackToServer() {
    assertEquals("192.0.2.10",new Node(base()).sni);
  }
}
