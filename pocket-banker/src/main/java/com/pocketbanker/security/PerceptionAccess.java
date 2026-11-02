package com.pocketbanker.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 本地用户凭据边界；后续统一登录只需替换 requireUser。 */
@Component
public class PerceptionAccess {
  private final Map<String, String> userHashes = new LinkedHashMap<>();
  private final AppAuthentication accounts;
  private final boolean localDemo;
  private static final SecureRandom RANDOM = new SecureRandom();

  public PerceptionAccess(
      @Value("${perception.users-json:{}}") String json,
      ObjectMapper mapper,
      AppAuthentication accounts,
      @Value("${backend.mode:local-demo}") String mode) {
    this.accounts = accounts;
    this.localDemo = "local-demo".equals(mode);
    try {
      Map<String, String> users = mapper.readValue(json, new TypeReference<>() {});
      for (var entry : users.entrySet()) {
        if (!entry.getKey().matches("[A-Za-z0-9_-]{1,100}") || entry.getValue().length() < 32) {
          throw new IllegalArgumentException();
        }
        String hash = hash(entry.getValue());
        if (userHashes.containsValue(hash)) throw new IllegalArgumentException();
        userHashes.put(entry.getKey(), hash);
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "perception.users-json must map unique users to unique tokens of at least 32 characters");
    }
  }

  public String requireUser(String authorization) {
    String token = bearer(authorization);
    if (token.startsWith("at_")) return accounts.identity(authorization).user();
    if (!localDemo)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account_login_required");
    String candidate = hash(token);
    String user =
        userHashes.entrySet().stream()
            .filter(e -> equal(e.getValue(), candidate))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_user_token"));
    accounts.requireAccountActive(user);
    return user;
  }

  public static String bearer(String authorization) {
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 512) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bearer_token_required");
    }
    return authorization.substring(7);
  }

  public static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "src_" + HexFormat.of().formatHex(bytes);
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean equal(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
