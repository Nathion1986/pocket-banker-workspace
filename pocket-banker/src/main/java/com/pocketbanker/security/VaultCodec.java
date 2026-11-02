package com.pocketbanker.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VaultCodec {
  private final SecretKeySpec key;
  private final ObjectMapper mapper;
  private final SecureRandom random = new SecureRandom();

  public VaultCodec(@Value("${backend.vault-key:}") String secret, ObjectMapper mapper) {
    this.mapper = mapper;
    try {
      key =
          secret.isBlank()
              ? null
              : new SecretKeySpec(
                  MessageDigest.getInstance("SHA-256")
                      .digest(secret.getBytes(StandardCharsets.UTF_8)),
                  "AES");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String seal(String user, Object value) {
    if (key == null) throw new BackendException(503, "backend_vault_key_not_configured");
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(value));
      byte[] all = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, all, 0, 12);
      System.arraycopy(encrypted, 0, all, 12, encrypted.length);
      return Base64.getEncoder().encodeToString(all);
    } catch (Exception e) {
      throw new BackendException(500, "vault_encode_failed");
    }
  }

  public <T> T open(String user, String value, Class<T> type) {
    try {
      return mapper.readValue(decrypt(user, value), type);
    } catch (Exception e) {
      throw new BackendException(500, "vault_decode_failed");
    }
  }

  public Map<String, Object> map(String user, String value) {
    try {
      return mapper.readValue(decrypt(user, value), new TypeReference<>() {});
    } catch (Exception e) {
      throw new BackendException(500, "vault_decode_failed");
    }
  }

  private byte[] decrypt(String user, String value) throws Exception {
    if (key == null) throw new BackendException(503, "backend_vault_key_not_configured");
    byte[] all = Base64.getDecoder().decode(value);
    if (all.length < 28) throw new IllegalArgumentException();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
    cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(all, 12, all.length - 12);
  }
}
