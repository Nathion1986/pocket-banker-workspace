package com.pocketbanker.perception;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.agent.PerceptionFacade;
import com.pocketbanker.data.*;
import com.pocketbanker.orchestration.RuleSignalExtractor;
import com.pocketbanker.profile.EventService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:perception_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "perception.recovery-ms=3600000",
      "perception.imap-initial-ms=3600000"
    })
@AutoConfigureMockMvc
@Import(PerceptionIntegrationTest.TestClockConfig.class)
class PerceptionIntegrationTest {
  static final String TOKEN_A = "test-user-alpha-token-at-least-32-characters";
  static final String TOKEN_B = "test-user-bravo-token-at-least-32-characters";
  static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PerceptionFacade facade;
  @Autowired SignalTransactions tx;
  @Autowired MutableClock clock;
  @Autowired EventService events;
  @SpyBean RuleSignalExtractor extractor;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("perceptionExecutor")
  org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor worker;

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add(
        "perception.users-json",
        () -> "{\"alpha\":\"" + TOKEN_A + "\",\"bravo\":\"" + TOKEN_B + "\"}");
    r.add(
        "perception.city-boundaries-path",
        () -> {
          try {
            return java.nio.file.Path.of(
                    PerceptionIntegrationTest.class.getResource("/test-cities.geojson").toURI())
                .toString();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @TestConfiguration
  static class TestClockConfig {
    @Bean
    @Primary
    MutableClock fixedClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @BeforeEach
  void clean() {
    clock.now.set(NOW);
    for (String table :
        List.of(
            "location_challenges",
            "event_decisions",
            "events",
            "mail_cursors",
            "perception_log",
            "perception_sources")) jdbc.update("DELETE FROM " + table);
  }

  record Source(String id, String token) {}

  Map<String, Object> collectorReport(String platform, long sequence, long epoch) {
    var report =
        new HashMap<String, Object>(
            Map.of(
                "protocolVersion",
                1,
                "deviceId",
                "device-1",
                "platform",
                platform,
                "osVersion",
                "test-version",
                "consentEpoch",
                epoch,
                "sequence",
                sequence,
                "observedAt",
                clock.instant().toString(),
                "collectionMode",
                "automatic",
                "permission",
                "granted",
                "background",
                "allowed"));
    report.put("pendingCount", 0);
    return report;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"android", "ios", "harmonyos", "other"})
  void collectorPlatformsShareContractWithoutClaimingDeviceCertification(String platform)
      throws Exception {
    var source = source("SMS");
    String path = "/api/v1/perception/sources/" + source.id() + "/collector";
    assertThat(send(get(path), TOKEN_A, null, 200).path("collectorStatus").asText())
        .isEqualTo("unknown");
    var report = collectorReport(platform, 1, 0);
    var result = send(post(path), source.token(), report, 200);
    assertThat(result.path("collectorStatus").asText()).isEqualTo("ready_reported");
    assertThat(result.path("evidence").asText()).isEqualTo("self_reported");
    assertThat(result.path("report").path("platform").asText()).isEqualTo(platform);
    assertThat(send(get(path), TOKEN_A, null, 200).path("receivedAt"))
        .isEqualTo(result.path("receivedAt"));
    send(get(path), TOKEN_B, null, 404);
    send(get(path), source.token(), null, 401);
    send(post(path), TOKEN_A, report, 401);
    send(post(path), source("SMS", TOKEN_B).token(), report, 401);
    report.put("deviceId", "wrong-device");
    send(post(path), source.token(), report, 400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isZero();
  }

  @Test
  void collectorReportOrderingAndFreshnessCannotBeRenewedByReplay() throws Exception {
    var source = source("SMS");
    String path = "/api/v1/perception/sources/" + source.id() + "/collector";
    var report = collectorReport("android", 4, 0);
    var first = send(post(path), source.token(), report, 200);
    clock.now.set(NOW.plusSeconds(901));
    var replay = send(post(path), source.token(), report, 200);
    assertThat(replay.path("receivedAt")).isEqualTo(first.path("receivedAt"));
    assertThat(replay.path("collectorStatus").asText()).isEqualTo("stale");
    report.put("permission", "denied");
    send(post(path), source.token(), report, 409);
    send(post(path), source.token(), collectorReport("android", 3, 0), 409);
    report = collectorReport("android", 5, 0);
    report.put("observedAt", NOW.toString());
    send(post(path), source.token(), report, 400);
    report.put("observedAt", clock.instant().plusSeconds(61).toString());
    send(post(path), source.token(), report, 400);
    report.put("observedAt", clock.instant().toString());
    report.put("permission", "denied");
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("permission_required");
    report.put("sequence", 6);
    report.put("observedAt", clock.instant().minusSeconds(1).toString());
    send(post(path), source.token(), report, 409);
  }

  @Test
  void collectorReportCannotRestoreConsentAfterPauseResumeOrRevocation() throws Exception {
    var source = source("LOCATION");
    String root = "/api/v1/perception/sources/" + source.id();
    var old = collectorReport("ios", 1, 0);
    send(post(root + "/collector"), source.token(), old, 200);
    send(post(root + "/pause"), TOKEN_A, Map.of(), 200);
    assertThat(send(get(root + "/collector"), TOKEN_A, null, 200).path("collectorStatus").asText())
        .isEqualTo("paused");
    send(post(root + "/collector"), source.token(), old, 403);
    send(post(root + "/resume"), TOKEN_A, Map.of(), 200);
    assertThat(send(get(root + "/collector"), TOKEN_A, null, 200).path("collectorStatus").asText())
        .isEqualTo("needs_report");
    send(post(root + "/collector"), source.token(), old, 409);
    send(post(root + "/collector"), source.token(), collectorReport("ios", 1, 2), 200);
    send(post(root + "/revoke"), TOKEN_A, Map.of(), 200);
    send(post(root + "/collector"), source.token(), collectorReport("ios", 2, 2), 403);
    assertThat(send(get(root + "/collector"), TOKEN_A, null, 200).path("sourceStatus").asText())
        .isEqualTo("revoked");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_challenges", Integer.class))
        .isZero();
  }

  @Test
  void collectorReportsDistinguishManualUnsupportedAndBackgroundRestrictions() throws Exception {
    var source = source("SMS");
    String path = "/api/v1/perception/sources/" + source.id() + "/collector";
    var report = collectorReport("harmonyos", 1, 0);
    report.put("background", "restricted");
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("background_restricted");
    report.put("sequence", 2);
    report.put("collectionMode", "manual");
    report.put("permission", "not_required");
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("manual_only");
    report.put("sequence", 3);
    report.put("collectionMode", "unsupported");
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("unsupported");
    report.put("sequence", 4);
    report.put("collectionMode", "automatic");
    send(post(path), source.token(), report, 400);
    report.put("permission", "granted");
    report.put("background", "unknown");
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("background_unknown");
    report.put("sequence", 5);
    report.put("background", "allowed");
    report.put("pendingCount", 3);
    assertThat(send(post(path), source.token(), report, 200).path("collectorStatus").asText())
        .isEqualTo("backlog_reported");
  }

  @Test
  void collectorConcurrentReportsRetainHighestSequence() throws Exception {
    var source = source("SMS");
    String path = "/api/v1/perception/sources/" + source.id() + "/collector";
    var pool = Executors.newFixedThreadPool(8);
    try {
      var results = new ArrayList<Future<Integer>>();
      for (int i = 1; i <= 32; i++) {
        var body = mapper.writeValueAsBytes(collectorReport("android", i, 0));
        results.add(
            pool.submit(
                () ->
                    mvc.perform(
                            post(path)
                                .header("Authorization", "Bearer " + source.token())
                                .contentType("application/json")
                                .content(body))
                        .andReturn()
                        .getResponse()
                        .getStatus()));
      }
      for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS)).isIn(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(send(get(path), TOKEN_A, null, 200).path("report").path("sequence").asInt())
        .isEqualTo(32);
  }

  @Test
  void collectorReportValidatesBoundsKindAndMissingFields() throws Exception {
    var source = source("SMS");
    String path = "/api/v1/perception/sources/" + source.id() + "/collector";
    for (String field :
        List.of(
            "protocolVersion",
            "sequence",
            "consentEpoch",
            "pendingCount",
            "observedAt",
            "platform",
            "permission",
            "collectionMode",
            "background",
            "deviceId")) {
      var report = collectorReport("android", 1, 0);
      report.remove(field);
      send(post(path), source.token(), report, 400);
    }
    for (var entry :
        Map.of(
                "protocolVersion",
                2,
                "sequence",
                -1,
                "pendingCount",
                -1,
                "platform",
                "brand-only",
                "osVersion",
                "x".repeat(101))
            .entrySet()) {
      var report = collectorReport("android", 1, 0);
      report.put(entry.getKey(), entry.getValue());
      send(post(path), source.token(), report, 400);
    }
    var email = source("EMAIL");
    send(
        post("/api/v1/perception/sources/" + email.id() + "/collector"),
        email.token(),
        collectorReport("android", 1, 0),
        400);
    jdbc.update("UPDATE perception_sources SET expires_at=? WHERE id=?", NOW, source.id());
    assertThat(send(get(path), TOKEN_A, null, 200).path("collectorStatus").asText())
        .isEqualTo("expired");
    send(post(path), source.token(), collectorReport("android", 1, 0), 403);
  }

  @Test
  void explicitFlightEmailCreatesConfirmedTripWithoutInferringPayment() throws Exception {
    var source = source("EMAIL");
    var receipt =
        send(
            post("/api/v1/perception/mail/import"),
            source.token(),
            signal(source, "flight-itinerary", "机票行程：目的地三亚，2026年10月1日出发，2026年10月7日返程。"),
            202);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(tx.viewById(receipt.path("id").asText()).status())
                    .isEqualTo("completed"));
    String event = tx.viewById(receipt.path("id").asText()).eventId();
    assertThat(jdbc.queryForObject("SELECT kind FROM events WHERE id=?", String.class, event))
        .isEqualTo("flight_trip");
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM events WHERE id=?", java.math.BigDecimal.class, event))
        .isNull();
    send(post("/api/v1/events/" + event + "/confirm"), TOKEN_A, Map.of(), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM backend_facts WHERE fact_key LIKE 'travel_window:%'",
                Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backend_tasks", Integer.class)).isZero();
  }

  @Test
  void sourcePauseResumeRejectsOldInFlightCompletionAndAllowsExplicitReplay() throws Exception {
    var source = source("SMS");
    var signal =
        new PerceptionRequests.TextSignal(
            source.id(), "pause-race", NOW, "95500", null, "学费20000元 2026年10月15日");
    var ticket = tx.accept("Bearer " + source.token(), "SMS", signal);
    assertThat(tx.begin(ticket)).isTrue();
    send(post("/api/v1/perception/sources/" + source.id() + "/pause"), TOKEN_B, Map.of(), 404);
    send(post("/api/v1/perception/sources/" + source.id() + "/pause"), TOKEN_A, Map.of(), 200);
    assertThatThrownBy(() -> tx.accept("Bearer " + source.token(), "SMS", signal))
        .hasMessage("source_inactive");
    send(post("/api/v1/perception/sources/" + source.id() + "/resume"), TOKEN_A, Map.of(), 200);
    tx.complete(ticket, extractor.extract(null, signal.text(), NOW));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isZero();
    var retry = tx.accept("Bearer " + source.token(), "SMS", signal);
    assertThat(retry.dispatch()).isTrue();
    assertThat(tx.begin(retry)).isTrue();
    tx.complete(retry, extractor.extract(null, signal.text(), NOW));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    facade.revoke("Bearer " + TOKEN_A, source.id());
    send(post("/api/v1/perception/sources/" + source.id() + "/resume"), TOKEN_A, Map.of(), 403);
  }

  Source source(String kind) throws Exception {
    return source(kind, TOKEN_A);
  }

  Source source(String kind, String userToken) throws Exception {
    var response =
        send(
            post("/api/v1/perception/sources"),
            userToken,
            Map.of(
                "kind",
                kind,
                "deviceId",
                "device-1",
                "consentId",
                "consent-test",
                "consentGranted",
                true,
                "allowedSenders",
                List.of("95500", "school@example.test"),
                "expiresAt",
                Instant.now().plusSeconds(86400 * 365).toString()),
            201);
    return new Source(
        response.path("source").path("id").asText(), response.path("sourceToken").asText());
  }

  Map<String, Object> signal(Source source, String externalId, String text) {
    return Map.of(
        "sourceId",
        source.id(),
        "externalId",
        externalId,
        "occurredAt",
        NOW.toString(),
        "sender",
        "95500",
        "text",
        text);
  }

  JsonNode ingest(Source source, String id, String text) throws Exception {
    return send(post("/api/v1/perception/sms"), source.token(), signal(source, id, text), 202);
  }

  String completed(Source source, String id, String text) throws Exception {
    String receipt = ingest(source, id, text).path("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tx.viewById(receipt).status()).isEqualTo("completed"));
    return receipt;
  }

  JsonNode send(MockHttpServletRequestBuilder request, String token, Object body, int expected)
      throws Exception {
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null)
      request.contentType("application/json").content(mapper.writeValueAsBytes(body));
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
    return response.getContentAsString().isBlank()
        ? mapper.createObjectNode()
        : mapper.readTree(response.getContentAsString());
  }

  @Test
  void smsToCandidateToConfirmedFactsAndNoRawPersistence() throws Exception {
    Source source = source("SMS");
    String raw = "保密测试标记_XYZ 尾号1234账户支出35.20元，余额9000元";
    String receipt = completed(source, "sms-1", raw);
    String event = tx.viewById(receipt).eventId();
    assertThat(
            send(get("/api/v1/profile/events"), TOKEN_A, null, 200).path("totalElements").asInt())
        .isZero();
    var candidate = jdbc.queryForMap("SELECT * FROM events WHERE id=?", event);
    assertThat(candidate.get("AMOUNT").toString()).isEqualTo("35.20");
    assertThat(candidate.get("ACCOUNT_LAST4")).isEqualTo("1234");
    assertThat(candidate.get("STATUS")).isEqualTo("candidate");
    send(post("/api/v1/events/" + event + "/confirm"), TOKEN_A, null, 200);
    assertThat(
            send(get("/api/v1/profile/events"), TOKEN_A, null, 200)
                .path("content")
                .get(0)
                .path("id")
                .asText())
        .isEqualTo(event);
    send(post("/api/v1/events/" + event + "/confirm"), TOKEN_A, null, 200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_decisions", Integer.class))
        .isEqualTo(1);
    String script =
        String.join("\n", jdbc.queryForList("SCRIPT NOPASSWORDS NOSETTINGS", String.class));
    assertThat(script).doesNotContain(raw, "保密测试标记_XYZ", source.token(), TOKEN_A);
  }

  @Test
  void unauthenticatedAndSpoofedUserHeadersAreRejected() throws Exception {
    send(get("/api/v1/perception/sources").header("X-User-Id", "alpha"), null, null, 401);
    Source source = source("SMS");
    send(get("/api/v1/perception/sources"), source.token(), null, 401);
    send(post("/api/v1/perception/sms"), TOKEN_A, signal(source, "1", "支出35元"), 401);
  }

  @Test
  void crossUserAccessIsBlocked() throws Exception {
    Source source = source("SMS");
    String receipt = completed(source, "1", "支出35元");
    String event = tx.viewById(receipt).eventId();
    send(get("/api/v1/perception/receipts/" + receipt), TOKEN_B, null, 404);
    send(post("/api/v1/events/" + event + "/confirm"), TOKEN_B, null, 404);
    send(post("/api/v1/perception/sources/" + source.id() + "/revoke"), TOKEN_B, null, 404);
    assertThat(send(get("/api/v1/events"), TOKEN_B, null, 200).path("totalElements").asInt())
        .isZero();
    Source other = source("SMS", TOKEN_B);
    send(post("/api/v1/perception/sms"), other.token(), signal(source, "2", "支出35元"), 401);
    send(
        get("/api/v1/perception/sources/" + other.id() + "/receipts/" + receipt),
        other.token(),
        null,
        404);
  }

  @Test
  void duplicateAndChangedPayloadConflict() throws Exception {
    Source source = source("SMS");
    String receipt = completed(source, "1", "支出35元");
    assertThat(ingest(source, "1", "支出35元").path("id").asText()).isEqualTo(receipt);
    send(post("/api/v1/perception/sms"), source.token(), signal(source, "1", "支出36元"), 409);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
  }

  @Test
  void concurrentDeliveryCreatesOneReceiptAndEvent() throws Exception {
    Source source = source("SMS");
    var input =
        new PerceptionRequests.TextSignal(source.id(), "concurrent", NOW, "95500", null, "支出35元");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> requests = new ArrayList<>();
      for (int i = 0; i < 16; i++)
        requests.add(
            pool.submit(() -> facade.ingest("Bearer " + source.token(), "SMS", input).id()));
      Set<String> ids = new HashSet<>();
      for (var response : requests) ids.add(response.get(10, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> assertThat(tx.viewById(ids.iterator().next()).status()).isEqualTo("completed"));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perception_log", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sourceRevocationPreventsNewAndInFlightWork() throws Exception {
    Source source = source("SMS");
    var ticket =
        tx.accept(
            "Bearer " + source.token(),
            "SMS",
            new PerceptionRequests.TextSignal(
                source.id(), "in-flight", NOW, "95500", null, "支出35元"));
    send(post("/api/v1/perception/sources/" + source.id() + "/revoke"), TOKEN_A, null, 200);
    assertThat(tx.begin(ticket)).isFalse();
    assertThat(tx.viewById(ticket.id()).status()).isEqualTo("revoked");
    send(post("/api/v1/perception/sms"), source.token(), signal(source, "next", "支出35元"), 403);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isZero();
  }

  @Test
  void expiredSourceCannotIngest() throws Exception {
    Source source = source("SMS");
    jdbc.update(
        "UPDATE perception_sources SET expires_at=? WHERE id=?", NOW.minusSeconds(1), source.id());
    send(post("/api/v1/perception/sms"), source.token(), signal(source, "1", "支出35元"), 403);
  }

  @Test
  void whitelistAndOneTimeCodeFilter() throws Exception {
    Source source = source("SMS");
    var input = new HashMap<>(signal(source, "spam", "支出35元"));
    input.put("sender", "bad-sender");
    assertThat(
            send(post("/api/v1/perception/sms"), source.token(), input, 202)
                .path("status")
                .asText())
        .isEqualTo("filtered");
    String receipt = ingest(source, "otp", "验证码123456,支出35元").path("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tx.viewById(receipt).status()).isEqualTo("filtered"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isZero();
  }

  @Test
  void ambiguousContentNeedsReviewAndCanBeStructuredByOwner() throws Exception {
    Source source = source("SMS");
    String receipt =
        ingest(source, "ambiguous", "学费20000元或者18000元，2026年10月15日前缴纳").path("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tx.viewById(receipt).status()).isEqualTo("needs_review"));
    Map<String, Object> draft =
        Map.of(
            "kind",
            "tuition_due",
            "title",
            "学费待缴",
            "amount",
            "20000.00",
            "currency",
            "CNY",
            "dueDate",
            "2026-10-15");
    send(post("/api/v1/perception/receipts/" + receipt + "/candidate"), TOKEN_B, draft, 404);
    var event =
        send(post("/api/v1/perception/receipts/" + receipt + "/candidate"), TOKEN_A, draft, 201);
    assertThat(event.path("status").asText()).isEqualTo("candidate");
    assertThat(event.path("extractorVersion").asText()).isEqualTo("manual-v1");
    assertThat(
            send(get("/api/v1/profile/events"), TOKEN_A, null, 200).path("totalElements").asInt())
        .isZero();
  }

  @Test
  void crashedQueueLeaseRecoversAndOldWorkerCannotOverwriteNewAttempt() throws Exception {
    Source source = source("SMS");
    var input =
        new PerceptionRequests.TextSignal(source.id(), "crash", NOW, "95500", null, "支出35元");
    var old = tx.accept("Bearer " + source.token(), "SMS", input);
    clock.now.set(NOW.plusSeconds(301));
    assertThat(tx.recoverExpired()).isEqualTo(1);
    assertThat(tx.viewById(old.id()).status()).isEqualTo("retry_required");
    var retried = facade.ingest("Bearer " + source.token(), "SMS", input);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tx.viewById(old.id()).status()).isEqualTo("completed"));
    tx.complete(old, new RuleSignalExtractor.Extraction("filtered", "old_worker", null));
    assertThat(tx.viewById(old.id()).status()).isEqualTo("completed");
    assertThat(tx.viewById(old.id()).attempts()).isEqualTo(2);
    assertThat(retried.id()).isEqualTo(old.id());
  }

  @Test
  void transientExtractionFailureCanBeReplayed() throws Exception {
    Source source = source("SMS");
    doThrow(new IllegalStateException("RAW_MUST_NOT_LEAK"))
        .doCallRealMethod()
        .when(extractor)
        .extract(isNull(), eq("支出35元"), eq(NOW));
    String receipt = ingest(source, "fail-once", "支出35元").path("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(tx.viewById(receipt).status()).isEqualTo("retry_required"));
    completed(source, "fail-once", "支出35元");
    assertThat(tx.viewById(receipt).attempts()).isEqualTo(2);
    assertThat(tx.viewById(receipt).reason()).doesNotContain("RAW_MUST_NOT_LEAK");
  }

  @Test
  void oppositeDecisionsConflictAndExpiredCandidatesAreNotConfirmed() throws Exception {
    Source source = source("SMS");
    String event = tx.viewById(completed(source, "reject", "支出35元")).eventId();
    send(post("/api/v1/events/" + event + "/reject"), TOKEN_A, null, 200);
    send(post("/api/v1/events/" + event + "/confirm"), TOKEN_A, null, 409);
    String expiring = tx.viewById(completed(source, "expire", "支出36元")).eventId();
    clock.now.set(NOW.plus(Duration.ofDays(31)));
    send(post("/api/v1/events/" + expiring + "/confirm"), TOKEN_A, null, 409);
    assertThat(
            send(get("/api/v1/events?status=expired"), TOKEN_A, null, 200)
                .path("totalElements")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void concurrentConfirmRejectHasExactlyOneDecision() throws Exception {
    Source source = source("SMS");
    String id = tx.viewById(completed(source, "decision", "支出35元")).eventId();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var go = new CountDownLatch(1);
      List<Future<Boolean>> f = new ArrayList<>();
      for (boolean confirm : List.of(true, false))
        f.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    events.decide("alpha", id, confirm);
                    return true;
                  } catch (org.springframework.web.server.ResponseStatusException e) {
                    return false;
                  }
                }));
      go.countDown();
      assertThat(List.of(f.get(0).get(), f.get(1).get())).containsExactlyInAnyOrder(true, false);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_decisions", Integer.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void forgettingDoesNotResurrectOnReplay() throws Exception {
    Source source = source("SMS");
    String receipt = completed(source, "forget", "支出35元");
    send(post("/api/v1/events/" + tx.viewById(receipt).eventId() + "/confirm"), TOKEN_A, null, 200);
    send(delete("/api/v1/events/" + tx.viewById(receipt).eventId()), TOKEN_A, null, 204);
    ingest(source, "forget", "支出35元");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_decisions", Integer.class)).isZero();
  }

  @Test
  void invalidRequestsDoNotLeakBodyOrPersist() throws Exception {
    Source source = source("SMS");
    var input = new HashMap<>(signal(source, "future", "VERY_PRIVATE支出35元"));
    input.put("occurredAt", NOW.plusSeconds(3600).toString());
    send(post("/api/v1/perception/sms"), source.token(), input, 400);
    input.put("text", "secret".repeat(6000));
    assertThat(send(post("/api/v1/perception/sms"), source.token(), input, 400).toString())
        .doesNotContain("secret");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perception_log", Integer.class)).isZero();
    send(
        post("/api/v1/perception/mail/import"),
        source.token(),
        signal(source, "kind", "支出35元"),
        400);
  }

  @Test
  void oversizedBodyIsRejectedBeforeJsonParsing() throws Exception {
    send(post("/api/v1/perception/sms"), TOKEN_A, Map.of("text", "x".repeat(140000)), 413);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perception_log", Integer.class)).isZero();
  }

  @Test
  void fullWorkerQueueCreatesRetryableReceiptRatherThanLosingMessage() throws Exception {
    Source source = source("SMS");
    CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(2);
    Runnable blocker =
        () -> {
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    try {
      worker.execute(blocker);
      worker.execute(blocker);
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      int capacity = worker.getThreadPoolExecutor().getQueue().remainingCapacity();
      for (int i = 0; i < capacity; i++) worker.execute(blocker);
      send(post("/api/v1/perception/sms"), source.token(), signal(source, "full", "支出35元"), 503);
      assertThat(jdbc.queryForObject("SELECT status FROM perception_log", String.class))
          .isEqualTo("retry_required");
    } finally {
      release.countDown();
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> worker.getActiveCount() == 0);
    completed(source, "full", "支出35元");
    assertThat(jdbc.queryForObject("SELECT attempts FROM perception_log", Integer.class))
        .isEqualTo(2);
  }

  String riskEvent() throws Exception {
    Source sms = source("SMS");
    return tx.viewById(completed(sms, UUID.randomUUID().toString(), "境外刷卡支出8000元")).eventId();
  }

  String challenge(Source source, String risk) throws Exception {
    return send(
            post("/api/v1/perception/location/challenges"),
            TOKEN_A,
            Map.of("sourceId", source.id(), "deviceId", "device-1", "riskEventId", risk),
            201)
        .path("id")
        .asText();
  }

  Map<String, Object> location(Source source, String challenge) {
    return new HashMap<>(
        Map.of(
            "sourceId",
            source.id(),
            "challengeId",
            challenge,
            "deviceId",
            "device-1",
            "permission",
            "granted",
            "collectedAt",
            clock.instant().toString(),
            "latitude",
            23.1234567,
            "longitude",
            114.7654321,
            "accuracy",
            20,
            "coordinateSystem",
            "WGS84"));
  }

  @Test
  void eResolvedCityExpiresAndReplayCannotRestoreIt() throws Exception {
    Source source = source("LOCATION");
    String challenge = challenge(source, riskEvent());
    var input = location(source, challenge);
    send(post("/api/v1/perception/location"), source.token(), input, 200);
    clock.now.set(NOW.plusSeconds(3601));
    var result =
        send(get("/api/v1/perception/location/challenges/" + challenge), TOKEN_A, null, 200);
    assertThat(result.path("cityCode").isNull()).isTrue();
    assertThat(result.path("status").asText()).isEqualTo("expired");
    var replay = send(post("/api/v1/perception/location"), source.token(), input, 200);
    assertThat(replay.path("cityCode").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT city_name FROM location_challenges WHERE id=?", String.class, challenge))
        .isNull();
  }

  @Test
  void eAccuracyCircleAcrossBoundaryIsUnresolved() throws Exception {
    Source source = source("LOCATION");
    String challenge = challenge(source, riskEvent());
    var input = location(source, challenge);
    input.put("longitude", 113.00001);
    input.put("latitude", 23.0);
    input.put("accuracy", 500.0);
    assertThat(
            send(post("/api/v1/perception/location"), source.token(), input, 200)
                .path("status")
                .asText())
        .isEqualTo("unresolved");
  }

  @Test
  void locationStoresOnlyCityAndReplaysWithoutNewEvents() throws Exception {
    Source source = source("LOCATION");
    String challenge = challenge(source, riskEvent());
    var input = location(source, challenge);
    var response = send(post("/api/v1/perception/location"), source.token(), input, 200);
    assertThat(response.path("status").asText()).isEqualTo("resolved");
    assertThat(response.path("cityCode").asText()).isEqualTo("TEST_CITY");
    send(post("/api/v1/perception/location"), source.token(), input, 200);
    input.put("latitude", 23.1);
    send(post("/api/v1/perception/location"), source.token(), input, 409);
    String script =
        String.join("\n", jdbc.queryForList("SCRIPT NOPASSWORDS NOSETTINGS", String.class));
    assertThat(script).doesNotContain("23.1234567", "114.7654321");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(1);
  }

  @Test
  void locationRequiresOwnedRiskAndDeviceBinding() throws Exception {
    Source source = source("LOCATION");
    send(
        post("/api/v1/perception/location/challenges"),
        TOKEN_A,
        Map.of("sourceId", source.id(), "deviceId", "wrong", "riskEventId", riskEvent()),
        400);
    Source sms = source("SMS");
    String ordinary = tx.viewById(completed(sms, "ordinary", "支出35元")).eventId();
    send(
        post("/api/v1/perception/location/challenges"),
        TOKEN_A,
        Map.of("sourceId", source.id(), "deviceId", "device-1", "riskEventId", ordinary),
        409);
    String challenge = challenge(source, riskEvent());
    var input = location(source, challenge);
    input.put("deviceId", "wrong");
    send(post("/api/v1/perception/location"), source.token(), input, 404);
    send(get("/api/v1/perception/location/challenges/" + challenge), TOKEN_B, null, 404);
  }

  @Test
  void locationExpiredDeniedLowAccuracyAndUnknownCity() throws Exception {
    Source source = source("LOCATION");
    String risk = riskEvent();
    String expired = challenge(source, risk);
    clock.now.set(NOW.plusSeconds(31));
    assertThat(
            send(
                    post("/api/v1/perception/location"),
                    source.token(),
                    location(source, expired),
                    200)
                .path("status")
                .asText())
        .isEqualTo("expired");
    String denied = challenge(source, risk);
    assertThat(
            send(
                    post("/api/v1/perception/location"),
                    source.token(),
                    Map.of(
                        "sourceId",
                        source.id(),
                        "challengeId",
                        denied,
                        "deviceId",
                        "device-1",
                        "permission",
                        "denied"),
                    200)
                .path("status")
                .asText())
        .isEqualTo("denied");
    String low = challenge(source, risk);
    var input = location(source, low);
    input.put("accuracy", 2000);
    assertThat(
            send(post("/api/v1/perception/location"), source.token(), input, 200)
                .path("status")
                .asText())
        .isEqualTo("low_accuracy");
    String unknown = challenge(source, risk);
    input = location(source, unknown);
    input.put("latitude", 0);
    input.put("longitude", 0);
    assertThat(
            send(post("/api/v1/perception/location"), source.token(), input, 200)
                .path("status")
                .asText())
        .isEqualTo("unresolved");
  }

  @Test
  void locationStaleTimestampAndInvalidCoordinatesRejected() throws Exception {
    Source source = source("LOCATION");
    String c = challenge(source, riskEvent());
    var input = location(source, c);
    input.put("collectedAt", NOW.minusSeconds(60).toString());
    send(post("/api/v1/perception/location"), source.token(), input, 400);
    input = location(source, c);
    input.put("latitude", 91);
    send(post("/api/v1/perception/location"), source.token(), input, 400);
    input = location(source, c);
    input.put("coordinateSystem", "FAKE");
    send(post("/api/v1/perception/location"), source.token(), input, 400);
  }
}
