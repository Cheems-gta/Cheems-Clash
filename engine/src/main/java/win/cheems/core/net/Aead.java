package win.cheems.core.net;

import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** SIP004 TCP framing. Cryptographic primitives are supplied by the platform. */
public final class Aead {
  private static byte[] key(String password, int size) throws GeneralSecurityException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] last = new byte[0], pass = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    while (out.size() < size) {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(last);
      last = md.digest(pass);
      out.write(last, 0, last.length);
    }
    return Arrays.copyOf(out.toByteArray(), size);
  }

  private static byte[] subkey(byte[] master, byte[] salt, int size)
      throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(salt, "HmacSHA1"));
    byte[] prk = mac.doFinal(master);
    mac.init(new SecretKeySpec(prk, "HmacSHA1"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] previous = new byte[0];
    for (int i = 1; out.size() < size; i++) {
      mac.update(previous);
      mac.update("ss-subkey".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      mac.update((byte) i);
      previous = mac.doFinal();
      out.write(previous, 0, previous.length);
    }
    return Arrays.copyOf(out.toByteArray(), size);
  }

  private static final class State {
    final byte[] secret, nonce = new byte[12];
    final boolean chacha;

    State(Node n, byte[] salt) throws GeneralSecurityException {
      chacha = n.cipher.equals("chacha20-ietf-poly1305");
      secret = subkey(key(n.password, salt.length), salt, salt.length);
    }

    byte[] apply(int mode, byte[] data) throws IOException {
      try {
        Cipher c;
        if (chacha) {
          try {
            c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding");
          } catch (GeneralSecurityException e) {
            c = Cipher.getInstance("ChaCha20-Poly1305");
          }
          c.init(mode, new SecretKeySpec(secret, "ChaCha20"), new IvParameterSpec(nonce));
        } else {
          c = Cipher.getInstance("AES/GCM/NoPadding");
          c.init(mode, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
        }
        byte[] output = c.doFinal(data);
        for (int i = 0; i < nonce.length; i++) if (++nonce[i] != 0) break;
        return output;
      } catch (GeneralSecurityException e) {
        throw new IOException("AEAD authentication/encryption failed", e);
      }
    }
  }

  static int size(Node n) {
    return n.cipher.equals("aes-128-gcm") ? 16 : 32;
  }

  public static OutputStream output(Node n, OutputStream raw) throws Exception {
    byte[] salt = new byte[size(n)];
    new SecureRandom().nextBytes(salt);
    State state = new State(n, salt);
    raw.write(salt);
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b});
      }

      @Override
      public synchronized void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
          int count = Math.min(len, 0x3fff);
          raw.write(
              state.apply(Cipher.ENCRYPT_MODE, new byte[] {(byte) (count >> 8), (byte) count}));
          raw.write(state.apply(Cipher.ENCRYPT_MODE, Arrays.copyOfRange(b, off, off + count)));
          off += count;
          len -= count;
        }
      }

      @Override
      public void flush() throws IOException {
        raw.flush();
      }
    };
  }

  public static InputStream input(Node n, InputStream raw) {
    return new InputStream() {
      State state;
      byte[] chunk = new byte[0];
      int pos;

      @Override
      public int read() throws IOException {
        byte[] b = new byte[1];
        return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (state == null) {
          try {
            state = new State(n, readFully(raw, size(n)));
          } catch (GeneralSecurityException e) {
            throw new IOException(e);
          }
        }
        while (pos == chunk.length) {
          int first = raw.read();
          if (first < 0) return -1;
          byte[] enc = new byte[18];
          enc[0] = (byte) first;
          byte[] rest = readFully(raw, 17);
          System.arraycopy(rest, 0, enc, 1, 17);
          byte[] length = state.apply(Cipher.DECRYPT_MODE, enc);
          int count = ((length[0] & 255) << 8) | (length[1] & 255);
          if (count > 0x3fff) throw new IOException("Invalid AEAD frame");
          chunk = state.apply(Cipher.DECRYPT_MODE, readFully(raw, count + 16));
          pos = 0;
        }
        int count = Math.min(len, chunk.length - pos);
        System.arraycopy(chunk, pos, b, off, count);
        pos += count;
        return count;
      }
    };
  }

  public static byte[] readFully(InputStream in, int count) throws IOException {
    byte[] b = new byte[count];
    int pos = 0;
    while (pos < count) {
      int n = in.read(b, pos, count - pos);
      if (n < 0) throw new EOFException();
      pos += n;
    }
    return b;
  }
}
