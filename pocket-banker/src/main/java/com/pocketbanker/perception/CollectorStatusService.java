package com.pocketbanker.perception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.PerceptionSourceEntity;
import com.pocketbanker.data.PerceptionSourceRepository;
import com.pocketbanker.security.PerceptionAccess;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-independent diagnostics. No hardware certification or permission grant is implied. */
@Service
@RequiredArgsConstructor
public class CollectorStatusService {
  private static final long FRESH_SECONDS = 900;
  private final PerceptionSourceRepository repository;
  private final SourceService sources;
  private final ObjectMapper mapper;
  private final Clock perceptionClock;
  private final Validator validator;

  public record Status(
      String sourceId,
      String deviceId,
      String sourceStatus,
      long consentEpoch,
      String collectorStatus,
      String evidence,
      PerceptionRequests.CollectorReport report,
      Instant receivedAt,
      Instant freshUntil) {}

  @Transactional(readOnly = true)
  public Status get(String user, String id) {
    var source =
        repository
            .findById(id)
            .filter(s -> s.getUserId().equals(user))
            .orElseThrow(() -> new PerceptionException(404, "source_not_found"));
    requireMobile(source);
    return view(source);
  }

  @Transactional
  public Status report(String token, String id, PerceptionRequests.CollectorReport report) {
    if (!validator.validate(report).isEmpty())
      throw new PerceptionException(400, "invalid_collector_report");
    var source = sources.authenticateLocked(id, token);
    requireMobile(source);
    if (source.getDeviceId() == null || !source.getDeviceId().equals(report.deviceId()))
      throw new PerceptionException(400, "collector_device_mismatch");
    sources.requireEpoch(source, report.consentEpoch());
    if ("automatic".equals(report.collectionMode()) && "not_required".equals(report.permission()))
      throw new PerceptionException(400, "automatic_collection_requires_permission_status");
    String json;
    try {
      json = mapper.writeValueAsString(report);
    } catch (JsonProcessingException e) {
      throw new PerceptionException(400, "invalid_collector_report");
    }
    String hash = PerceptionAccess.hash(json);
    var previous = read(source);
    if (previous != null && previous.consentEpoch().equals(report.consentEpoch())) {
      if (report.sequence().equals(previous.sequence())) {
        if (!hash.equals(source.getCollectorReportHash()))
          throw new PerceptionException(409, "collector_sequence_content_conflict");
        // A delayed duplicate is acknowledged, but must not refresh last-seen time.
        return view(source);
      }
      if (report.sequence() < previous.sequence()
          || report.observedAt().isBefore(previous.observedAt()))
        throw new PerceptionException(409, "collector_report_out_of_order");
    }
    Instant now = perceptionClock.instant();
    if (report.observedAt().isBefore(now.minusSeconds(300))
        || report.observedAt().isAfter(now.plusSeconds(60)))
      throw new PerceptionException(400, "collector_report_time_invalid");
    source.setCollectorReportJson(json);
    source.setCollectorReportHash(hash);
    source.setCollectorReceivedAt(now);
    return view(source);
  }

  private void requireMobile(PerceptionSourceEntity source) {
    if (!Set.of("SMS", "LOCATION").contains(source.getKind()))
      throw new PerceptionException(400, "collector_kind_unsupported");
  }

  private PerceptionRequests.CollectorReport read(PerceptionSourceEntity source) {
    if (source.getCollectorReportJson() == null) return null;
    try {
      return mapper.readValue(
          source.getCollectorReportJson(), PerceptionRequests.CollectorReport.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("invalid_stored_collector_report");
    }
  }

  private Status view(PerceptionSourceEntity source) {
    var report = read(source);
    String sourceStatus = sources.view(source).status();
    Instant freshUntil =
        source.getCollectorReceivedAt() == null
            ? null
            : source.getCollectorReceivedAt().plusSeconds(FRESH_SECONDS);
    String status;
    if (!"active".equals(sourceStatus)) status = sourceStatus;
    else if (report == null) status = "unknown";
    else if (report.consentEpoch() != source.getConsentEpoch()) status = "needs_report";
    else if (freshUntil == null || !freshUntil.isAfter(perceptionClock.instant())) status = "stale";
    else if ("unsupported".equals(report.collectionMode())) status = "unsupported";
    else if ("manual".equals(report.collectionMode())) status = "manual_only";
    else if (!"granted".equals(report.permission())) status = "permission_required";
    else if ("restricted".equals(report.background())) status = "background_restricted";
    else if ("unknown".equals(report.background()) || "not_applicable".equals(report.background()))
      status = "background_unknown";
    else if (report.pendingCount() > 0) status = "backlog_reported";
    else status = "ready_reported";
    return new Status(
        source.getId(),
        source.getDeviceId(),
        sourceStatus,
        source.getConsentEpoch(),
        status,
        report == null ? "none" : "self_reported",
        report,
        source.getCollectorReceivedAt(),
        freshUntil);
  }
}
