package win.cheems.core.net;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
public class AnyTlsWireTest {
 @Test public void roundTrip()throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  AnyTlsWire.writeFrame(out,new AnyTlsWire.Frame(AnyTlsWire.PSH,4294967295L,new byte[]{1,2,3}));
  AnyTlsWire.Frame f=AnyTlsWire.readFrame(new ByteArrayInputStream(out.toByteArray()));
  assertEquals(2,f.command);assertEquals(4294967295L,f.streamId);assertArrayEquals(new byte[]{1,2,3},f.data);
 }
 @Test public void authenticationLength()throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  AnyTlsWire.writeAuthentication(out,"test",new byte[30]);
  assertEquals(64,out.size());
  assertEquals(0,out.toByteArray()[32]);assertEquals(30,out.toByteArray()[33]);
 }
 @Test(expected=EOFException.class) public void truncatedFrameFails()throws Exception {
  AnyTlsWire.readFrame(new ByteArrayInputStream(new byte[]{2,0,0,0,1,0,2,1}));
 }
 @Test public void settingsVersion() {
  String s=new String(AnyTlsWire.settings("00000000000000000000000000000000"),java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(s.startsWith("v=2\nclient=Cheems-Core/"));
 }
}
