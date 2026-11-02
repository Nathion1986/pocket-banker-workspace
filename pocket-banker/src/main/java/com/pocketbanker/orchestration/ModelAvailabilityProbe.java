package com.pocketbanker.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Read-only catalog request; never submits a prompt or spends inference tokens. */
public final class ModelAvailabilityProbe {
  private ModelAvailabilityProbe() {}

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public static String read(ObjectMapper mapper, String base, String key, String model) {
    if (key == null || key.isBlank()) return "unreachable";
    try {
      var response =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/models"))
                  .timeout(Duration.ofSeconds(2))
                  .header("Authorization", "Bearer " + key)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 404 || response.statusCode() == 405)
        return "configured_unprobed";
      if (response.statusCode() != 200 || response.body().length > 262144) return "unreachable";
      var data = mapper.readTree(response.body()).path("data");
      if (!data.isArray()) return "unreachable";
      for (var item : data) if (model.equals(item.path("id").asText())) return "up";
      return "unreachable";
    } catch (Exception e) {
      return "unreachable";
    }
  }
}
