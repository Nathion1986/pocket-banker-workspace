package com.pocketbanker.perception;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 输入 DTO 不含 userId，不能从请求自报归属。 */
public final class PerceptionRequests {
  private PerceptionRequests() {}

  public record CreateSource(
      @NotBlank @Pattern(regexp = "SMS|EMAIL|LOCATION|CALENDAR|HEALTH|FLOW|DIALOGUE") String kind,
      @Size(max = 100) String deviceId,
      @NotBlank @Size(max = 100) String consentId,
      @AssertTrue boolean consentGranted,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 160) String> allowedSenders,
      @Size(max = 100) String connectorRef,
      @NotNull @Future Instant expiresAt) {}

  public record TextSignal(
      @NotBlank @Size(max = 36) String sourceId,
      @NotBlank @Size(max = 200) String externalId,
      @NotNull Instant occurredAt,
      @NotBlank @Size(max = 160) String sender,
      @Size(max = 500) String subject,
      @NotBlank @Size(max = 32768) String text) {
    @Override
    public String toString() {
      return "TextSignal[redacted]";
    }
  }

  public record CreateChallenge(
      @NotBlank @Size(max = 36) String sourceId,
      @NotBlank @Size(max = 100) String deviceId,
      @NotBlank @Size(max = 36) String riskEventId) {}

  /** Device telemetry is self-reported evidence, never a grant of consent. */
  public record CollectorReport(
      @NotNull @Min(1) @Max(1) Integer protocolVersion,
      @NotBlank @Size(max = 100) String deviceId,
      @NotBlank @Pattern(regexp = "android|ios|harmonyos|other") String platform,
      @Size(max = 100) String osVersion,
      @NotNull @PositiveOrZero Long consentEpoch,
      @NotNull @Positive Long sequence,
      @NotNull Instant observedAt,
      @NotBlank @Pattern(regexp = "automatic|manual|unsupported") String collectionMode,
      @NotBlank @Pattern(regexp = "granted|denied|not_determined|restricted|not_required")
          String permission,
      @NotBlank @Pattern(regexp = "allowed|restricted|unknown|not_applicable") String background,
      @NotNull @Min(0) @Max(1000000) Integer pendingCount) {}

  public record LocationReply(
      @NotBlank @Size(max = 36) String sourceId,
      @NotBlank @Size(max = 36) String challengeId,
      @NotBlank @Size(max = 100) String deviceId,
      @NotBlank @Pattern(regexp = "granted|denied") String permission,
      Instant collectedAt,
      @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @DecimalMin("-180") @DecimalMax("180") Double longitude,
      @DecimalMin("0") Double accuracy,
      @Pattern(regexp = "WGS84|GCJ02") String coordinateSystem) {
    @Override
    public String toString() {
      return "LocationReply[redacted]";
    }
  }
}
