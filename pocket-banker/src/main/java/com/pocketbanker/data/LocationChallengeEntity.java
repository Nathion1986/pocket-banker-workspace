package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "location_challenges")
@Getter
@Setter
public class LocationChallengeEntity {
  @Id private String id;
  private String sourceId;
  private String userId;
  private String deviceId;
  private String riskEventId;
  private Instant createdAt;
  private Instant expiresAt;
  private String status;
  private String responseHash;
  private String cityCode;
  private String cityName;
  private Instant collectedAt;
  private Instant respondedAt;
  private Instant resultExpiresAt;
  private String boundaryVersion;
}
