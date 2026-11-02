package com.pocketbanker.channels.web;

import com.pocketbanker.agent.PerceptionFacade;
import com.pocketbanker.data.TimelineEventEntity;
import com.pocketbanker.perception.*;
import com.pocketbanker.profile.CandidateDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class PerceptionController {
  private final PerceptionFacade facade;

  @GetMapping("/perception/capabilities")
  public java.util.List<java.util.Map<String, Object>> capabilities(
      @RequestHeader(value = "Authorization", required = false) String token) {
    return facade.sourceCapabilities(token);
  }

  @PostMapping("/perception/calendar")
  public java.util.Map<String, Object> calendar(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody com.pocketbanker.perception.SupplementalPerception.CalendarInput input) {
    return facade.calendar(token, input);
  }

  @PostMapping("/perception/calendar/ics")
  public java.util.Map<String, Object> calendarIcs(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody CalendarConnector.Import input) {
    return facade.calendarIcs(token, input);
  }

  @PostMapping("/perception/calendar/queue")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public java.util.Map<String, Object> calendarQueued(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody SupplementalPerception.CalendarInput input) {
    return facade.calendarQueued(token, input);
  }

  @PostMapping("/perception/health/queue")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public java.util.Map<String, Object> healthQueued(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody SupplementalPerception.HealthInput input) {
    return facade.healthQueued(token, input);
  }

  @PostMapping("/perception/health/delete")
  public java.util.Map<String, Object> deleteHealth(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody com.pocketbanker.perception.SupplementalPerception.HealthDeletion input) {
    return facade.deleteHealth(token, input);
  }

  @PostMapping("/perception/health")
  public java.util.Map<String, Object> health(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody com.pocketbanker.perception.SupplementalPerception.HealthInput input) {
    return facade.health(token, input);
  }

  @PostMapping("/perception/sources")
  @ResponseStatus(HttpStatus.CREATED)
  public SourceService.CreatedSource createSource(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody PerceptionRequests.CreateSource input) {
    return facade.createSource(token, input);
  }

  @GetMapping("/perception/sources")
  public List<SourceService.SourceView> sources(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.sources(token, page);
  }

  @PostMapping("/perception/sources/{id}/revoke")
  public SourceService.SourceView revoke(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.revoke(token, id);
  }

  @GetMapping("/perception/sources/{id}/collector")
  public CollectorStatusService.Status collector(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.collector(token, id);
  }

  @PostMapping("/perception/sources/{id}/collector")
  public CollectorStatusService.Status reportCollector(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody PerceptionRequests.CollectorReport input) {
    return facade.reportCollector(token, id, input);
  }

  @PostMapping("/perception/sources/{id}/pause")
  public SourceService.SourceView pause(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.pause(token, id, true);
  }

  @PostMapping("/perception/sources/{id}/resume")
  public SourceService.SourceView resume(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.pause(token, id, false);
  }

  @PostMapping("/perception/sms")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public SignalTransactions.ReceiptView sms(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody PerceptionRequests.TextSignal input) {
    return facade.ingest(token, "SMS", input);
  }

  @PostMapping("/perception/mail/import")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public SignalTransactions.ReceiptView mail(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody PerceptionRequests.TextSignal input) {
    return facade.ingest(token, "EMAIL", input);
  }

  @GetMapping("/perception/receipts")
  public List<SignalTransactions.ReceiptView> receipts(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.receipts(token, page);
  }

  @GetMapping("/perception/receipts/{id}")
  public SignalTransactions.ReceiptView receipt(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.receipt(token, id);
  }

  @GetMapping("/perception/sources/{sourceId}/receipts/{id}")
  public SignalTransactions.ReceiptView sourceReceipt(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String sourceId,
      @PathVariable String id) {
    return facade.sourceReceipt(token, sourceId, id);
  }

  @PostMapping("/perception/receipts/{id}/candidate")
  @ResponseStatus(HttpStatus.CREATED)
  public TimelineEventEntity manual(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody CandidateDraft input) {
    return facade.manual(token, id, input);
  }

  @GetMapping("/events")
  public Page<TimelineEventEntity> events(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "candidate") String status,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.events(token, status, page);
  }

  @GetMapping("/profile/events")
  public Page<TimelineEventEntity> facts(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.events(token, "confirmed", page);
  }

  @PostMapping("/events/{id}/settlements")
  public java.util.Map<String, Object> matchSettlement(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody com.pocketbanker.prediction.CashflowLedger.SettlementInput input) {
    return facade.matchSettlement(token, id, input);
  }

  @PostMapping("/events/{id}/confirm")
  public TimelineEventEntity confirm(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.decide(token, id, true);
  }

  @PostMapping("/events/{id}/reject")
  public TimelineEventEntity reject(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.decide(token, id, false);
  }

  @PostMapping("/events/{id}/merge")
  public TimelineEventEntity merge(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @RequestBody java.util.Map<String, String> input) {
    String decision = input.get("decision");
    if (!java.util.Set.of("merge", "separate").contains(decision))
      throw new com.pocketbanker.contracts.BackendException(400, "merge_decision_required");
    return facade.merge(token, id, decision.equals("merge"));
  }

  public record Postpone(@jakarta.validation.constraints.NotNull java.time.Instant until) {}

  @PostMapping("/events/{id}/postpone")
  public java.util.Map<String, Object> postpone(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody Postpone input) {
    return facade.postpone(token, id, input.until());
  }

  public record RiskFeedback(
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "self|denied|unsure")
          String response) {}

  @PostMapping("/events/{id}/risk-feedback")
  public java.util.Map<String, Object> riskFeedback(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id,
      @Valid @RequestBody RiskFeedback input) {
    return facade.riskFeedback(token, id, input.response());
  }

  @GetMapping("/timeline")
  public java.util.List<java.util.Map<String, Object>> timeline(
      @RequestHeader(value = "Authorization", required = false) String token,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return facade.timeline(token, page);
  }

  @DeleteMapping("/events/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forget(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    facade.forget(token, id);
  }

  @PostMapping("/perception/location/challenges")
  @ResponseStatus(HttpStatus.CREATED)
  public LocationService.ChallengeView challenge(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody PerceptionRequests.CreateChallenge input) {
    return facade.challenge(token, input);
  }

  @GetMapping("/perception/location/challenges/{id}")
  public LocationService.ChallengeView challenge(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.challenge(token, id);
  }

  @PostMapping("/perception/location")
  public LocationService.ChallengeView location(
      @RequestHeader(value = "Authorization", required = false) String token,
      @Valid @RequestBody PerceptionRequests.LocationReply input) {
    return facade.location(token, input);
  }

  @GetMapping("/perception/sources/{source}/jobs/{id}")
  public java.util.Map<String, Object> sourceJob(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String source,
      @PathVariable String id) {
    return facade.sourceJob(token, source, id);
  }

  @GetMapping("/perception/sources/{id}/jobs")
  public java.util.List<java.util.Map<String, Object>> structuredJobs(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.structuredJobs(token, id);
  }

  @PostMapping("/perception/sources/{id}/jobs/retry")
  public java.util.Map<String, Object> retryStructured(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.retryStructured(token, id);
  }

  @PostMapping("/perception/sources/{id}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImapConnector.SyncState sync(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.sync(token, id);
  }

  @GetMapping("/perception/sources/{id}/sync")
  public ImapConnector.SyncState syncState(
      @RequestHeader(value = "Authorization", required = false) String token,
      @PathVariable String id) {
    return facade.syncState(token, id);
  }
}
