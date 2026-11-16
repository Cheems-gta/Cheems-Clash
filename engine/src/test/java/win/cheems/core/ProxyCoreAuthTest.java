package win.cheems.core;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public final class ProxyCoreAuthTest {
  private static final String SECRET = "test-only-ephemeral-local-proxy-secret-123";

  @Test public void acceptsValidBasicAuthForLocalConnect() {
    ProxyCore core = new ProxyCore(s -> {}, SECRET);
    String token = Base64.getEncoder().encodeToString(
        ("cheems:" + SECRET).getBytes(StandardCharsets.UTF_8));
    assertTrue(core.authorized("CONNECT example.com:443 HTTP/1.1\r\n"
        + "Proxy-Authorization: Basic " + token + "\r\n\r\n"));
  }

  @Test public void rejectsUnauthenticatedAndIncorrectRequests() {
    ProxyCore core = new ProxyCore(s -> {}, SECRET);
    assertFalse(core.authorized("CONNECT example.com:443 HTTP/1.1\r\n\r\n"));
    String bad = Base64.getEncoder().encodeToString(
        "cheems:wrong-secret".getBytes(StandardCharsets.UTF_8));
    assertFalse(core.authorized("CONNECT example.com:443 HTTP/1.1\r\n"
        + "Proxy-Authorization: Basic " + bad + "\r\n\r\n"));
  }
}
