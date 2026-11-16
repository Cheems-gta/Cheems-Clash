package win.cheems.core.net;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransportCapabilitiesTest {
  @Test public void supportedTcpTransportsAreAdvertised() {
    for (String p : new String[]{"ss","ss2022","trojan","socks5","http"}) {
      assertTrue(p, TransportCapabilities.canDialTcp(p));
    }
  }
  @Test public void importedProtocolsMustNotClaimWorkingConnections() {
    for (String p : new String[]{"hysteria2","hy2"}) {
      assertEquals(TransportCapabilities.Status.PARSE_ONLY,TransportCapabilities.status(p));
      assertFalse(p,TransportCapabilities.canDialTcp(p));
    }
    assertEquals(TransportCapabilities.Status.TCP_READY,TransportCapabilities.status("anytls"));
    assertTrue(TransportCapabilities.canDialTcp("anytls"));
    assertEquals(TransportCapabilities.Status.TCP_READY,TransportCapabilities.status("vless"));
    assertTrue(TransportCapabilities.canDialTcp("vless"));
    assertEquals(TransportCapabilities.Status.TCP_READY,TransportCapabilities.status("vmess"));
    assertTrue(TransportCapabilities.canDialTcp("vmess"));
  }
  @Test public void quicRequirementsAreExplicit() {
    assertTrue(TransportCapabilities.requiresQuic("hysteria2"));
    assertTrue(TransportCapabilities.requiresQuic("hy2"));
    assertTrue(TransportCapabilities.requiresQuic("tuic"));
    assertFalse(TransportCapabilities.requiresQuic("anytls"));
  }
  @Test public void socks5UdpIsNativeOnlyAndOtherUdpRemainsUnadvertised() {
    for(String p:new String[]{"ss","trojan","hysteria2","hy2","anytls","vless"})
      assertFalse(TransportCapabilities.canDialUdp(p));
    assertTrue(TransportCapabilities.canDialUdp("socks5"));
    assertTrue(TransportCapabilities.canDialUdp("vmess"));
    assertTrue(TransportCapabilities.canDialUdp("ss2022"));
    assertTrue(TransportCapabilities.canDialUdp("tuic"));
  }
}

