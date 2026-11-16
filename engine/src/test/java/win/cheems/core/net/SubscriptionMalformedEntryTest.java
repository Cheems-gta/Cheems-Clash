package win.cheems.core.net;
import static org.junit.Assert.*;
import org.junit.Test;
public class SubscriptionMalformedEntryTest {
  @Test public void emptySocksCredentialsDoNotCrashParser() throws Exception {
    assertEquals(1, Subscription.parse("socks5://@example.com:1080#no-user").size());
  }
  @Test public void emptyTuicCredentialsDoNotCrashParser() throws Exception {
    assertEquals(1, Subscription.parse("tuic://@example.com:443#empty").size());
  }
  @Test public void malformedShareLinkDoesNotDiscardValidNodes() throws Exception {
    String text="not-a-node\nhysteria2://demo@example.com:443#valid\n";
    assertEquals(1,Subscription.parse(text).size());
  }
  @Test public void yamlWithInvalidAndValidEntriesRetainsValidNode() throws Exception {
    String yaml="proxies:\n  - {name: invalid, type: hysteria2, server: example.com, port: bad}\n"+
      "  - {name: valid, type: hysteria2, server: example.com, port: 443, password: demo}\n";
    assertEquals(2,Subscription.parse(yaml).size());
  }
  @Test public void allMalformedRecordsProduceClearMessage() {
    try {Subscription.parse("not-a-node\n");fail("must reject");}
    catch(Exception e){assertTrue(e.getMessage().contains("未识别到有效节点"));}
  }
}
