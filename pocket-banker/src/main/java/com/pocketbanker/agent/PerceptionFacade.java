package com.pocketbanker.agent;

import com.pocketbanker.data.TimelineEventEntity;
import com.pocketbanker.perception.*;
import com.pocketbanker.profile.CandidateDraft;
import com.pocketbanker.profile.EventService;
import com.pocketbanker.security.PerceptionAccess;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/** 感知管理门面，与对话 AgentCore 解耦；不包含任何资金行动入口。 */
@Service
@RequiredArgsConstructor
public class PerceptionFacade {
  private final PerceptionAccess access;
  private final SourceService sources;
  private final PerceptionService perception;
  private final SignalTransactions receipts;
  private final EventService events;
  private final LocationService locations;
  private final ImapConnector mail;
  private final com.pocketbanker.perception.spi.SourceCapabilities sourceAdapters;
  private final com.pocketbanker.perception.SupplementalPerception supplemental;
  private final CalendarConnector calendarConnector;
  private final CalDavConnector calDav;
  private final CollectorStatusService collectors;
  private final StructuredSignalJobs structuredJobs;

  public java.util.Map<String, Object> sourceJob(String token, String source, String id) {
    return structuredJobs.forSource(token, source, id);
  }

  private final com.pocketbanker.prediction.CashflowLedger cashflows;

  public java.util.Map<String, Object> matchSettlement(
      String token,
      String event,
      com.pocketbanker.prediction.CashflowLedger.SettlementInput input) {
    return cashflows.match(access.requireUser(token), event, input);
  }

  public CollectorStatusService.Status collector(String token, String id) {
    return collectors.get(access.requireUser(token), id);
  }

  public CollectorStatusService.Status reportCollector(
      String token, String id, PerceptionRequests.CollectorReport input) {
    return collectors.report(token, id, input);
  }

  public java.util.Map<String, Object> calendarIcs(String token, CalendarConnector.Import input) {
    return calendarConnector.ingest(token, input);
  }

  public java.util.Map<String, Object> calendar(
      String token, com.pocketbanker.perception.SupplementalPerception.CalendarInput input) {
    return supplemental.calendar(token, input);
  }

  public java.util.Map<String, Object> deleteHealth(
      String token, com.pocketbanker.perception.SupplementalPerception.HealthDeletion input) {
    return supplemental.deleteHealth(token, input);
  }

  public java.util.Map<String, Object> health(
      String token, com.pocketbanker.perception.SupplementalPerception.HealthInput input) {
    return supplemental.health(token, input);
  }

  public List<java.util.Map<String, Object>> structuredJobs(String token, String source) {
    return supplemental.structuredJobs(access.requireUser(token), source);
  }

  public java.util.Map<String, Object> retryStructured(String token, String source) {
    return supplemental.retryStructured(access.requireUser(token), source);
  }

  public java.util.Map<String, Object> calendarQueued(
      String token, SupplementalPerception.CalendarInput input) {
    return supplemental.calendarQueued(token, input);
  }

  public java.util.Map<String, Object> healthQueued(
      String token, SupplementalPerception.HealthInput input) {
    return supplemental.healthQueued(token, input);
  }

  public SourceService.CreatedSource createSource(
      String token, PerceptionRequests.CreateSource input) {
    String user = access.requireUser(token);
    if ("CALENDAR".equals(input.kind())) calDav.validateBinding(user, input.connectorRef());
    else mail.validateBinding(user, input.connectorRef());
    return sources.create(user, input);
  }

  public List<SourceService.SourceView> sources(String token, int page) {
    return sources.list(access.requireUser(token), page);
  }

  public SourceService.SourceView revoke(String token, String id) {
    return sources.revoke(access.requireUser(token), id);
  }

  public SourceService.SourceView pause(String token, String id, boolean paused) {
    return sources.pause(access.requireUser(token), id, paused);
  }

  public SignalTransactions.ReceiptView ingest(
      String token, String kind, PerceptionRequests.TextSignal input) {
    return (SignalTransactions.ReceiptView) sourceAdapters.ingest(kind, token, input);
  }

  public SignalTransactions.ReceiptView receipt(String token, String id) {
    return receipts.owned(access.requireUser(token), id);
  }

  public SignalTransactions.ReceiptView sourceReceipt(String token, String source, String id) {
    return receipts.forSource(token, source, id);
  }

  public List<SignalTransactions.ReceiptView> receipts(String token, int page) {
    return receipts.list(access.requireUser(token), page);
  }

  public TimelineEventEntity manual(String token, String id, CandidateDraft draft) {
    return receipts.manual(access.requireUser(token), id, draft);
  }

  public Page<TimelineEventEntity> events(String token, String status, int page) {
    return events.list(access.requireUser(token), status, page);
  }

  public TimelineEventEntity decide(String token, String id, boolean confirm) {
    return events.decide(access.requireUser(token), id, confirm);
  }

  public java.util.Map<String, Object> postpone(String token, String id, java.time.Instant until) {
    return events.postpone(access.requireUser(token), id, until);
  }

  public java.util.Map<String, Object> riskFeedback(String token, String id, String response) {
    return events.riskFeedback(access.requireUser(token), id, response);
  }

  public java.util.List<java.util.Map<String, Object>> timeline(String token, int page) {
    return events.timeline(access.requireUser(token), page);
  }

  public void forget(String token, String id) {
    events.forget(access.requireUser(token), id);
  }

  public LocationService.ChallengeView challenge(
      String token, PerceptionRequests.CreateChallenge input) {
    return locations.create(access.requireUser(token), input);
  }

  public LocationService.ChallengeView location(
      String token, PerceptionRequests.LocationReply input) {
    return locations.reply(token, input);
  }

  public LocationService.ChallengeView challenge(String token, String id) {
    return locations.get(access.requireUser(token), id);
  }

  public ImapConnector.SyncState sync(String token, String id) {
    String user = access.requireUser(token);
    return sources.isCalendar(user, id) ? calDav.requestSync(user, id) : mail.requestSync(user, id);
  }

  public ImapConnector.SyncState syncState(String token, String id) {
    String user = access.requireUser(token);
    return sources.isCalendar(user, id) ? calDav.state(user, id) : mail.state(user, id);
  }

  public java.util.List<java.util.Map<String, Object>> sourceCapabilities(String token) {
    access.requireUser(token);
    return sourceAdapters.list();
  }

  public TimelineEventEntity merge(String token, String id, boolean merge) {
    return events.resolveDuplicate(access.requireUser(token), id, merge);
  }
}
