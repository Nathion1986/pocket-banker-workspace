package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.orchestration.RuleSignalExtractor;
import com.pocketbanker.security.PerceptionAccess;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

class PerceptionGoldenEvaluationTest {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final List<Map<String, Object>> RESULTS = new ArrayList<>();
  static byte[] corpus;

  @TestFactory
  Stream<DynamicTest> fixedChineseExpectationsAreIndependentOfExtractorOutput() throws Exception {
    try (var in = getClass().getResourceAsStream("/perception-zh-golden.jsonl")) {
      corpus = Objects.requireNonNull(in).readAllBytes();
    }
    var rows = new ArrayList<Map<String, Object>>();
    for (String line : new String(corpus, StandardCharsets.UTF_8).lines().toList())
      rows.add(MAPPER.readValue(line, new TypeReference<>() {}));
    assertThat(rows).hasSize(300);
    assertThat(rows.stream().map(r -> r.get("family")).distinct().count()).isEqualTo(60);
    return rows.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.get("id") + " " + row.get("category"),
                    () -> {
                      var expected = (Map<String, Object>) row.get("expected");
                      long started = System.nanoTime();
                      var actual =
                          new RuleSignalExtractor()
                              .extract(
                                  (String) row.get("subject"),
                                  row.get("text").toString(),
                                  Instant.parse(row.get("occurredAt").toString()));
                      long elapsed = System.nanoTime() - started;
                      Map<String, Object> fields = new LinkedHashMap<>();
                      fields.put("status", actual.status());
                      if (actual.candidate() != null)
                        fields.putAll(
                            MAPPER.convertValue(
                                actual.candidate(), new TypeReference<Map<String, Object>>() {}));
                      List<String> wrong = new ArrayList<>();
                      for (var field : expected.entrySet()) {
                        Object observed = fields.get(field.getKey());
                        if ("amount".equals(field.getKey()) && observed != null)
                          observed =
                              new java.math.BigDecimal(observed.toString())
                                  .setScale(2)
                                  .toPlainString();
                        if (!Objects.equals(field.getValue(), observed)) wrong.add(field.getKey());
                      }
                      RESULTS.add(
                          Map.of(
                              "id",
                              row.get("id"),
                              "category",
                              row.get("category"),
                              "passed",
                              wrong.isEmpty(),
                              "wrongFields",
                              wrong,
                              "fieldsChecked",
                              expected.size(),
                              "nanos",
                              elapsed));
                      assertThat(wrong).as(row.get("id").toString()).isEmpty();
                      if (actual.candidate() != null) {
                        String normalized =
                            Normalizer.normalize(
                                (row.get("subject") == null ? "" : row.get("subject") + "\n")
                                    + row.get("text"),
                                Normalizer.Form.NFKC);
                        assertThat(actual.evidence()).containsKey("amount");
                        for (var value : actual.evidence().values())
                          if (value instanceof Map<?, ?> span && span.containsKey("start")) {
                            int begin = ((Number) span.get("start")).intValue(),
                                end = ((Number) span.get("end")).intValue();
                            assertThat(begin).isBetween(0, normalized.length() - 1);
                            assertThat(end).isBetween(begin + 1, normalized.length());
                            assertThat(span.get("hash"))
                                .isEqualTo(PerceptionAccess.hash(normalized.substring(begin, end)));
                          }
                      }
                    }));
  }

  @AfterAll
  static void report() throws Exception {
    String target = System.getProperty("perception.eval.result");
    if (target == null) return;
    long passed = RESULTS.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
    var latencies =
        RESULTS.stream().mapToLong(r -> ((Number) r.get("nanos")).longValue()).sorted().toArray();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("passed", RESULTS.size() == 300 && passed == 300);
    report.put("cases", RESULTS.size());
    report.put("passingCases", passed);
    report.put("semanticFamilies", 60);
    report.put("provenance", "synthetic_hand_authored_expectations_and_format_variants");
    report.put(
        "corpusSHA256",
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corpus)));
    report.put(
        "ruleExtractionP95Millis",
        latencies.length == 0
            ? null
            : latencies[(int) Math.ceil(latencies.length * .95) - 1] / 1_000_000.0);
    report.put(
        "fieldsChecked",
        RESULTS.stream().mapToInt(r -> ((Number) r.get("fieldsChecked")).intValue()).sum());
    report.put("realModelSemanticsVerified", false);
    report.put("privateMessagesUsed", false);
    report.put("results", RESULTS);
    var path = Path.of(target);
    Files.createDirectories(path.getParent());
    Files.writeString(
        path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
  }
}
