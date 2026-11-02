package com.pocketbanker.perception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.*;
import com.pocketbanker.orchestration.RuleSignalExtractor;
import com.pocketbanker.profile.CandidateDraft;
import com.pocketbanker.profile.EventService;
import com.pocketbanker.security.PerceptionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短事务锁顺序统一 source -> receipt；原文不成为实体字段。 */
@Service
@RequiredArgsConstructor
public class SignalTransactions {
  private final PerceptionSourceRepository sources;
  private final SignalReceiptRepository receipts;
  private final TimelineEventRepository events;
  private final SourceService sourceService;
  private final EventService eventService;
  private final ObjectMapper mapper;
  private final Clock perceptionClock;

  @Value("${perception.lease-seconds:300}")
  private long leaseSeconds;

  public record ReceiptView(
      String id,
      String sourceId,
      String status,
      String reason,
      int attempts,
      String eventId,
      Instant receivedAt,
      Instant updatedAt) {}

  public record Ticket(String id, String sourceId, int attempt, boolean dispatch) {}

  @Transactional
  public Ticket accept(String authorization, String kind, PerceptionRequests.TextSignal input) {
    return reserve(sourceService.authenticateLocked(input.sourceId(), authorization, kind), input);
  }

  @Transactional
  public Ticket acceptMail(String user, PerceptionRequests.TextSignal input) {
    return acceptMail(user, input, null);
  }

  @Transactional
  public Ticket acceptMail(String user, PerceptionRequests.TextSignal input, Long epoch) {
    var source = sourceService.lockOwned(user, input.sourceId());
    sourceService.requireActive(source);
    if (epoch != null) sourceService.requireEpoch(source, epoch);
    if (!"EMAIL".equals(source.getKind()))
      throw new PerceptionException(400, "source_kind_mismatch");
    return reserve(source, input);
  }

