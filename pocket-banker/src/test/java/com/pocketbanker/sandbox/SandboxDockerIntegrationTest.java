package com.pocketbanker.sandbox;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("docker")
class SandboxDockerIntegrationTest {
  ObjectMapper mapper = new ObjectMapper();

  String docker(String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add("docker");
    command.addAll(List.of(args));
    var p = new ProcessBuilder(command).redirectErrorStream(true).start();
    assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
    return new String(p.getInputStream().readAllBytes());
  }

  @Test
  void generatedCodeUsesDockerEvenWhenFastTestsSelectProcessMode() {
    var output =
        new ReadOnlySandbox(mapper, "python3", "process")
            .analyzeGenerated("result = {'count': len(data)}", List.of(Map.of("amount", "1.00")));
    assertThat(output.get("isolation")).isEqualTo("docker");
  }

  @Test
  void realDockerRejectsCpuAndMemoryExhaustionAndLeavesNoSandbox() {
    var sandbox = new ReadOnlySandbox(mapper, "python3", "docker");
    for (String code :
        List.of("result = sum(x for x in range(1000000000))", "result = [0] * 100000000"))
      assertThatThrownBy(() -> sandbox.analyze(code, List.of()))
          .isInstanceOf(com.pocketbanker.contracts.BackendException.class);
  }

  @Test
  void interruptedDockerClientStopsTheActualContainerImmediately() throws Exception {
    Set<String> before = new HashSet<>(List.of(docker("ps", "--format", "{{.Names}}").split("\n")));
    var pool = Executors.newSingleThreadExecutor();
    Future<?> future =
        pool.submit(
            () ->
                new ReadOnlySandbox(mapper, "python3", "docker")
                    .analyze("result = sum(x for x in range(1000000000))", List.of()));
    String own = "";
    try {
      long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (System.nanoTime() < end) {
        for (String candidate :
            docker("ps", "--filter", "ancestor=python:3.12-slim", "--format", "{{.Names}}")
                .split("\n"))
          if (!candidate.isBlank() && !before.contains(candidate)) own = candidate;
        if (!own.isBlank()) break;
        Thread.sleep(25);
      }
      assertThat(own).isNotBlank();
      future.cancel(true);
      pool.shutdownNow();
      pool.awaitTermination(4, TimeUnit.SECONDS);
      assertThat(docker("ps", "--filter", "name=" + own, "--format", "{{.Names}}").strip())
          .isEmpty();
    } finally {
      future.cancel(true);
      pool.shutdownNow();
      if (!own.isBlank()) docker("rm", "--force", own);
    }
  }

  @Test
  void realDockerRunsExactDecimalAnalysis() {
    var sandbox = new ReadOnlySandbox(mapper, "python3", "docker");
    var result =
        sandbox.analyze(
            "result = {'total': str(sum(Decimal(row['amount']) for row in data))}",
            List.of(Map.of("amount", "123.45"), Map.of("amount", "0.55")));
    assertThat(result.get("isolation")).isEqualTo("docker");
    assertThat(result.get("result").toString()).contains("124.00");
  }

  @Test
  void dockerFlagsActuallyIsolateAndInterruptedCliLeavesNoContainer() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    Future<?> future =
        pool.submit(
            () ->
                new ReadOnlySandbox(mapper, "python3", "docker")
                    .analyze("result = sum(x for x in range(1000000000))", List.of()));
    String name = "";
    try {
      long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
      while (System.nanoTime() < end) {
        name =
            docker("ps", "--filter", "label=pocket-banker.sandbox=true", "--format", "{{.Names}}")
                .strip();
        if (!name.isBlank()) break;
        if (future.isDone()) break;
        Thread.sleep(25);
      }
      assertThat(name).startsWith("pb-sandbox-");
      var config = mapper.readTree(docker("inspect", name)).get(0);
      var host = config.path("HostConfig");
      assertThat(host.path("NetworkMode").asText()).isEqualTo("none");
      assertThat(host.path("ReadonlyRootfs").asBoolean()).isTrue();
      assertThat(host.path("Memory").asLong()).isEqualTo(134217728);
      assertThat(host.path("MemorySwap").asLong()).isEqualTo(134217728);
      assertThat(host.path("NanoCpus").asLong()).isEqualTo(500000000);
      assertThat(host.path("PidsLimit").asLong()).isEqualTo(32);
      assertThat(config.path("Config").path("User").asText()).isEqualTo("65534:65534");
      assertThat(host.path("CapDrop").toString()).contains("ALL");
      assertThat(host.path("SecurityOpt").toString()).contains("no-new-privileges");
      future.cancel(true);
    } finally {
      future.cancel(true);
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
      if (!name.isBlank())
        assertThat(docker("ps", "-a", "--filter", "name=" + name, "--format", "{{.Names}}").strip())
            .isEmpty();
    }
  }
}
