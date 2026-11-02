package com.pocketbanker.perception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.data.LocationChallengeEntity;
import com.pocketbanker.data.LocationChallengeRepository;
import com.pocketbanker.profile.EventService;
import com.pocketbanker.security.PerceptionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocationService {
  private final SourceService sources;
  private final LocationChallengeRepository challenges;
  private final EventService events;
  private final CityResolver cities;
  private final Clock perceptionClock;
  private final ObjectMapper mapper;
  private final com.pocketbanker.data.BackendStore db;

  public record ChallengeView(
      String id,
      String sourceId,
      String deviceId,
      String riskEventId,
      String status,
      Instant expiresAt,
      String cityCode,
      String cityName,
      Instant collectedAt) {}

  @Transactional
  public ChallengeView create(String user, PerceptionRequests.CreateChallenge input) {
    var source = sources.lockOwned(user, input.sourceId());
    sources.requireActive(source);
    if (!"LOCATION".equals(source.getKind()) || !input.deviceId().equals(source.getDeviceId())) {
      throw new PerceptionException(400, "location_device_mismatch");
    }
    events.requireRiskEvent(user, input.riskEventId());
    var c = new LocationChallengeEntity();
    c.setId(UUID.randomUUID().toString());
    c.setSourceId(source.getId());
    c.setUserId(user);
    c.setDeviceId(input.deviceId());
    c.setRiskEventId(input.riskEventId());
    c.setCreatedAt(perceptionClock.instant());
    c.setExpiresAt(c.getCreatedAt().plusSeconds(30));
    c.setStatus("pending");
    return view(challenges.save(c));
  }

  @Transactional
  public ChallengeView reply(String token, PerceptionRequests.LocationReply input) {
    var source = sources.authenticateLocked(input.sourceId(), token, "LOCATION");
    var c =
        challenges
            .lockById(input.challengeId())
            .orElseThrow(() -> new PerceptionException(404, "challenge_not_found"));
    if (!c.getSourceId().equals(source.getId())
        || !c.getUserId().equals(source.getUserId())
        || !c.getDeviceId().equals(input.deviceId()))
      throw new PerceptionException(404, "challenge_not_found");
    String hash;
    try {
      hash = PerceptionAccess.hash(mapper.writeValueAsString(input));
    } catch (Exception e) {
      throw new PerceptionException(400, "invalid_location");
    }
    expireResult(c);
    if (c.getResponseHash() != null) {
      if (c.getResponseHash().equals(hash)) return view(c);
      throw new PerceptionException(409, "challenge_already_consumed");
    }
    if (!c.getExpiresAt().isAfter(perceptionClock.instant())) {
      c.setStatus("expired");
      return view(c);
    }
    events.requireRiskEvent(c.getUserId(), c.getRiskEventId());
    if ("denied".equals(input.permission())) {
      if (input.latitude() != null || input.longitude() != null)
        throw new PerceptionException(400, "denied_location_has_coordinates");
      c.setStatus("denied");
    } else {
      if (input.collectedAt() == null
          || input.latitude() == null
          || input.longitude() == null
          || input.accuracy() == null
          || input.coordinateSystem() == null
          || !Double.isFinite(input.latitude())
          || !Double.isFinite(input.longitude())
          || !Double.isFinite(input.accuracy())
          || input.collectedAt().isBefore(c.getCreatedAt().minusSeconds(5))
          || input.collectedAt().isAfter(perceptionClock.instant().plusSeconds(5))
          || input.collectedAt().isAfter(c.getExpiresAt())) {
        throw new PerceptionException(400, "invalid_or_stale_location");
      }
      c.setCollectedAt(input.collectedAt());
      if (input.accuracy() > 1000) {
        c.setStatus("low_accuracy");
      } else {
        var city =
            cities.resolve(
                input.latitude(), input.longitude(), input.coordinateSystem(), input.accuracy());
        c.setStatus(city.isPresent() ? "resolved" : "unresolved");
        city.ifPresent(
            result -> {
              c.setCityCode(result.code());
              c.setCityName(result.name());
              c.setBoundaryVersion(cities.version());
              c.setResultExpiresAt(perceptionClock.instant().plusSeconds(3600));
            });
      }
    }
    c.setResponseHash(hash);
    c.setRespondedAt(perceptionClock.instant());
    return view(c);
  }

  @Transactional
  public ChallengeView get(String user, String id) {
    var c =
        challenges
            .findByIdAndUserId(id, user)
            .orElseThrow(() -> new PerceptionException(404, "challenge_not_found"));
    if ("pending".equals(c.getStatus()) && !c.getExpiresAt().isAfter(perceptionClock.instant()))
      c.setStatus("expired");
    expireResult(c);
    return view(c);
  }

  private void expireResult(LocationChallengeEntity c) {
    if (c.getResultExpiresAt() != null
        && !c.getResultExpiresAt().isAfter(perceptionClock.instant())) {
      c.setCityCode(null);
      c.setCityName(null);
      c.setCollectedAt(null);
      c.setBoundaryVersion(null);
      c.setStatus("expired");
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${perception.location-cleanup-ms:30000}")
  @Transactional
  public void cleanup() {
    db.update(
        "UPDATE location_challenges SET city_code=NULL,city_name=NULL,collected_at=NULL,boundary_version=NULL,status='expired' WHERE result_expires_at<=? AND status='resolved'",
        perceptionClock.instant());
  }

  private ChallengeView view(LocationChallengeEntity c) {
    return new ChallengeView(
        c.getId(),
        c.getSourceId(),
        c.getDeviceId(),
        c.getRiskEventId(),
        c.getStatus(),
        c.getExpiresAt(),
        c.getCityCode(),
        c.getCityName(),
        c.getCollectedAt());
  }
}
