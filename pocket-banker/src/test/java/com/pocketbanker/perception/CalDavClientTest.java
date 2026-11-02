package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CalDavClientTest {
  @Test
  void responseLocationsMustRemainDirectChildrenOfConfiguredCollection() {
    URI base = URI.create("https://calendar.example/alpha/calendar/");
    for (String path :
        new String[] {
          "https://evil.example/x",
          "../other/a.ics",
          "/alpha/calendar/%2e%2e/secret",
          "/alpha/calendar/a%2fb.ics",
          "a.ics?credential=secret",
          "a.ics#fragment"
        })
      assertThatThrownBy(() -> CalDavClient.resourceUri(base, path))
          .isInstanceOf(PerceptionException.class);
    assertThat(CalDavClient.resourceUri(base, "a%20b.ics")).isEqualTo(base.resolve("a%20b.ics"));
  }

  @Test
  void externalEntitiesOversizedBodiesRedirectsAndIncompleteListingsFailClosed() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> body = new AtomicReference<>("");
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(207, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      var account =
          new CalDavConnector.Account(
              "alpha",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/calendar/",
              "alpha",
              "test-password",
              "UTC",
              true);
      var client = new CalDavClient();
      for (String xml :
          new String[] {
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d='DAV:'>&e;</d:multistatus>",
            "<d:multistatus xmlns:d='DAV:'/>",
            "x".repeat(512001),
            "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/calendar/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"
          }) {
        body.set(xml);
        assertThatThrownBy(() -> client.list(account)).isInstanceOf(Exception.class);
      }
      server.removeContext("/");
      server.createContext(
          "/",
          exchange -> {
            exchange.getResponseHeaders().set("Location", "http://example.invalid/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      assertThatThrownBy(() -> client.list(account)).hasMessage("invalid_caldav_response");
    } finally {
      server.stop(0);
    }
  }
}
