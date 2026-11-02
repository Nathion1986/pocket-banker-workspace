package com.pocketbanker.backend;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.orchestration.ModelAvailabilityProbe;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModelAvailabilityProbeTest {
  @ParameterizedTest
  @CsvSource({
    "200,valid,up",
    "200,absent,unreachable",
    "200,malformed,unreachable",
    "404,absent,configured_unprobed",
    "405,absent,configured_unprobed",
    "401,absent,unreachable",
    "429,absent,unreachable",
    "503,absent,unreachable"
  })
  void readOnlyCatalogChecksNeverSendAnInferenceRequest(int status, String body, String expected)
      throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/models",
        request -> {
          assertThat(request.getRequestMethod()).isEqualTo("GET");
          assertThat(request.getRequestBody().readAllBytes()).isEmpty();
          assertThat(request.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer synthetic-key");
          calls.incrementAndGet();
          byte[] data =
              (body.equals("valid")
                      ? "{\"data\":[{\"id\":\"synthetic-model\"}]}"
                      : body.equals("malformed") ? "{" : "{\"data\":[]}")
                  .getBytes(StandardCharsets.UTF_8);
          request.sendResponseHeaders(status, data.length);
          request.getResponseBody().write(data);
          request.close();
        });
    server.start();
    try {
      assertThat(
              ModelAvailabilityProbe.read(
                  new ObjectMapper(),
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "synthetic-key",
                  "synthetic-model"))
          .isEqualTo(expected);
      assertThat(calls).hasValue(1);
    } finally {
      server.stop(0);
    }
  }
}
