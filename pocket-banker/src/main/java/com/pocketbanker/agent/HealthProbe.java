package com.pocketbanker.agent;

import com.pocketbanker.adapters.nativebank.BankAdapters;
import com.pocketbanker.data.BackendStore;
import com.pocketbanker.orchestration.ModelCoordinator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealthProbe {
  private final BankAdapters banks;
  private final BackendStore db;
  private final ModelCoordinator models;
  private final com.pocketbanker.adapters.nativebank.NativeHttpClient http;
  private final com.pocketbanker.channels.telegram.TelegramClient telegram;
  private final StructuredPlanningService planning;
  private final LongTailAnalysisPlanner analysis;

  @org.springframework.beans.factory.annotation.Value("${backend.planner.url:}")
  private String planner;

  @org.springframework.beans.factory.annotation.Value("${backend.analysis-code.url:}")
  private String analysisCode;

  @org.springframework.beans.factory.annotation.Value("${perception.imap-accounts-json:{}}")
  private String imap;

  @org.springframework.beans.factory.annotation.Value("${backend.calendars-json:{}}")
  private String calendars;

  @org.springframework.beans.factory.annotation.Value("${perception.city-boundaries-path:}")
  private String boundaries;

  @org.springframework.beans.factory.annotation.Value("${backend.sandbox-mode:docker}")
  private String sandboxMode;

  @org.springframework.beans.factory.annotation.Value("${backend.sandbox-python:python3}")
  private String python;

  @org.springframework.beans.factory.annotation.Value("${backend.model-mode:mock}")
  private String modelMode;

  @org.springframework.beans.factory.annotation.Value("${backend.flower-url:http://127.0.0.1:8004}")
  private String flower;

  @org.springframework.beans.factory.annotation.Value("${backend.cake-url:http://127.0.0.1:8005}")
  private String cake;

  public Map<String, Object> health() {
    Map<String, String> status = new TreeMap<>();
    for (var bank : banks.list()) status.put(bank.id(), bank.healthy() ? "up" : "unreachable");
    for (var merchant : Map.of("flower", flower, "cake", cake).entrySet())
      try {
        http.send(merchant.getValue(), "/merchant/v1/health", "GET", null);
        status.put(merchant.getKey(), "up");
      } catch (Exception e) {
        status.put(merchant.getKey(), "unreachable");
      }
    String database;
    try {
      db.one("SELECT 1 AS alive");
      database = "up";
    } catch (Exception e) {
      database = "unavailable";
    }
    Map<String, String> dependencies = new TreeMap<>();
    dependencies.put("telegram", telegram.health());
    dependencies.put("planner", planning.health());
    dependencies.put("analysis_code", analysis.health());
    dependencies.put(
        "imap", imap.isBlank() || imap.strip().equals("{}") ? "disabled" : "configured_unprobed");
    dependencies.put(
        "caldav",
        calendars.isBlank() || calendars.strip().equals("{}") ? "disabled" : "configured_unprobed");
    dependencies.put(
        "city_boundaries",
        boundaries.isBlank()
            ? "disabled"
            : java.nio.file.Files.isReadable(java.nio.file.Path.of(boundaries))
                ? "up"
                : "unreachable");
    var modelHealth = models.healthCatalog();
    for (var provider : modelHealth) {
      String value = Objects.toString(provider.get("health"));
      dependencies.put(
          "model:" + provider.get("id"),
          Set.of("last-call-failed", "down", "unavailable", "unconfigured", "disabled")
                  .contains(value)
              ? "unreachable"
              : Set.of("last-call-succeeded", "up").contains(value)
                  ? "up"
                  : modelMode.equals("mock") ? "simulated" : "configured_unprobed");
    }
    dependencies.put("sandbox", runtimeHealth());
    boolean degraded =
        status.containsValue("unreachable")
            || dependencies.values().stream()
                .anyMatch(v -> Set.of("unreachable", "configured_unprobed").contains(v));
    return Map.of(
        "status",
        database.equals("up") ? degraded ? "degraded" : "up" : "unavailable",
        "database",
        database,
        "banks",
        status,
        "models",
        modelHealth,
        "dependencies",
        dependencies);
  }

  private String runtimeHealth() {
    try {
      var command =
          sandboxMode.equals("docker")
              ? List.of("docker", "info", "--format", "{{.ServerVersion}}")
              : List.of(python, "-I", "-S", "-c", "import decimal, resource");
      var process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "unreachable";
      }
      return process.exitValue() == 0 ? "up" : "unreachable";
    } catch (Exception e) {
      return "unreachable";
    }
  }
}
