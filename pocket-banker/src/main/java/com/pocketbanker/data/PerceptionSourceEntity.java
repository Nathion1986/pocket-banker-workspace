package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "perception_sources")
@Getter
@Setter
public class PerceptionSourceEntity {
  @Id private String id;
  private String userId;
  private String kind;
  private String deviceId;
  private String consentId;
  private String purpose;
  private String tokenHash;
  private String allowedSendersJson;
  private String connectorRef;
  private String status;
  private boolean paused;
  private long consentEpoch;
  private Instant createdAt;
  private Instant expiresAt;
  private Instant revokedAt;
  private String collectorReportJson;
  private String collectorReportHash;
  private Instant collectorReceivedAt;
}
