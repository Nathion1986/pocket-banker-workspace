package com.pocketbanker.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class NativeFixture {
  public static final String TOKEN = "fixture-mock-service-token", ADMIN = "fixture-admin-token";
  public final Map<String, Integer> ports = new LinkedHashMap<>();
  private final List<Process> processes = new ArrayList<>();
  private final Path temp;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public NativeFixture() {
    try {
      temp = Files.createTempDirectory("pocket-native-test-");
      for (String kind : List.of("A", "B", "C", "flower", "cake"))
        try (var socket = new java.net.ServerSocket(0)) {
          ports.put(kind, socket.getLocalPort());
        }
      Path base = Path.of("..").toAbsolutePath().normalize().resolve("mock-banks");
      for (String kind : ports.keySet()) {
        String dir =
            kind.equals("flower")
                ? "flower-shop"
                : kind.equals("cake") ? "cake-shop" : "bank-" + kind.toLowerCase();
        String python =
            Files.isExecutable(Path.of("/opt/homebrew/bin/python3"))
                ? "/opt/homebrew/bin/python3"
                : "python3";
        var builder =
            new ProcessBuilder(
                python,
                base.resolve(dir + "/main.py").toString(),
                "--port",
                ports.get(kind).toString(),
                "--db",
                temp.resolve(kind + ".sqlite3").toString());
        builder.redirectErrorStream(true).redirectOutput(temp.resolve(kind + ".log").toFile());
        builder.environment().put("MOCK_INITIAL_TIME", "2026-09-30T12:00:00Z");
        builder.environment().put("MOCK_SERVICE_TOKEN", TOKEN);
        builder.environment().put("MOCK_ADMIN_TOKEN", ADMIN);
        builder.environment().put("PYTHONUNBUFFERED", "1");
        for (String bank : List.of("A", "B", "C"))
          builder.environment().put("BANK_" + bank + "_URL", url(bank));
        processes.add(builder.start());
      }
      for (String kind : ports.keySet()) {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean ready = false;
        while (System.nanoTime() < end) {
          try {
            get(kind, prefix(kind) + "/health");
            ready = true;
            break;
          } catch (Exception e) {
            Thread.sleep(25);
          }
        }
        if (!ready) throw new IllegalStateException("native fixture did not start " + kind);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String url(String kind) {
    return "http://127.0.0.1:" + ports.get(kind);
  }

  public static String prefix(String kind) {
    return kind.equals("A")
        ? "/api/v2"
        : kind.equals("B") ? "/v2" : kind.equals("C") ? "/v1" : "/merchant/v1";
  }

  public Object get(String kind, String path) {
    return request(kind, path, null, false);
  }

  public Object execute(String kind, String path, Object body) {
    return request(kind, path, body, false);
  }

  public Object admin(String kind, String path, Object body) {
    return request(kind, path, body, true);
  }

  private Object request(String kind, String path, Object body, boolean admin) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(url(kind) + path))
              .timeout(Duration.ofSeconds(5))
              .header(admin ? "X-Mock-Admin-Token" : "X-Mock-Service-Token", admin ? ADMIN : TOKEN)
              .header("Content-Type", "application/json");
      if (body == null) request.GET();
      else request.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200)
        throw new IllegalStateException("native status " + response.statusCode());
      return mapper.readValue(response.body(), Object.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void reset() {
    for (String kind : ports.keySet()) admin(kind, "/admin/reset", Map.of());
  }

  public void advance() {
    for (String kind : List.of("A", "B", "C"))
      admin(kind, "/admin/advance", Map.of("seconds", 172801));
  }

  /** Independent read-only SQLite evidence from these synthetic bank processes. */
  public Map<String, Object> ledgerSnapshot() {
    try {
      String code =
          "import json,sqlite3,sys; from pathlib import Path; result={}\n"
              + "for path in sorted(Path(sys.argv[1]).glob('*.sqlite3')):\n"
              + " db=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True); db.row_factory=sqlite3.Row\n"
              + " result[path.stem]={t:[dict(r) for r in db.execute('SELECT * FROM '+t+' ORDER BY 1')] for t in ['accounts','transactions','operations','reservations','credit_bills','orders']}\n"
              + " db.close()\n"
              + "print(json.dumps(result,sort_keys=True))";
      String python =
          Files.isExecutable(Path.of("/opt/homebrew/bin/python3"))
              ? "/opt/homebrew/bin/python3"
              : "python3";
      var p = new ProcessBuilder(python, "-c", code, temp.toAbsolutePath().toString()).start();
      byte[] output = p.getInputStream().readAllBytes();
      if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
        throw new IllegalStateException("synthetic ledger snapshot failed");
      return mapper.readValue(output, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void close() {
    for (Process p : processes) {
      p.destroy();
      try {
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
      } catch (Exception ignored) {
        p.destroyForcibly();
      }
    }
    // CI logs contain only synthetic fixture requests. Preserve diagnostics before removing temp
    // DBs.
    String workspace = System.getenv("GITHUB_WORKSPACE");
    if (workspace != null && !workspace.isBlank()) {
      try {
        Path evidence =
            Path.of(workspace).resolve("private/ci-native-fixtures").resolve(temp.getFileName());
        Files.createDirectories(evidence);
        for (String kind : ports.keySet())
          Files.copy(
              temp.resolve(kind + ".log"),
              evidence.resolve(kind + ".log"),
              StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
        throw new IllegalStateException("could not preserve CI synthetic fixture logs", e);
      }
    }
    try (var paths = Files.walk(temp)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
              });
    } catch (Exception ignored) {
    }
  }
}
