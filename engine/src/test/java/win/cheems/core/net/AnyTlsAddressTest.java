package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
public class AnyTlsAddressTest {
 @Test public void domain() {
  assertArrayEquals(new byte[]{3,11,'e','x','a','m','p','l','e','.','c','o','m',1,(byte)187},
      AnyTlsAddress.encode("example.com",443));
 }
 @Test public void ipv4() {
  assertArrayEquals(new byte[]{1,127,0,0,1,0,80},AnyTlsAddress.encode("127.0.0.1",80));
 }
 @Test(expected=IllegalArgumentException.class) public void invalidPort() {
  AnyTlsAddress.encode("example.com",65536);
 }
 @Test(expected=IllegalArgumentException.class) public void tooLongDomain() {
  AnyTlsAddress.encode("a".repeat(256),443);
 }
}
