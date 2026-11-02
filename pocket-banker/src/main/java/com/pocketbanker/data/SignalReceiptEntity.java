package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "perception_log")
@Getter
@Setter
public class SignalReceiptEntity {
  @Id private String id;
  private String sourceId;
  private String userId;
  private String externalIdHash;
  private String contentHash;
  private Instant occurredAt;
  private Instant receivedAt;
  private Instant updatedAt;
  private String status;
  private String reason;
  private int attempts;
  private Instant leaseUntil;
  private String canonicalEventId;
  private long consentEpoch;
}
