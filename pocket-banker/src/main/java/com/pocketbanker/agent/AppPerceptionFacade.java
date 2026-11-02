package com.pocketbanker.agent;

import com.pocketbanker.data.TimelineEventEntity;
import com.pocketbanker.profile.CandidateDraft;
import com.pocketbanker.profile.EventService;
import com.pocketbanker.security.PerceptionAccess;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * App projections preserve decimal amounts and keep perception separate from financial authority.
 */
@Service
@RequiredArgsConstructor
public class AppPerceptionFacade {
  private final PerceptionFacade perception;
  private final BackendFacade backend;
  private final PerceptionAccess access;
  private final EventService events;
  private final com.pocketbanker.perception.SourceService sources;
  private final com.pocketbanker.data.BackendStore db;

  public Map<String, Object> overview(
      String token, int candidatePage, int factPage, int sourcePage, int receiptPage) {
    access.requireUser(token);
    var candidates = perception.events(token, "candidate", candidatePage);
    var confirmed = perception.events(token, "confirmed", factPage);
    var result = new LinkedHashMap<String, Object>();
    result.put("sources", perception.sources(token, sourcePage));
    result.put("receipts", perception.receipts(token, receiptPage));
    result.put("candidates", candidates.getContent().stream().map(this::enrich).toList());
    result.put("confirmed", confirmed.getContent().stream().map(this::enrich).toList());
    result.put("candidateHasMore", candidates.hasNext());
    result.put("factHasMore", confirmed.hasNext());
    result.put("profile", backend.profile(token));
    return result;
  }

  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> correct(
      String token, String id, String fingerprint, CandidateDraft draft) {
    String user = access.requireUser(token);
    db.lockUser(user);
    var receipt =
        db.one(
                "SELECT r.source_id FROM perception_log r JOIN events e ON e.receipt_id=r.id WHERE e.id=? AND e.user_id=?",
                id,
                user)
            .orElseThrow(
                () -> new com.pocketbanker.contracts.BackendException(404, "event_not_found"));
    sources.requireActive(sources.lockOwned(user, receipt.get("source_id").toString()));
    return enrich(events.correct(user, id, fingerprint, draft));
  }

  public Map<String, Object> decide(String token, String id, String fingerprint, String choice) {
    String user = access.requireUser(token);
    if ("forget".equals(choice)) {
      events.forgetCurrent(user, id, fingerprint);
      return Map.of("forgotten", true);
    }
    if (Set.of("merge", "separate").contains(choice))
      return enrich(events.resolveDuplicateCurrent(user, id, fingerprint, "merge".equals(choice)));
    return enrich(events.decideCurrent(user, id, fingerprint, "confirm".equals(choice)));
  }

  private Map<String, Object> enrich(TimelineEventEntity event) {
    var result = view(event);
    var source =
        db.one(
            "SELECT s.id,s.kind FROM perception_sources s JOIN perception_log r ON r.source_id=s.id WHERE r.id=? AND s.user_id=?",
            event.getReceiptId(),
            event.getUserId());
    source.ifPresent(
        row -> {
          result.put("sourceId", row.get("id"));
          result.put("sourceKind", row.get("kind"));
          result.put(
              "editable",
              Boolean.TRUE.equals(result.get("editable"))
                  && Set.of("SMS", "EMAIL").contains(row.get("kind")));
        });
    return result;
  }

  public static Map<String, Object> view(TimelineEventEntity e) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", e.getId());
    result.put("receiptId", e.getReceiptId());
    result.put("kind", e.getKind());
    result.put("eventType", e.getEventType());
    result.put("title", e.getTitle());
    result.put("amount", e.getAmount() == null ? null : e.getAmount().toPlainString());
    result.put("currency", e.getCurrency());
    result.put("dueDate", e.getDueDate());
    result.put("recurrence", e.getRecurrence());
    result.put("accountLast4", e.getAccountLast4());
    result.put("occurredAt", e.getOccurredAt());
    result.put("city", e.getCity());
    result.put("status", e.getStatus());
    result.put("source", e.getSource());
    result.put("confidence", e.getConfidence());
    result.put("subjectId", e.getSubjectId());
    result.put("duplicateOf", e.getDuplicateOf());
    result.put("payloadJson", e.getPayloadJson());
    result.put("fingerprint", EventService.fingerprint(e));
    result.put(
        "editable",
        e.getTaskId() == null
            && Set.of("candidate", "confirmed").contains(e.getStatus())
            && Set.of(
                    "tuition_due",
                    "payment_due",
                    "transaction_debit",
                    "transaction_credit",
                    "risk_transaction",
                    "life_event",
                    "flight_trip")
                .contains(e.getKind()));
    return result;
  }
}