  private Ticket reserve(PerceptionSourceEntity source, PerceptionRequests.TextSignal input) {
    Instant now = perceptionClock.instant();
    if (input.occurredAt().isAfter(now.plusSeconds(300))
        || input.occurredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))) {
      throw new PerceptionException(400, "invalid_occurred_at");
    }
    String hash;
    try {
      hash =
          PerceptionAccess.hash(
              mapper.writeValueAsString(
                  List.of(
                      input.occurredAt().toString(),
                          input.sender().strip().toLowerCase(Locale.ROOT),
                      Objects.toString(input.subject(), ""), input.text())));
    } catch (Exception e) {
      throw new PerceptionException(400, "invalid_signal");
    }
    String externalHash = PerceptionAccess.hash(input.externalId());
    var found = receipts.findBySourceIdAndExternalIdHash(source.getId(), externalHash);
    var r = found.orElseGet(SignalReceiptEntity::new);
    if (found.isPresent()) {
      if (!PerceptionAccess.equal(r.getContentHash(), hash))
        throw new PerceptionException(409, "external_id_content_conflict");
      boolean stale =
          Set.of("queued", "processing").contains(r.getStatus())
              && (r.getConsentEpoch() != source.getConsentEpoch()
                  || r.getLeaseUntil().isBefore(now));
      if (!"retry_required".equals(r.getStatus()) && !stale) return ticket(r, false);
    } else {
      r.setId(UUID.randomUUID().toString());
      r.setSourceId(source.getId());
      r.setUserId(source.getUserId());
      r.setExternalIdHash(externalHash);
      r.setContentHash(hash);
      r.setOccurredAt(input.occurredAt());
      r.setReceivedAt(now);
    }
    r.setAttempts(r.getAttempts() + 1);
    r.setConsentEpoch(source.getConsentEpoch());
    r.setUpdatedAt(now);
    r.setLeaseUntil(now.plusSeconds(leaseSeconds));
    boolean allowed = sourceService.senderAllowed(source, input.sender());
    r.setStatus(allowed ? "queued" : "filtered");
    r.setReason(allowed ? null : "sender_not_allowed");
    receipts.saveAndFlush(r);
    return ticket(r, allowed);
  }

  @Transactional
  public boolean begin(Ticket ticket) {
    var s = sources.lockById(ticket.sourceId()).orElseThrow();
    var r = receipts.lockById(ticket.id()).orElseThrow();
    if (r.getAttempts() != ticket.attempt() || !"queued".equals(r.getStatus())) return false;
    if (!isActive(s) || r.getConsentEpoch() != s.getConsentEpoch()) {
      finishState(
          r, "revoked".equals(s.getStatus()) ? "revoked" : "retry_required", "source_inactive");
      return false;
    }
    r.setStatus("processing");
    r.setUpdatedAt(perceptionClock.instant());
    return true;
  }

  @Transactional
  public void complete(Ticket ticket, RuleSignalExtractor.Extraction extraction) {
    var s = sources.lockById(ticket.sourceId()).orElseThrow();
    var r = receipts.lockById(ticket.id()).orElseThrow();
    if (r.getAttempts() != ticket.attempt() || !"processing".equals(r.getStatus())) return;
    if (!isActive(s) || r.getConsentEpoch() != s.getConsentEpoch()) {
      finishState(
          r, "revoked".equals(s.getStatus()) ? "revoked" : "retry_required", "source_inactive");
      return;
    }
    if (extraction.candidate() != null) {
      var event = eventService.addCandidate(r, extraction.candidate(), "rules-grounded-v2");
      try {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fieldEvidence", extraction.evidence());
        metadata.put("receiptId", r.getId());
        metadata.put("senderAuthenticated", false);
        if ("flight_trip".equals(extraction.candidate().kind())) {
          var trip = extraction.candidate();
          metadata.put("city", trip.city());
          metadata.put("startDate", trip.dueDate().toString());
          metadata.put(
              "endDate",
              trip.endDate() == null ? trip.dueDate().toString() : trip.endDate().toString());
        }
        event.setPayloadJson(mapper.writeValueAsString(metadata));
      } catch (Exception e) {
        throw new IllegalStateException("invalid_field_evidence");
      }
    }
    finishState(r, extraction.status(), extraction.reason());
  }

  @Transactional
  public void retry(Ticket ticket, String reason) {
    sources.lockById(ticket.sourceId()).orElseThrow();
    var r = receipts.lockById(ticket.id()).orElseThrow();
    if (r.getAttempts() == ticket.attempt()
        && Set.of("queued", "processing").contains(r.getStatus())) {
      finishState(r, "retry_required", reason);
    }
  }

  @Transactional
  public TimelineEventEntity manual(String user, String receiptId, CandidateDraft draft) {
    var initial =
        receipts
            .findByIdAndUserId(receiptId, user)
            .orElseThrow(() -> new PerceptionException(404, "receipt_not_found"));
    var source = sourceService.lockOwned(user, initial.getSourceId());
    sourceService.requireActive(source);
    var r = receipts.lockById(receiptId).orElseThrow();
    if ("HEALTH".equals(source.getKind())
        && (!"life_event".equals(draft.kind())
            || draft.amount() != null
            || draft.currency() != null))
      throw new PerceptionException(400, "health_purpose_excludes_financial_events");
    if (!"needs_review".equals(r.getStatus()))
      throw new PerceptionException(409, "receipt_not_awaiting_review");
    var event = eventService.addCandidate(r, draft, "manual-v1");
    finishState(r, "completed", "manual_candidate");
    return event;
  }

  @Transactional(readOnly = true)
  public ReceiptView owned(String user, String id) {
    return view(
        receipts
            .findByIdAndUserId(id, user)
            .orElseThrow(() -> new PerceptionException(404, "receipt_not_found")));
  }

  @Transactional
  public ReceiptView forSource(String token, String sourceId, String id) {
    var s =
        sources
            .findById(sourceId)
            .orElseThrow(() -> new PerceptionException(401, "invalid_source_token"));
    sourceService.authenticateLocked(sourceId, token, s.getKind());
    var r =
        receipts.findById(id).orElseThrow(() -> new PerceptionException(404, "receipt_not_found"));
    if (!r.getSourceId().equals(sourceId)) throw new PerceptionException(404, "receipt_not_found");
    return view(r);
  }

  @Transactional(readOnly = true)
  public ReceiptView viewById(String id) {
    return view(receipts.findById(id).orElseThrow());
  }

  @Transactional(readOnly = true)
  public List<ReceiptView> list(String user, int page) {
    return receipts.findByUserIdOrderByReceivedAtDesc(user, PageRequest.of(page, 50)).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public int recoverExpired() {
    return receipts.recoverExpired(perceptionClock.instant());
  }

  private boolean isActive(PerceptionSourceEntity s) {
    return "active".equals(s.getStatus())
        && !s.isPaused()
        && s.getExpiresAt().isAfter(perceptionClock.instant());
  }

  private Ticket ticket(SignalReceiptEntity r, boolean dispatch) {
    return new Ticket(r.getId(), r.getSourceId(), r.getAttempts(), dispatch);
  }

  private void finishState(SignalReceiptEntity r, String status, String reason) {
    r.setStatus(status);
    r.setReason(reason);
    r.setUpdatedAt(perceptionClock.instant());
    r.setLeaseUntil(null);
  }

  private ReceiptView view(SignalReceiptEntity r) {
    return new ReceiptView(
        r.getId(),
        r.getSourceId(),
        r.getStatus(),
        r.getReason(),
        r.getAttempts(),
        r.getCanonicalEventId() != null
            ? events
                .findByIdAndUserId(r.getCanonicalEventId(), r.getUserId())
                .map(TimelineEventEntity::getId)
                .orElse(null)
            : events.findByReceiptId(r.getId()).map(TimelineEventEntity::getId).orElse(null),
        r.getReceivedAt(),
        r.getUpdatedAt());
  }
}
