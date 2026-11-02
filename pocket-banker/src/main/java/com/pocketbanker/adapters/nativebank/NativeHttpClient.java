package com.pocketbanker.adapters.nativebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NativeHttpClient {
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String token;
  private final Duration timeout;

  public NativeHttpClient(
      ObjectMapper mapper,
      @Value("${backend.mock-service-token:}") String token,
      @Value("${backend.bank-timeout-ms:2000}") long millis) {
    this.mapper = mapper;
    this.token = token;
    timeout = Duration.ofMillis(millis);
    http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  public Object send(String base, String path, String method, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(timeout)
              .header("X-Mock-Service-Token", token)
              .header("Content-Type", "application/json");
      if (method.equals("GET")) builder.GET();
      else
        builder.method(
            method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.body().length > 1_000_000)
        throw new BackendException(502, "bank_response_too_large");
      if (response.statusCode() == 404) throw new BackendException(404, "bank_resource_not_found");
      if (response.statusCode() >= 400 && response.statusCode() < 500) {
        String code = "bank_business_rejected";
        try {
          Object error = object(mapper.readValue(response.body(), Object.class)).get("error");
          if (error instanceof String safe && safe.matches("[a-z][a-z0-9_]{0,70}"))
            code = "bank_" + safe;
        } catch (Exception ignored) {
        }
        throw new BackendException(response.statusCode(), code);
      }
      if (response.statusCode() >= 500)
        throw new BackendException(
            503, method.equals("GET") ? "bank_unavailable" : "bank_result_unknown");
      return mapper.readValue(response.body(), Object.class);
    } catch (BackendException e) {
      throw e;
    } catch (Exception e) {
      throw new BackendException(
          503, method.equals("GET") ? "bank_unavailable" : "bank_result_unknown");
    }
  }

  public static String quote(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object o) {
    if (!(o instanceof Map)) throw new BackendException(502, "invalid_bank_schema");
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> list(Object o) {
    if (!(o instanceof List<?> l) || l.stream().anyMatch(v -> !(v instanceof Map)))
      throw new BackendException(502, "invalid_bank_schema");
    return (List<Map<String, Object>>) o;
  }
}
