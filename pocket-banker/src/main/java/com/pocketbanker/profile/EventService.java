package com.pocketbanker.profile;

import com.pocketbanker.data.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EventService {
  private final TimelineEventRepository events;
  private final EventDecisionRepository decisions;
  private final Clock perceptionClock;
  private final com.pocketbanker.data.BackendStore backendStore;
  private final EventProjection projection;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  @Value("${perception.candidate-days:30}")
  private int candidateDays;

  @Transactional
  public TimelineEventEntity addCandidate(
      SignalReceiptEntity receipt, CandidateDraft draft, String extractor) {
    var existing = events.findByReceiptId(receipt.getId());
    if (existing.isPresent()) return existing.get();
    validate(draft);
    Instant now = perceptionClock.instant();
    var e = new TimelineEventEntity();
    e.setId(UUID.randomUUID().toString());
    e.setUserId(receipt.getUserId());
    e.setReceiptId(receipt.getId());
    e.setEventType(Set.of("life_event", "flight_trip").contains(draft.kind()) ? "life" : "finance");
    e.setCity(draft.city());
    e.setKind(draft.kind());
    if (java.util.Set.of("tuition_due", "payment_due").contains(draft.kind())) {
      var matches =
          backendStore.rows(
              "SELECT id,currency,due_date,recurrence,account_last4 FROM events WHERE user_id=? AND kind=? AND amount=? AND status IN ('candidate','confirmed') AND superseded_by IS NULL",
              receipt.getUserId(),
              draft.kind(),
              draft.amount());
      for (var match : matches)
        if (java.util.Objects.equals(match.get("currency"), draft.currency())
            && java.util.Objects.equals(
                match.get("due_date"), draft.dueDate() == null ? null : draft.dueDate().toString())
            && java.util.Objects.equals(match.get("recurrence"), draft.recurrence())
            && java.util.Objects.equals(match.get("account_last4"), draft.accountLast4())) {
          e.setDuplicateOf(match.get("id").toString());
          break;
        }
    }

    e.setTitle(draft.title());
    e.setAmount(draft.amount());
    e.setCurrency(draft.currency());
    e.setDueDate(draft.dueDate() == null ? null : draft.dueDate().toString());
    e.setRecurrence(draft.recurrence());
    e.setAccountLast4(draft.accountLast4());
    e.setOccurredAt(receipt.getOccurredAt());
    e.setSource("perception");
    e.setConfidence("manual-v1".equals(extractor) ? 1.0 : 0.85);
    e.setExtractorVersion(extractor);
    e.setStatus("candidate");
    e.setCreatedAt(now);
    e.setExpiresAt(now.plus(candidateDays, ChronoUnit.DAYS));
    return events.save(e);
  }

  @Transactional
  public TimelineEventEntity structured(
      SignalReceiptEntity receipt,
      String kind,
      String title,
      java.math.BigDecimal amount,
      java.time.LocalDate date,
      java.util.Map<String, Object> payload) {
    var existing = events.findByReceiptId(receipt.getId());
    if (existing.isPresent()) return existing.get();
    var e = new TimelineEventEntity();
    e.setId(java.util.UUID.randomUUID().toString());
    e.setUserId(receipt.getUserId());
    e.setReceiptId(receipt.getId());
    e.setKind(kind);
    e.setEventType(amount == null ? "life" : "finance");
    e.setTitle(title);
    e.setAmount(amount);
    e.setCurrency(amount == null ? null : "CNY");
    e.setDueDate(date == null ? null : date.toString());
    e.setOccurredAt(receipt.getOccurredAt());
    e.setSource("perception");
    e.setStatus("candidate");
    e.setConfidence(0.9);
    e.setExtractorVersion("structured-consensus-v1");
    e.setCreatedAt(perceptionClock.instant());
    e.setExpiresAt(perceptionClock.instant().plus(candidateDays, ChronoUnit.DAYS));
    try {
      e.setPayloadJson(mapper.writeValueAsString(payload));
    } catch (Exception x) {
      throw new IllegalArgumentException("invalid_event_payload");
    }
    if (payload.get("subjectId") != null) e.setSubjectId(payload.get("subjectId").toString());
    if (payload.get("city") != null) e.setCity(payload.get("city").toString());
    return events.saveAndFlush(e);
  }

  private void validate(CandidateDraft draft) {
    if ("flight_trip".equals(draft.kind())
        && (draft.city() == null
            || draft.city().isBlank()
            || draft.dueDate() == null
            || draft.endDate() != null && draft.endDate().isBefore(draft.dueDate())))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "explicit_trip_fields_required");
    if (!Set.of("life_event", "flight_trip").contains(draft.kind())
        && (draft.amount() == null || draft.currency() == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_and_currency_required");
    }
    if (Set.of("tuition_due", "payment_due").contains(draft.kind())
        && draft.dueDate() == null
        && draft.recurrence() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "due_date_or_recurrence_required");
    }
  }

  @Transactional
  public Page<TimelineEventEntity> list(String user, String status, int page) {
    if (!Set.of("candidate", "confirmed", "rejected", "expired").contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_event_status");
    }
    expireCandidates(user);
    return events.facts(user, status, PageRequest.of(page, 50));
  }

  @Transactional
  public TimelineEventEntity decide(String user, String id, boolean confirm) {
    return decideInternal(user, id, confirm, false);
  }

  private TimelineEventEntity decideInternal(
      String user, String id, boolean confirm, boolean checked) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (confirm && !checked && "manual-correction-v1".equals(event.getExtractorVersion()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "corrected_candidate_requires_revision");
    if (confirm && event.getDuplicateOf() != null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "duplicate_candidate_requires_merge_or_separate");
    if (confirm) projection.requireCurrent(event);
    String next = confirm ? "confirmed" : "rejected";
    if (next.equals(event.getStatus())) return event;
    if (!"candidate".equals(event.getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "event_already_decided");
    if (!event.getExpiresAt().isAfter(perceptionClock.instant())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "candidate_expired");
    }
    event.setStatus(next);
    if (confirm) {
      event.setConfirmedAt(perceptionClock.instant());
      events.saveAndFlush(event);
      projection.confirmed(event);
    }
    projection.stopReminders(user, id);
    decision(event, next);
    return event;
  }

  /** App edits use a content fingerprint and invalidate dependent facts before re-confirmation. */
  public static String fingerprint(TimelineEventEntity e) {
    try {
      return com.pocketbanker.security.PerceptionAccess.hash(
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  java.util.Arrays.asList(
                      e.getId(),
                      e.getStatus(),
                      e.getTitle(),
                      e.getKind(),
                      e.getAmount() == null
                          ? null
                          : e.getAmount().stripTrailingZeros().toPlainString(),
                      e.getCurrency(),
                      e.getDueDate(),
                      e.getRecurrence(),
                      e.getAccountLast4(),
                      e.getCity(),
                      e.getPayloadJson(),
                      e.getSupersededBy())));
    } catch (Exception failure) {
      throw new IllegalStateException("event_fingerprint_unavailable");
    }
  }

  @Transactional
  public TimelineEventEntity correct(
      String user, String id, String expected, CandidateDraft draft) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!fingerprint(event).equals(expected))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "event_content_changed");
    if (!Set.of("candidate", "confirmed").contains(event.getStatus()) || event.getTaskId() != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "editable_perception_event_required");
    var source =
        backendStore.one(
            "SELECT s.kind FROM perception_sources s JOIN perception_log r ON r.source_id=s.id WHERE r.id=? AND s.user_id=?",
            event.getReceiptId(),
            user);
    if (source.isEmpty() || !Set.of("SMS", "EMAIL").contains(source.get().get("kind")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "text_source_correction_required");
    projection.requireCurrent(event);
    validate(draft);
    if ("confirmed".equals(event.getStatus())) projection.forget(user, id);
    event.setKind(draft.kind());
    event.setEventType(
        Set.of("life_event", "flight_trip").contains(draft.kind()) ? "life" : "finance");
    event.setTitle(draft.title());
    event.setAmount(draft.amount());
    event.setCurrency(draft.currency());
    event.setDueDate(draft.dueDate() == null ? null : draft.dueDate().toString());
    event.setRecurrence(draft.recurrence());
    event.setAccountLast4(draft.accountLast4());
    event.setCity(draft.city());
    event.setDuplicateOf(null);
    event.setStatus("candidate");
    event.setConfirmedAt(null);
    event.setConfidence(1.0);
    event.setExtractorVersion("manual-correction-v1");
    event.setExpiresAt(perceptionClock.instant().plus(candidateDays, ChronoUnit.DAYS));
    try {
      var metadata = new java.util.LinkedHashMap<String, Object>();
      metadata.put("manualCorrection", true);
      metadata.put("previousFingerprint", expected);
      if ("flight_trip".equals(draft.kind())) {
        metadata.put("city", draft.city());
        metadata.put("startDate", draft.dueDate().toString());
        metadata.put(
            "endDate",
            draft.endDate() == null ? draft.dueDate().toString() : draft.endDate().toString());
      }
      event.setPayloadJson(mapper.writeValueAsString(metadata));
    } catch (Exception e) {
      throw new IllegalStateException("invalid_correction_metadata");
    }
    // Re-evaluate duplicate bill protection after an amount/date edit.
    if (Set.of("tuition_due", "payment_due").contains(draft.kind()))
      for (var row :
          backendStore.rows(
              "SELECT id,currency,due_date,recurrence,account_last4 FROM events WHERE user_id=? AND id<>? AND kind=? AND amount=? AND status IN ('candidate','confirmed') AND superseded_by IS NULL",
              user,
              id,
              draft.kind(),
              draft.amount()))
        if (java.util.Objects.equals(row.get("currency"), draft.currency())
            && java.util.Objects.equals(row.get("due_date"), event.getDueDate())
            && java.util.Objects.equals(row.get("recurrence"), draft.recurrence())
            && java.util.Objects.equals(row.get("account_last4"), draft.accountLast4())) {
          event.setDuplicateOf(row.get("id").toString());
          break;
        }
    decision(event, "corrected");
    return events.saveAndFlush(event);
  }

  @Transactional
  public TimelineEventEntity decideCurrent(
      String user, String id, String expected, boolean confirm) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!fingerprint(event).equals(expected))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "event_content_changed");
    return decideInternal(user, id, confirm, true);
  }

  @Transactional
  public void forgetCurrent(String user, String id, String expected) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!fingerprint(event).equals(expected))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "event_content_changed");
    forget(user, id);
  }

  @Transactional
  public TimelineEventEntity resolveDuplicateCurrent(
      String user, String id, String expected, boolean merge) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!fingerprint(event).equals(expected))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "event_content_changed");
    if (!"candidate".equals(event.getStatus()) || event.getDuplicateOf() == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "candidate_not_duplicate");
    if (merge) return resolveDuplicate(user, id, true);
    event.setDuplicateOf(null);
    events.saveAndFlush(event);
    return decideInternal(user, id, true, true);
  }

  @Transactional
  public TimelineEventEntity resolveDuplicate(String user, String id, boolean merge) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
    if (!"candidate".equals(event.getStatus()) || event.getDuplicateOf() == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "candidate_not_duplicate");
    if (!merge) {
      event.setDuplicateOf(null);
      events.saveAndFlush(event);
      return decide(user, id, true);
    }
    var target =
        events
            .lockOwned(event.getDuplicateOf(), user)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "merge_target_unavailable"));
    if ("candidate".equals(target.getStatus())) decide(user, target.getId(), true);
    if (!"confirmed".equals(target.getStatus()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "merge_target_not_fact");
    event.setStatus("rejected");
    events.saveAndFlush(event);
    backendStore.update(
        "UPDATE perception_log SET canonical_event_id=?,reason='merged_by_user' WHERE id=? AND user_id=?",
        target.getId(),
        event.getReceiptId(),
        user);
    decision(event, "merged");
    return target;
  }

  @Transactional
  public void forget(String user, String id) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    // FK cascade removes derived location checks and decision history; no orphan facts.
    projection.forget(user, id);
    events.delete(event);
  }

  @Transactional
  public java.util.Map<String, Object> postpone(String user, String id, Instant until) {
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!"candidate".equals(event.getStatus())
        || event.getSupersededBy() != null
        || until == null
        || !until.isAfter(perceptionClock.instant())
        || !until.isBefore(event.getExpiresAt()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid_candidate_postponement");
    backendStore.update("UPDATE events SET remind_after=? WHERE id=?", until, id);
    projection.stopReminders(user, id);
    return java.util.Map.of("eventId", id, "remindAfter", until, "status", "candidate");
  }

  @Transactional
  public java.util.Map<String, Object> riskFeedback(String user, String id, String response) {
    if (response == null || !Set.of("self", "denied", "unsure").contains(response))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_risk_response");
    backendStore.lockUser(user);
    var event =
        events
            .lockOwned(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!"risk_transaction".equals(event.getKind()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "risk_event_required");
    var prior =
        backendStore.one(
            "SELECT decision FROM event_decisions WHERE event_id=? AND decision LIKE 'risk_%'", id);
    String decision = "risk_" + response;
    if (prior.isPresent() && !decision.equals(prior.get().get("decision")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "risk_response_already_recorded");
    if (prior.isEmpty()) decision(event, decision);
    projection.stopReminders(user, id);
    return java.util.Map.of(
        "eventId",
        id,
        "response",
        response,
        "cardRestored",
        false,
        "recovery",
        "explicit_verified_action_required");
  }

  @Transactional(readOnly = true)
  public java.util.List<java.util.Map<String, Object>> timeline(String user, int page) {
    return backendStore
        .rows(
            "SELECT id,event_type,kind,status,occurred_at,due_date,amount,currency,task_id,superseded_by,payload_json FROM events WHERE user_id=? ORDER BY occurred_at DESC,id LIMIT 50 OFFSET ?",
            user,
            page * 50)
        .stream()
        .map(
            row -> {
              if (row.get("task_id") != null && row.get("payload_json") != null) {
                try {
                  var result =
                      mapper.readValue(
                          row.get("payload_json").toString(),
                          new com.fasterxml.jackson.core.type.TypeReference<
                              java.util.Map<String, Object>>() {});
                  row.put("resultState", result.get("taskState"));
                  row.put("results", result.get("results"));
                } catch (Exception invalid) {
                  throw new IllegalStateException("invalid_task_result_projection");
                }
              }
              row.remove("payload_json");
              row.put(
                  "category",
                  "prediction".equals(row.get("event_type"))
                      ? "prediction"
                      : row.get("task_id") != null
                          ? "SUCCEEDED".equals(row.get("resultState"))
                              ? "completed"
                              : "execution_result"
                          : "confirmed".equals(row.get("status")) ? "fact" : row.get("status"));
              return row;
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public TimelineEventEntity requireRiskEvent(String user, String id) {
    var event =
        events
            .findByIdAndUserId(id, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!"risk_transaction".equals(event.getKind())
        || !Set.of("candidate", "confirmed").contains(event.getStatus())
        || ("candidate".equals(event.getStatus())
            && !event.getExpiresAt().isAfter(perceptionClock.instant()))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "active_risk_event_required");
    }
    return event;
  }

  private void expireCandidates(String user) {
    events.expireCandidates(user, perceptionClock.instant());
  }

  private void decision(TimelineEventEntity event, String state) {
    var d = new EventDecisionEntity();
    d.setId(UUID.randomUUID().toString());
    d.setEventId(event.getId());
    d.setUserId(event.getUserId());
    d.setDecision(state);
    d.setDecidedAt(perceptionClock.instant());
    decisions.save(d);
  }
}
