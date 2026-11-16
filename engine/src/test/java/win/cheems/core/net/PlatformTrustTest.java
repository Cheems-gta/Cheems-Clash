package win.cheems.core.net;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import org.junit.Test;

public class PlatformTrustTest {
  @Test public void exportsParseablePlatformRootsAndPreservesCustomCA() throws Exception {
    String roots = PlatformTrust.withCustomCA(null);
    Collection<?> parsed = CertificateFactory.getInstance("X.509").generateCertificates(
        new ByteArrayInputStream(roots.getBytes(StandardCharsets.US_ASCII)));
    assertFalse("Platform trust store must contain certificates", parsed.isEmpty());
    // Use a real certificate as a provider-specific root to check bundle concatenation.
    int end = roots.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length();
    String custom = roots.substring(0, end);
    String combined = PlatformTrust.withCustomCA(custom);
    assertTrue(combined.endsWith(custom));
    Collection<?> merged = CertificateFactory.getInstance("X.509").generateCertificates(
        new ByteArrayInputStream(combined.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(parsed.size() + 1, merged.size());
  }

  @Test public void rejectsMalformedProviderCAEvenWithValidPlatformRoots() throws Exception {
    try {
      PlatformTrust.withCustomCA("not a certificate");
      fail("Malformed provider CA accepted");
    } catch (GeneralSecurityException expected) {
      // A valid platform bundle must not hide a broken custom trust setting.
    }
  }

  @Test public void keepsProviderCABounded() throws Exception {
    try {
      PlatformTrust.withCustomCA("x".repeat(64 * 1024 + 1));
      fail("Oversized provider CA accepted");
    } catch (GeneralSecurityException expected) {
      assertTrue(expected.getMessage().contains("64 KiB"));
    }
  }
}
