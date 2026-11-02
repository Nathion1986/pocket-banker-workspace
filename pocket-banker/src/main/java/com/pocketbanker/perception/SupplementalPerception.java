package com.pocketbanker.perception;

import static com.pocketbanker.data.BackendStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.contracts.BackendException;
import com.pocketbanker.data.*;
import com.pocketbanker.orchestration.ModelCoordinator;
import com.pocketbanker.profile.EventService;
import com.pocketbanker.security.PerceptionAccess;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplementalPerception {
  private final SourceService sources;
  private final StructuredSignalJobs jobs;
  private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor structuredWorkers;
  private final PerceptionSourceRepository sourceRepository;
  private final SignalReceiptRepository receipts;
  private final BackendStore db;
  private final ObjectMapper mapper;
  private final EventService events;
  private final ModelCoordinator models;
  private final com.pocketbanker.profile.ProfileService profile;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  public SupplementalPerception(
      SourceService sources,
      StructuredSignalJobs jobs,
      @org.springframework.beans.factory.annotation.Qualifier("perceptionExecutor")
          org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor structuredWorkers,
      PerceptionSourceRepository sourceRepository,
      SignalReceiptRepository receipts,
      BackendStore db,
      ObjectMapper mapper,
      EventService events,
      ModelCoordinator models,
      com.pocketbanker.profile.ProfileService profile,
      org.springframework.transaction.support.TransactionTemplate transaction) {
    this.sources = sources;
    this.jobs = jobs;
    this.structuredWorkers = structuredWorkers;
    this.sourceRepository = sourceRepository;
    this.receipts = receipts;
    this.db = db;
    this.mapper = mapper;
    this.events = events;
    this.models = models;
    this.profile = profile;
    this.transaction = transaction;
  }

  public record CalendarInput(
      @NotBlank String sourceId,
      @NotBlank @Size(max = 160) String externalId,
      @Min(0) long revision,
      @NotBlank @Pattern(regexp = "birthday|trip|payment") String kind,
      @NotNull LocalDate startDate,
      LocalDate endDate,
      @Size(max = 100) String city,
      @Size(max = 36) String subjectId,
      String amount,
      String currency) {}

  public record HealthInput(
      @NotBlank String sourceId,
      @NotBlank @Size(max = 160) String externalId,
      @NotNull Instant occurredAt,
      @Min(0) @Max(200000) int steps,
      @Min(20) @Max(250) int restingPulse,
      @DecimalMin("0") @DecimalMax("24") BigDecimal sleepHours,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
          @Min(0)
          long revision) {}

  public record HealthDeletion(
      @NotBlank String sourceId,
      @NotBlank @Size(max = 160) String externalId,
      @Min(0) long revision) {}

  public Map<String, Object> calendar(String token, CalendarInput input) {
    return calendarEntry(token, null, input, null, null);
  }

  public Map<String, Object> calendarQueued(String token, CalendarInput input) {
    return calendarEntry(token, null, input, null, null, true);
  }

  Map<String, Object> calendarEntry(
      String token, String owner, CalendarInput input, CalendarIcsReader.Entry entry, Long epoch) {
    return calendarEntry(token, owner, input, entry, epoch, false);
  }

  private Map<String, Object> calendarEntry(
      String token,
      String owner,
      CalendarInput input,
      CalendarIcsReader.Entry entry,
      Long epoch,
      boolean deferred) {
    var source = transaction.execute(status -> calendarSource(token, owner, input.sourceId()));
    if (epoch != null) sources.requireEpoch(source, epoch);
    validateCalendar(source.getUserId(), input);
    String digest = digest(entry == null ? input : Map.of("input", input, "timing", entry));
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("input", input);
    if (entry != null) safe.put("entry", entry);
    String id =
        transaction.execute(
            status -> {
              var current = calendarSource(token, owner, input.sourceId());
              sources.requireEpoch(current, source.getConsentEpoch());
              invalidateOlder(current, input.externalId(), input.revision());
              return jobs.reserve(
                  current,
                  "calendar",
                  PerceptionAccess.hash(input.externalId()),
                  input.revision(),
                  digest,
                  safe);
            });
    if (!deferred) processJob(id, false);
    return jobs.result(id);
  }

  private void validateCalendar(String user, CalendarInput input) {
    if (input.currency() != null && !"CNY".equals(input.currency()))
      throw new BackendException(400, "unsupported_calendar_currency");
    if (input.endDate() != null && input.endDate().isBefore(input.startDate()))
      throw new BackendException(400, "invalid_trip_window");
    if (input.kind().equals("payment")) com.pocketbanker.contracts.Money.positive(input.amount());
    if (input.subjectId() != null
        && db.one(
                "SELECT id FROM backend_persons WHERE user_id=? AND id=?", user, input.subjectId())
            .isEmpty()) throw new BackendException(404, "calendar_subject_not_found");
  }

  private void invalidateOlder(PerceptionSourceEntity source, String external, long revision) {
    var previous =
        db.one(
            "SELECT revision,event_id FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            source.getUserId(),
            source.getId(),
            PerceptionAccess.hash(external));
    if (previous.isEmpty()) return;
    if (number(previous.get(), "revision") > revision)
      throw new BackendException(409, "structured_revision_stale");
    if (number(previous.get(), "revision") < revision && text(previous.get(), "event_id") != null) {
      String event = text(previous.get(), "event_id");
      db.update(
          "UPDATE events SET status='expired' WHERE user_id=? AND id=?", source.getUserId(), event);
      profile.cleanupSuperseded(source.getUserId(), event);
    }
  }

  public void processJob(String id, boolean explicit) {
    var claim = jobs.claim(id, explicit);
    if (claim == null) return;
    try {
      Map<String, Object> fields = new LinkedHashMap<>();
      CalendarInput calendar = null;
      CalendarIcsReader.Entry entry = null;
      HealthPrepared health = null;
      if (claim.kind().equals("calendar")) {
        calendar = mapper.convertValue(claim.input().get("input"), CalendarInput.class);
        if (claim.input().containsKey("entry"))
          entry = mapper.convertValue(claim.input().get("entry"), CalendarIcsReader.Entry.class);
        fields.put("kind", "calendar_" + calendar.kind());
        fields.put("date", calendar.startDate().toString());
        if (calendar.endDate() != null) fields.put("endDate", calendar.endDate().toString());
        if (calendar.amount() != null)
          fields.put("amount", com.pocketbanker.contracts.Money.positive(calendar.amount()));
        if (calendar.city() != null) fields.put("city", calendar.city());
      } else {
        health = mapper.convertValue(claim.input(), HealthPrepared.class);
        fields.put("kind", "coverage_hint");
        fields.put("purpose", "coverage_reminder");
        fields.put("reviewSuggested", health.reviewSuggested());
        fields.put("coverageOnFile", profile.value(claim.user(), "coverage").isPresent());
      }
      var review = models.review(claim.user(), claim.id(), fields, "signal", true);
      if ("abstain".equals(review.decision())) {
        jobs.fail(claim, "model_consensus_unavailable");
        return;
      }
      var preparedCalendar = calendar;
      var preparedEntry = entry;
      var preparedHealth = health;
      transaction.executeWithoutResult(
          status -> {
            db.lockUser(claim.user());
            jobs.requireClaim(claim);
            Map<String, Object> result =
                claim.kind().equals("calendar")
                    ? calendarCommit(
                        null, claim.user(), preparedCalendar, preparedEntry, review, claim.epoch())
                    : healthCommit(claim.user(), preparedHealth, review, claim.epoch());
            jobs.complete(claim, result);
          });
    } catch (RuntimeException e) {
      jobs.fail(claim, "structured_processing_failed");
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${perception.structured-poll-ms:30000}",
      initialDelayString = "${perception.structured-initial-ms:30000}")
  public void recoverStructured() {
    for (String id : jobs.due()) {
      try {
        structuredWorkers.execute(() -> processJob(id, false));
      } catch (org.springframework.core.task.TaskRejectedException full) {
        break;
      }
    }
  }

  public List<Map<String, Object>> structuredJobs(String user, String source) {
    return jobs.list(user, source);
  }

  public Map<String, Object> retryStructured(String user, String source) {
    var pending = jobs.list(user, source);
    for (var row : pending)
      if (Set.of("queued", "retry_required", "processing", "needs_review")
          .contains(text(row, "state"))) processJob(text(row, "id"), true);
    return Map.of("jobs", jobs.list(user, source));
  }

  private Map<String, Object> calendarCommit(
      String token,
      String owner,
      CalendarInput input,
      CalendarIcsReader.Entry entry,
      ModelCoordinator.Verdict review,
      long epoch) {
    var source = calendarSource(token, owner, input.sourceId());
    sources.requireEpoch(source, epoch);
    if (input.currency() != null && !input.currency().equals("CNY"))
      throw new BackendException(400, "unsupported_calendar_currency");
    if (input.endDate() != null && input.endDate().isBefore(input.startDate()))
      throw new BackendException(400, "invalid_trip_window");
    if (input.subjectId() != null
        && db.one(
                "SELECT id FROM backend_persons WHERE user_id=? AND id=?",
                source.getUserId(),
                input.subjectId())
            .isEmpty()) throw new BackendException(404, "calendar_subject_not_found");
    String external = PerceptionAccess.hash(input.externalId());
    var alias =
        db.one(
            "SELECT * FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            source.getUserId(),
            source.getId(),
            external);
    if (alias.isPresent() && number(alias.get(), "revision") > input.revision())
      throw new BackendException(409, "calendar_revision_stale");
    if (alias.isPresent()
        && number(alias.get(), "revision") == input.revision()
        && text(alias.get(), "event_id") == null)
      throw new BackendException(409, "calendar_revision_cancelled");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("calendarKind", input.kind());
    payload.put("startDate", input.startDate().toString());
    if (input.endDate() != null) payload.put("endDate", input.endDate().toString());
    if (input.city() != null) payload.put("city", input.city());
    if (input.subjectId() != null) payload.put("subjectId", input.subjectId());
    payload.put("externalHash", external);
    payload.put("revision", input.revision());
    payload.put("sourceId", source.getId());
    if (entry != null) {
      payload.put("startsAt", entry.startsAt());
      payload.put("endsAt", entry.endsAt());
      payload.put("timezone", entry.timezone());
      payload.put("allDay", entry.allDay());
    }
    var receipt =
        receipt(
            source,
            input.externalId() + ":" + input.revision(),
            input.startDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
            entry == null ? input : Map.of("input", input, "timing", entry));
    if (receipt.getCanonicalEventId() != null)
      return Map.of(
          "receiptId",
          receipt.getId(),
          "eventId",
          receipt.getCanonicalEventId(),
          "status",
          receipt.getStatus());
    if (!review.decision().equals("confirm")) {
      receipt.setStatus("needs_review");
      receipts.save(receipt);
      return Map.of("receiptId", receipt.getId(), "status", "needs_review");
    }
    BigDecimal amount =
        input.kind().equals("payment")
            ? com.pocketbanker.contracts.Money.positive(input.amount())
            : null;
    String title =
        input.kind().equals("trip") ? "行程计划" : input.kind().equals("birthday") ? "生日提醒" : "日历付款提醒";
    var event =
        events.structured(
            receipt,
            input.kind().equals("payment") ? "payment_due" : "calendar_" + input.kind(),
            title,
            amount,
            input.startDate(),
            payload);
    if (alias.isPresent() && number(alias.get(), "revision") < input.revision()) {
      String old = text(alias.get(), "event_id");
      if (old != null) {
        db.update(
            "UPDATE events SET superseded_by=? WHERE id=? AND user_id=?",
            event.getId(),
            old,
            source.getUserId());
        profile.cleanupSuperseded(source.getUserId(), old);
      }
    }
    receipt.setCanonicalEventId(event.getId());
    receipt.setStatus("completed");
    receipts.save(receipt);
    if (alias.isPresent())
      db.update(
          "UPDATE backend_signal_aliases SET revision=?,event_id=? WHERE user_id=? AND source_id=? AND external_hash=?",
          input.revision(),
          event.getId(),
          source.getUserId(),
          source.getId(),
          external);
    else
      db.update(
          "INSERT INTO backend_signal_aliases VALUES(?,?,?,?,?)",
          source.getUserId(),
          source.getId(),
          external,
          input.revision(),
          event.getId());
    return Map.of("receiptId", receipt.getId(), "eventId", event.getId(), "status", "candidate");
  }

  public record HealthPrepared(
      String sourceId,
      String externalId,
      Instant occurredAt,
      long revision,
      boolean reviewSuggested,
      String contentDigest) {}

  public Map<String, Object> healthQueued(String token, HealthInput input) {
    return healthInput(token, input, true);
  }

  public Map<String, Object> health(String token, HealthInput input) {
    return healthInput(token, input, false);
  }

  private Map<String, Object> healthInput(String token, HealthInput input, boolean deferred) {
    var source = transaction.execute(status -> authenticate(token, input.sourceId(), "HEALTH"));
    if (input.occurredAt().isAfter(db.now().plusSeconds(300)))
      throw new BackendException(400, "invalid_health_time");
    var prepared =
        new HealthPrepared(
            input.sourceId(),
            input.externalId(),
            input.occurredAt(),
            input.revision(),
            input.restingPulse() > 100
                || input.sleepHours() != null
                    && input.sleepHours().compareTo(new BigDecimal("5")) < 0,
            digest(input));
    var safe =
        mapper.convertValue(
            prepared, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    String id =
        transaction.execute(
            status -> {
              var current = authenticate(token, input.sourceId(), "HEALTH");
              sources.requireEpoch(current, source.getConsentEpoch());
              invalidateOlder(current, input.externalId(), input.revision());
              return jobs.reserve(
                  current,
                  "health",
                  PerceptionAccess.hash(input.externalId()),
                  input.revision(),
                  prepared.contentDigest(),
                  safe);
            });
    if (!deferred) processJob(id, false);
    return jobs.result(id);
  }

  private Map<String, Object> healthCommit(
      String user, HealthPrepared input, ModelCoordinator.Verdict review, long epoch) {
    var source = sources.lockOwned(user, input.sourceId());
    sources.requireActive(source);
    sources.requireEpoch(source, epoch);
    if (input.occurredAt().isAfter(db.now().plusSeconds(300)))
      throw new BackendException(400, "invalid_health_time");
    // Raw measurements are used transiently; business payload is only a coverage-review hint, never
    // a pricing input.
    boolean reviewSuggested = input.reviewSuggested();
    String external = PerceptionAccess.hash(input.externalId());
    var alias =
        db.one(
            "SELECT * FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            source.getUserId(),
            source.getId(),
            external);
    if (alias.isPresent() && number(alias.get(), "revision") > input.revision())
      throw new BackendException(409, "health_revision_stale");
    var receipt =
        receipt(
            source,
            input.revision() == 0
                ? input.externalId()
                : input.externalId() + ":" + input.revision(),
            input.occurredAt(),
            new ContentDigest(input.contentDigest()));
    if (alias.isPresent()
        && number(alias.get(), "revision") == input.revision()
        && text(alias.get(), "event_id") == null
        && "processing".equals(receipt.getStatus()))
      throw new BackendException(409, "health_revision_deleted");
    if (receipt.getCanonicalEventId() != null)
      return Map.of(
          "receiptId",
          receipt.getId(),
          "eventId",
          receipt.getCanonicalEventId(),
          "status",
          receipt.getStatus());
    if (alias.isPresent() && number(alias.get(), "revision") < input.revision()) {
      String old = text(alias.get(), "event_id");
      if (old != null) {
        db.update(
            "UPDATE events SET status='expired' WHERE user_id=? AND id=?", source.getUserId(), old);
        profile.cleanupSuperseded(source.getUserId(), old);
      }
    }
    // A filtered or disputed new version still invalidates the older assertion.
    db.update(
        "MERGE INTO backend_signal_aliases KEY(user_id,source_id,external_hash) VALUES(?,?,?,?,NULL)",
        source.getUserId(),
        source.getId(),
        external,
        input.revision());
    Map<String, Object> metadata =
        Map.of(
            "coverageReviewSuggested",
            reviewSuggested,
            "purpose",
            "coverage_reminder",
            "coverageOnFile",
            profile.value(source.getUserId(), "coverage").isPresent(),
            "externalHash",
            external,
            "sourceId",
            source.getId(),
            "revision",
            input.revision());
    if (!reviewSuggested) {
      receipt.setStatus("filtered");
      receipt.setReason("no_coverage_hint");
      receipts.save(receipt);
      return Map.of("receiptId", receipt.getId(), "status", "filtered");
    }
    if (!review.decision().equals("confirm")) {
      receipt.setStatus("needs_review");
      receipts.save(receipt);
      return Map.of("receiptId", receipt.getId(), "status", "needs_review");
    }
    var event = events.structured(receipt, "coverage_hint", "保障资料检查提醒", null, null, metadata);
    db.update(
        "UPDATE backend_signal_aliases SET event_id=? WHERE user_id=? AND source_id=? AND external_hash=?",
        event.getId(),
        source.getUserId(),
        source.getId(),
        external);
    receipt.setCanonicalEventId(event.getId());
    receipt.setStatus("completed");
    receipts.save(receipt);
    return Map.of("receiptId", receipt.getId(), "eventId", event.getId(), "status", "candidate");
  }

  @Transactional
  public Map<String, Object> deleteHealth(String token, HealthDeletion input) {
    var source = authenticate(token, input.sourceId(), "HEALTH");
    String hash = PerceptionAccess.hash(input.externalId());
    jobs.cancelRecord(source.getUserId(), source.getId(), "health", hash, input.revision());
    var alias =
        db.one(
            "SELECT * FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            source.getUserId(),
            source.getId(),
            hash);
    if (alias.isPresent()) {
      if (number(alias.get(), "revision") > input.revision())
        throw new BackendException(409, "health_revision_stale");
      if (number(alias.get(), "revision") == input.revision()
          && text(alias.get(), "event_id") != null)
        throw new BackendException(409, "external_id_content_conflict");
      String event = text(alias.get(), "event_id");
      if (event != null) {
        db.update(
            "UPDATE events SET status='expired' WHERE user_id=? AND id=?",
            source.getUserId(),
            event);
        profile.cleanupSuperseded(source.getUserId(), event);
      }
    }
    var marker =
        receipt(
            source,
            input.revision() == 0
                ? input.externalId()
                : input.externalId() + ":" + input.revision(),
            db.now(),
            Map.of("deleted", true, "revision", input.revision()));
    marker.setStatus("filtered");
    marker.setReason("source_record_deleted");
    receipts.save(marker);
    db.update(
        "MERGE INTO backend_signal_aliases KEY(user_id,source_id,external_hash) VALUES(?,?,?,?,NULL)",
        source.getUserId(),
        source.getId(),
        hash,
        input.revision());
    return Map.of("status", "deleted", "revision", input.revision());
  }

  @Transactional
  public Map<String, Object> cancelCalendar(
      String token, String sourceId, String externalId, long revision) {
    return cancelCalendarEntry(token, null, sourceId, externalId, revision, null);
  }

  Map<String, Object> cancelCalendarEntry(
      String token, String owner, String sourceId, String externalId, long revision, Long epoch) {
    return transaction.execute(
        status -> {
          if (epoch != null) sources.requireEpoch(calendarSource(token, owner, sourceId), epoch);
          return cancelCalendarCommit(token, owner, sourceId, externalId, revision);
        });
  }

  private Map<String, Object> cancelCalendarCommit(
      String token, String owner, String sourceId, String externalId, long revision) {
    var source = calendarSource(token, owner, sourceId);
    if (revision < 0 || externalId == null || externalId.length() > 160)
      throw new BackendException(400, "invalid_calendar_revision");
    String hash = PerceptionAccess.hash(externalId);
    jobs.cancelRecord(source.getUserId(), sourceId, "calendar", hash, revision);
    var alias =
        db.one(
            "SELECT * FROM backend_signal_aliases WHERE user_id=? AND source_id=? AND external_hash=?",
            source.getUserId(),
            sourceId,
            hash);
    if (alias.isPresent()) {
      if (number(alias.get(), "revision") > revision)
        throw new BackendException(409, "calendar_revision_stale");
      if (number(alias.get(), "revision") == revision && text(alias.get(), "event_id") != null)
        throw new BackendException(409, "external_id_content_conflict");
      String old = text(alias.get(), "event_id");
      if (old != null) {
        db.update(
            "UPDATE events SET status='expired' WHERE id=? AND user_id=?", old, source.getUserId());
        profile.cleanupSuperseded(source.getUserId(), old);
      }
      db.update(
          "UPDATE backend_signal_aliases SET revision=?,event_id=NULL WHERE user_id=? AND source_id=? AND external_hash=?",
          revision,
          source.getUserId(),
          sourceId,
          hash);
    } else
      db.update(
          "INSERT INTO backend_signal_aliases VALUES(?,?,?,?,NULL)",
          source.getUserId(),
          sourceId,
          hash,
          revision);
    return Map.of("status", "cancelled", "revision", revision);
  }

  private PerceptionSourceEntity calendarSource(String token, String owner, String id) {
    if (owner == null) return authenticate(token, id, "CALENDAR");
    db.lockUser(owner);
    var source = sources.lockOwned(owner, id);
    sources.requireActive(source);
    if (!"CALENDAR".equals(source.getKind()))
      throw new BackendException(400, "source_kind_mismatch");
    return source;
  }

  private PerceptionSourceEntity authenticate(String token, String id, String kind) {
    var source =
        sourceRepository
            .findById(id)
            .orElseThrow(() -> new BackendException(401, "invalid_source_token"));
    if (!PerceptionAccess.equal(
        source.getTokenHash(), PerceptionAccess.hash(PerceptionAccess.bearer(token))))
      throw new BackendException(401, "invalid_source_token");
    db.lockUser(source.getUserId());
    return sources.authenticateLocked(id, token, kind);
  }

  private record ContentDigest(String value) {}

  private String digest(Object value) {
    try {
      return PerceptionAccess.hash(
          mapper
              .writer()
              .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(value));
    } catch (Exception e) {
      throw new BackendException(400, "invalid_signal");
    }
  }

  private SignalReceiptEntity receipt(
      PerceptionSourceEntity source, String external, Instant occurred, Object content) {
    String hash = content instanceof ContentDigest digest ? digest.value() : digest(content);
    var existing =
        receipts.findBySourceIdAndExternalIdHash(source.getId(), PerceptionAccess.hash(external));
    if (existing.isPresent()) {
      if (!hash.equals(existing.get().getContentHash()))
        throw new BackendException(409, "external_id_content_conflict");
      return existing.get();
    }
    var r = new SignalReceiptEntity();
    r.setId(UUID.randomUUID().toString());
    r.setSourceId(source.getId());
    r.setUserId(source.getUserId());
    r.setExternalIdHash(PerceptionAccess.hash(external));
    r.setContentHash(hash);
    r.setOccurredAt(occurred);
    r.setReceivedAt(db.now());
    r.setUpdatedAt(db.now());
    r.setStatus("processing");
    r.setAttempts(1);
    return receipts.saveAndFlush(r);
  }
}
