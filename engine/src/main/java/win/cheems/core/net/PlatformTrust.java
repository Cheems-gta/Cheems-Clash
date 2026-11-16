package win.cheems.core.net;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Exports the platform's trusted roots to Go, which cannot read Android's Java trust store. */
public final class PlatformTrust {
  private static volatile String cachedRoots;
  private PlatformTrust() {}

  public static String withCustomCA(String custom) throws GeneralSecurityException {
    if (custom == null) custom = "";
    if (custom.length() > 64 * 1024)
      throw new GeneralSecurityException("Custom CA certificate exceeds 64 KiB");
    if (!custom.isBlank() && CertificateFactory.getInstance("X.509").generateCertificates(
        new ByteArrayInputStream(custom.getBytes(StandardCharsets.UTF_8))).isEmpty())
      throw new GeneralSecurityException("Custom CA contains no certificates");
    String combined = roots() + "\n" + custom;
    if (combined.length() > 2 * 1024 * 1024)
      throw new GeneralSecurityException("Platform CA bundle exceeds 2 MiB");
    return combined;
  }

  private static String roots() throws GeneralSecurityException {
    String existing = cachedRoots;
    if (existing != null) return existing;
    synchronized (PlatformTrust.class) {
      if (cachedRoots != null) return cachedRoots;
      TrustManagerFactory factory = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      factory.init((KeyStore) null);
      StringBuilder pem = new StringBuilder();
      int count = 0;
      Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
      for (TrustManager manager : factory.getTrustManagers()) {
        if (!(manager instanceof X509TrustManager)) continue;
        for (X509Certificate certificate : ((X509TrustManager) manager).getAcceptedIssuers()) {
          pem.append("-----BEGIN CERTIFICATE-----\n")
              .append(encoder.encodeToString(certificate.getEncoded()))
              .append("\n-----END CERTIFICATE-----\n");
          count++;
        }
      }
      if (count == 0) throw new GeneralSecurityException("Platform has no trusted CA certificates");
      if (pem.length() > 2 * 1024 * 1024)
        throw new GeneralSecurityException("Platform CA bundle exceeds 2 MiB");
      cachedRoots = pem.toString();
      return cachedRoots;
    }
  }
}
