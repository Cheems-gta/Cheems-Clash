package win.cheems.core.net;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class CoreTest {
  @Test
  public void clashSubscriptionKeepsSupportedHy2Configuration() throws Exception {
    List<Node> n =
        Subscription.parse(
            "proxies:\n"
                + " - {name: test, type: ss, server: localhost, port: 1234, cipher: aes-128-gcm,"
                + " password: secret}\n"
                + " - {name: modern, type: hysteria2, server: localhost, port: 443, password:"
                + " secret}\n");
    assertEquals(2, n.size());
    assertTrue(n.get(0).supported());
    assertTrue("HY2 configuration is supported by integrated builds", n.get(1).supported());
  }

  @Test
  public void base64AndEscapedCredentials() throws Exception {
    String uri = "trojan://p%40ss%2Bword@example.com:443?sni=example.com#test";
    Node n =
        Subscription.parse(Base64.getEncoder().encodeToString(uri.getBytes(StandardCharsets.UTF_8)))
            .get(0);
    assertEquals("p@ss+word", n.password);
    assertTrue(n.supported());
    String ss =
        "ss://"
            + Base64.getEncoder()
                .encodeToString("aes-256-gcm:secret".getBytes(StandardCharsets.UTF_8))
            + "@example.com:443#name";
    assertEquals("aes-256-gcm", Subscription.parse(ss).get(0).cipher);
  }

  @Test
  public void pluginsAreRejectedAndNodeTlsPolicyIsPreserved() throws Exception {
    assertFalse(Subscription.parse("trojan://password@example.com:443?type=ws").get(0).supported());
    Node insecure = Subscription.parse("trojan://password@example.com:443?allowInsecure=1").get(0);
    assertTrue(insecure.unsupported, insecure.supported());
    assertEquals("true", Node.value(insecure.source, "skip-cert-verify", "false"));
    assertFalse(
        Subscription.parse("ss://YWVzLTEyOC1nY206cA@example.com:443?plugin=obfs-local")
            .get(0)
            .supported());
  }

  @Test
  public void yamlObjectConstructionIsRejected() throws Exception {
    try {
      Subscription.parse("proxies: !!java.net.URL [https://example.com]");
      fail();
    } catch (org.yaml.snakeyaml.error.YAMLException expected) {
    }
  }

  @Test
  public void socksHandshakeAndActualRelay() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<Void> peer =
          CompletableFuture.runAsync(
              () -> {
                try (Socket s = server.accept()) {
                  s.setSoTimeout(3000);
                  InputStream in = s.getInputStream();
                  OutputStream out = s.getOutputStream();
                  assertArrayEquals(new byte[] {5, 1, 0}, Aead.readFully(in, 3));
                  out.write(new byte[] {5, 0});
                  out.flush();
                  assertArrayEquals(new byte[] {5, 1, 0, 3}, Aead.readFully(in, 4));
                  int count = in.read();
                  assertEquals(
                      "example.com",
                      new String(Aead.readFully(in, count), StandardCharsets.US_ASCII));
                  assertArrayEquals(new byte[] {0, 80}, Aead.readFully(in, 2));
                  out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                  out.flush();
                  assertEquals(
                      "ping", new String(Aead.readFully(in, 4), StandardCharsets.US_ASCII));
                  out.write("pong".getBytes(StandardCharsets.US_ASCII));
                  out.flush();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      Map<String, Object> m = new HashMap<>();
      m.put("type", "socks5");
      m.put("server", "127.0.0.1");
      m.put("port", server.getLocalPort());
      try (Dialer.Tunnel t = Dialer.connect(new Node(m), "example.com", 80, s -> {})) {
        t.out.write("ping".getBytes(StandardCharsets.US_ASCII));
        t.out.flush();
        assertEquals("pong", new String(Aead.readFully(t.in, 4), StandardCharsets.US_ASCII));
      }
      peer.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void allAeadCiphersHandleChunkBoundariesAndTampering() throws Exception {
    for (String cipher : Arrays.asList("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")) {
      Map<String, Object> m = new HashMap<>();
      m.put("type", "ss");
      m.put("server", "example.com");
      m.put("port", 443);
      m.put("cipher", cipher);
      m.put("password", "test-password");
      Node n = new Node(m);
      byte[] payload = new byte[50000];
      new Random(123).nextBytes(payload);
      ByteArrayOutputStream wire = new ByteArrayOutputStream();
      OutputStream enc = Aead.output(n, wire);
      enc.write(payload);
      enc.flush();
      assertArrayEquals(
          payload,
          Aead.readFully(
              Aead.input(n, new ByteArrayInputStream(wire.toByteArray())), payload.length));
      byte[] broken = wire.toByteArray();
      broken[broken.length - 1] ^= 1;
      try {
        Aead.readFully(Aead.input(n, new ByteArrayInputStream(broken)), payload.length);
        fail("Tampering was accepted");
      } catch (IOException expected) {
      }
    }
  }

  @Test
  public void independentPythonAeadFixtures() throws Exception {
    {
      Map<String, Object> m = new HashMap<>();
      m.put("type", "ss");
      m.put("server", "localhost");
      m.put("port", 1234);
      m.put("password", "test-password");
      m.put("cipher", "aes-128-gcm");
      String hex =
          "000102030405060708090a0b0c0d0e0fe8dfd18338eb89097fab3db5feab717abac2a7bda69394187b0ffc2a960b5e68fe6288f35471141451b5d924701185631773c6e7fb2d0a324047ba89345fa55a37";
      byte[] wire = new byte[hex.length() / 2];
      for (int i = 0; i < wire.length; i++)
        wire[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      assertEquals(
          "Independent Python peer fixture",
          new String(
              Aead.readFully(Aead.input(new Node(m), new ByteArrayInputStream(wire)), 31),
              StandardCharsets.UTF_8));
    }
    {
      Map<String, Object> m = new HashMap<>();
      m.put("type", "ss");
      m.put("server", "localhost");
      m.put("port", 1234);
      m.put("password", "test-password");
      m.put("cipher", "aes-256-gcm");
      String hex =
          "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1fe0cfa85d7cf6fe3302eaacbc22103dbe7b38e1792e89da2802b9e8ae0ccd9c214c42e32394911a72351205a3c89c38ac638c0475097322eabefe484dd42811888a";
      byte[] wire = new byte[hex.length() / 2];
      for (int i = 0; i < wire.length; i++)
        wire[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      assertEquals(
          "Independent Python peer fixture",
          new String(
              Aead.readFully(Aead.input(new Node(m), new ByteArrayInputStream(wire)), 31),
              StandardCharsets.UTF_8));
    }
    {
      Map<String, Object> m = new HashMap<>();
      m.put("type", "ss");
      m.put("server", "localhost");
      m.put("port", 1234);
      m.put("password", "test-password");
      m.put("cipher", "chacha20-ietf-poly1305");
      String hex =
          "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f7a0012bcbdb041c8d5bcbac16b82ab4aac9fbeb7319a2640c33417e7f9fa973bb9e27b1313322e8ade121315b3327fe42bc16d96c7f0cb2bcfaf66990a5b8006df";
      byte[] wire = new byte[hex.length() / 2];
      for (int i = 0; i < wire.length; i++)
        wire[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      assertEquals(
          "Independent Python peer fixture",
          new String(
              Aead.readFully(Aead.input(new Node(m), new ByteArrayInputStream(wire)), 31),
              StandardCharsets.UTF_8));
    }
  }
}
