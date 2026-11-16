package win.cheems.core.net;

import static org.junit.Assert.*;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class Hysteria2DialerTest {
  @Test public void hy2TrafficUsesLocalNativeSocksRatherThanDirectConnection() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<String> destination = new AtomicReference<>();
    ServerSocket listener = new ServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress("127.0.0.1", Hysteria2Native.LOCAL_SOCKS_PORT));
    listener.setSoTimeout(5000);
    // The native HY2 bridge now requires an ephemeral local SOCKS password.
    // Use a synthetic token; never load a real provider subscription in tests.
    final String fakeLocalToken = "synthetic-hy2-loopback-secret-0123456789";
    Field tokenField = Hysteria2Native.class.getDeclaredField("localToken");
    tokenField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> tokenReference = (AtomicReference<String>) tokenField.get(null);
    String previousToken = tokenReference.getAndSet(fakeLocalToken);
    Thread peer = new Thread(() -> {
      try (Socket conn = listener.accept()) {
        conn.setSoTimeout(5000);
        DataInputStream in = new DataInputStream(conn.getInputStream());
        OutputStream out = conn.getOutputStream();
        assertEquals(5, in.readUnsignedByte());
        int nMethods = in.readUnsignedByte();
        byte[] methods = new byte[nMethods];
        in.readFully(methods);
        assertArrayEquals(new byte[] {2}, methods);
        out.write(new byte[] {5, 2});
        out.flush();
        assertEquals(1, in.readUnsignedByte());
        byte[] user = new byte[in.readUnsignedByte()];
        in.readFully(user);
        assertEquals("cheems", new String(user, StandardCharsets.US_ASCII));
        byte[] pass = new byte[in.readUnsignedByte()];
        in.readFully(pass);
        assertEquals(fakeLocalToken, new String(pass, StandardCharsets.US_ASCII));
        out.write(new byte[] {1, 0});
        out.flush();
        assertEquals(5, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedByte());
        assertEquals(0, in.readUnsignedByte());
        assertEquals(3, in.readUnsignedByte());
        int length = in.readUnsignedByte();
        byte[] host = new byte[length];
        in.readFully(host);
        int port = in.readUnsignedShort();
        destination.set(new String(host, StandardCharsets.US_ASCII) + ":" + port);
        out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        byte[] ping = new byte[4];
        in.readFully(ping);
        assertEquals("ping", new String(ping, StandardCharsets.US_ASCII));
        out.write("pong".getBytes(StandardCharsets.US_ASCII));
      } catch (Throwable e) {
        failure.set(e);
      }
    }, "fake-hy2-socks");
    peer.start();
    try {
      Map<String,Object> config = new HashMap<>();
      config.put("type", "hysteria2");
      config.put("server", "unresolvable-proxy.invalid");
      config.put("port", 443);
      config.put("password", "not-a-real-password");
      Node node = new Node(config);
      assertTrue(node.unsupported, node.supported());
      try (Dialer.Tunnel tunnel = Dialer.connect(node, "target.example", 443, s -> {})) {
        tunnel.out.write("ping".getBytes(StandardCharsets.US_ASCII));
        tunnel.out.flush();
        byte[] pong = new byte[4];
        new DataInputStream(tunnel.in).readFully(pong);
        assertEquals("pong", new String(pong, StandardCharsets.US_ASCII));
      }
    } finally {
      listener.close();
      peer.join(6000);
      tokenReference.set(previousToken);
    }
    if (failure.get() != null) throw new AssertionError("Fake SOCKS5 relay failed", failure.get());
    assertEquals("target.example:443", destination.get());
  }
}
