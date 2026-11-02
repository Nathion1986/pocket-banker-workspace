package com.pocketbanker.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 SHA-1/6 digits/30 seconds; state and replay protection live in the transaction. */
public final class TotpCodes {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private TotpCodes() {}

  public static String newSecret() {
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    StringBuilder out = new StringBuilder();
    int bits = 0, buffer = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append(ALPHABET.charAt((buffer >> bits) & 31));
      }
    }
    return out.toString();
  }

  public static String at(String secret, long step) {
    try {
      byte[] bytes = new byte[secret.length() * 5 / 8];
      int bits = 0, buffer = 0, index = 0;
      for (char c : secret.toCharArray()) {
        int value = ALPHABET.indexOf(c);
        if (value < 0) throw new IllegalArgumentException("invalid_totp_secret");
        buffer = (buffer << 5) | value;
        bits += 5;
        if (bits >= 8) {
          bits -= 8;
          bytes[index++] = (byte) (buffer >> bits);
        }
      }
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(bytes, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 15;
      int binary = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
      return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    } catch (Exception e) {
      throw new IllegalStateException("totp_calculation_failed");
    }
  }

  public static long matchingStep(String secret, String code, Instant now, long last) {
    if (code == null || !code.matches("[0-9]{6}")) return -1;
    long current = now.getEpochSecond() / 30;
    for (long step = current - 1; step <= current + 1; step++)
      if (step > last && PerceptionAccess.equal(at(secret, step), code)) return step;
    return -1;
  }
}
