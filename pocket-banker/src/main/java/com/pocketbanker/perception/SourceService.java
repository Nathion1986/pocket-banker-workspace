package com.pocketbanker.perception;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.*;
import com.pocketbanker.security.PerceptionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SourceService {
  private final PerceptionSourceRepository sources;
  private final ObjectMapper mapper;
  private final Clock perceptionClock;
  private final com.pocketbanker.security.AppAuthentication accounts;

  public record SourceView(
      String id,
      String kind,
      String deviceId,
      String consentId,
      String purpose,
      String status,
      Instant expiresAt,
      List<String> allowedSenders,
      String connectorRef,
      long consentEpoch) {}

  public record CreatedSource(SourceView source, String sourceToken) {}

  @Transactional
  public CreatedSource create(String user, PerceptionRequests.CreateSource input) {
    if (!input.consentGranted() || !input.expiresAt().isAfter(perceptionClock.instant())) {
      throw new PerceptionException(400, "active_consent_required");
    }
    if ("LOCATION".equals(input.kind())
        && (input.deviceId() == null || input.deviceId().isBlank())) {
      throw new PerceptionException(400, "device_id_required");
    }
    if (java.util.Set.of("SMS", "EMAIL").contains(input.kind())
        && input.allowedSenders().isEmpty()) {
      throw new PerceptionException(400, "sender_allowlist_required");
    }
    if (input.connectorRef() != null
        && !java.util.Set.of("EMAIL", "CALENDAR").contains(input.kind())) {
      throw new PerceptionException(400, "connector_kind_unsupported");
    }
    var entity = new PerceptionSourceEntity();
    String token = PerceptionAccess.newToken();
    entity.setId(UUID.randomUUID().toString());
    entity.setUserId(user);
    entity.setKind(input.kind());
    entity.setDeviceId(input.deviceId());
    entity.setConsentId(input.consentId());
    entity.setPurpose(
        "LOCATION".equals(input.kind())
            ? "risk_verification"
            : "HEALTH".equals(input.kind()) ? "coverage_reminder" : "finance_events");
    entity.setTokenHash(PerceptionAccess.hash(token));
    try {
      entity.setAllowedSendersJson(
          mapper.writeValueAsString(
              input.allowedSenders().stream()
                  .map(s -> s.strip().toLowerCase(Locale.ROOT))
                  .distinct()
                  .toList()));
    } catch (Exception e) {
      throw new PerceptionException(400, "invalid_senders");
    }
    entity.setConnectorRef(input.connectorRef());
    entity.setStatus("active");
    entity.setCreatedAt(perceptionClock.instant());
    entity.setExpiresAt(input.expiresAt());
    sources.save(entity);
    return new CreatedSource(view(entity), token);
  }

  @Transactional(readOnly = true)
  public List<SourceView> list(String user, int page) {
    return sources.findByUserIdOrderByCreatedAtDesc(user, PageRequest.of(page, 50)).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public SourceView revoke(String user, String id) {
    var source = lockOwned(user, id);
    if (!"revoked".equals(source.getStatus())) {
      source.setStatus("revoked");
      source.setConsentEpoch(source.getConsentEpoch() + 1);
      source.setRevokedAt(perceptionClock.instant());
    }
    return view(source);
  }

  @Transactional
  public SourceView pause(String user, String id, boolean paused) {
    var source = lockOwned(user, id);
    if (!"active".equals(source.getStatus())
        || !source.getExpiresAt().isAfter(perceptionClock.instant()))
      throw new PerceptionException(403, "source_inactive");
    if (source.isPaused() != paused) {
      source.setPaused(paused);
      source.setConsentEpoch(source.getConsentEpoch() + 1);
    }
    return view(source);
  }

  public void requireEpoch(PerceptionSourceEntity source, long expected) {
    requireActive(source);
    if (source.getConsentEpoch() != expected)
      throw new PerceptionException(409, "source_consent_changed");
  }

  public PerceptionSourceEntity lockOwned(String user, String id) {
    var source =
        sources.lockById(id).orElseThrow(() -> new PerceptionException(404, "source_not_found"));
    if (!source.getUserId().equals(user)) throw new PerceptionException(404, "source_not_found");
    return source;
  }

  @Transactional(readOnly = true)
  public boolean isCalendar(String user, String id) {
    var source =
        sources.findById(id).orElseThrow(() -> new PerceptionException(404, "source_not_found"));
    if (!user.equals(source.getUserId())) throw new PerceptionException(404, "source_not_found");
    return "CALENDAR".equals(source.getKind());
  }

  public PerceptionSourceEntity authenticateLocked(String id, String token, String kind) {
    var source = authenticateLocked(id, token);
    if (!kind.equals(source.getKind())) throw new PerceptionException(400, "source_kind_mismatch");
    return source;
  }

  public PerceptionSourceEntity authenticateLocked(String id, String token) {
    var source =
        sources
            .lockById(id)
            .orElseThrow(() -> new PerceptionException(401, "invalid_source_token"));
    if (!PerceptionAccess.equal(
        source.getTokenHash(), PerceptionAccess.hash(PerceptionAccess.bearer(token)))) {
      throw new PerceptionException(401, "invalid_source_token");
    }
    requireActive(source);
    return source;
  }

  public void requireActive(PerceptionSourceEntity source) {
    accounts.requireAccountActive(source.getUserId());
    if (!"active".equals(source.getStatus())
        || source.isPaused()
        || !source.getExpiresAt().isAfter(perceptionClock.instant())) {
      throw new PerceptionException(403, "source_inactive");
    }
  }

  public boolean senderAllowed(PerceptionSourceEntity source, String sender) {
    return senders(source).contains(sender.strip().toLowerCase(Locale.ROOT));
  }

  private List<String> senders(PerceptionSourceEntity source) {
    try {
      return mapper.readValue(source.getAllowedSendersJson(), new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("invalid_stored_allowlist");
    }
  }

  public SourceView view(PerceptionSourceEntity s) {
    String status =
        "active".equals(s.getStatus()) && !s.getExpiresAt().isAfter(perceptionClock.instant())
            ? "expired"
            : "active".equals(s.getStatus()) && s.isPaused() ? "paused" : s.getStatus();
    return new SourceView(
        s.getId(),
        s.getKind(),
        s.getDeviceId(),
        s.getConsentId(),
        s.getPurpose(),
        status,
        s.getExpiresAt(),
        senders(s),
        s.getConnectorRef(),
        s.getConsentEpoch());
  }
}
