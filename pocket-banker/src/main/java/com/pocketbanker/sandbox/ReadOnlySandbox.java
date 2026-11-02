package com.pocketbanker.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadOnlySandbox {
  public static final String IMAGE =
      "python@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f";
  private final ObjectMapper mapper;
  private final String python, backend;

  public ReadOnlySandbox(
      ObjectMapper mapper,
      @Value("${backend.sandbox-python:python3}") String python,
      @Value("${backend.sandbox-mode:docker}") String backend) {
    if (!Set.of("docker", "process").contains(backend))
      throw new IllegalArgumentException("invalid_sandbox_mode");
    this.mapper = mapper;
    this.python = python;
    this.backend = backend;
  }

  public Map<String, Object> analyze(String code, List<Map<String, Object>> data) {
    return run(code, data, backend);
  }

  public Map<String, Object> analyzeGenerated(String code, List<Map<String, Object>> data) {
    return run(code, data, "docker");
  }

  private Map<String, Object> run(String code, List<Map<String, Object>> data, String isolation) {
    if (code == null || code.length() > 16000 || data.size() > 10000)
      throw new BackendException(400, "sandbox_input_limit");
    byte[] input;
    try {
      input = mapper.writeValueAsBytes(Map.of("code", code, "data", data));
    } catch (Exception e) {
      throw new BackendException(400, "sandbox_input_limit");
    }
    if (input.length > 1048576) throw new BackendException(400, "sandbox_input_limit");
    String container = isolation.equals("docker") ? "pb-sandbox-" + UUID.randomUUID() : null;
    Process process = null;
    ExecutorService io =
        Executors.newFixedThreadPool(
            2,
            r -> {
              var t = new Thread(r, "sandbox-io");
              t.setDaemon(true);
              return t;
            });
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    try {
      byte[] runner;
      try (var stream = getClass().getResourceAsStream("/sandbox/runner.py")) {
        runner = Objects.requireNonNull(stream).readAllBytes();
      }
      List<String> command =
          container != null
              ? List.of(
                  "docker",
                  "run",
                  "--pull=never",
                  "--name",
                  container,
                  "--label",
                  "pocket-banker.sandbox=true",
                  "--rm",
                  "-i",
                  "--network",
                  "none",
                  "--read-only",
                  "--user",
                  "65534:65534",
                  "--memory",
                  "128m",
                  "--memory-swap",
                  "128m",
                  "--cpus",
                  "0.5",
                  "--pids-limit",
                  "32",
                  "--cap-drop",
                  "ALL",
                  "--security-opt",
                  "no-new-privileges",
                  "--tmpfs",
                  "/tmp:rw,nosuid,noexec,size=8m",
                  IMAGE,
                  "python",
                  "-I",
                  "-S",
                  "-c",
                  new String(runner, java.nio.charset.StandardCharsets.UTF_8))
              : List.of(
                  python,
                  "-I",
                  "-S",
                  "-c",
                  new String(runner, java.nio.charset.StandardCharsets.UTF_8));
      var builder = new ProcessBuilder(command);
      builder.environment().clear();
      builder.environment().put("PATH", "/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin");
      if (container != null)
        for (String key : List.of("HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG"))
          if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      process = builder.start();
      var target = process;
      var write =
          io.submit(
              () -> {
                try (var out = target.getOutputStream()) {
                  out.write(input);
                }
                return null;
              });
      var read =
          io.submit(
              () -> {
                try (var in = target.getInputStream()) {
                  return in.readNBytes(65537);
                }
              });
      write.get(remaining(end), TimeUnit.NANOSECONDS);
      if (!process.waitFor(remaining(end), TimeUnit.NANOSECONDS)) throw new TimeoutException();
      byte[] output = read.get(remaining(end), TimeUnit.NANOSECONDS);
      if (process.exitValue() != 0 || output.length > 65536)
        throw new BackendException(400, "sandbox_execution_rejected");
      Map<String, Object> result = mapper.readValue(output, new TypeReference<>() {});
      if (!"SUCCEEDED".equals(result.get("status")))
        throw new BackendException(400, "sandbox_execution_rejected");
      result.put("isolation", isolation);
      return result;
    } catch (BackendException e) {
      throw e;
    } catch (TimeoutException e) {
      throw new BackendException(408, "sandbox_timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BackendException(503, "sandbox_interrupted");
    } catch (Exception e) {
      throw new BackendException(503, "sandbox_unavailable");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      io.shutdownNow();
      // Killing docker CLI does not stop its container. Always clean up our unique name.
      if (container != null) {
        boolean interrupted = Thread.interrupted();
        try {
          var cleanup =
              new ProcessBuilder("docker", "rm", "--force", container)
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                  .redirectError(ProcessBuilder.Redirect.DISCARD)
                  .start();
          if (!cleanup.waitFor(3, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (Exception ignored) {
          /* named sandbox can be diagnosed/removed; no other container is targeted */
        } finally {
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static long remaining(long end) throws TimeoutException {
    long left = end - System.nanoTime();
    if (left <= 0) throw new TimeoutException();
    return left;
  }
}
