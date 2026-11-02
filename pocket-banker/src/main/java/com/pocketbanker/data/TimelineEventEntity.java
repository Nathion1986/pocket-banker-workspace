package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "events")
@Getter
@Setter
public class TimelineEventEntity {
  @Id private String id;
  private String userId;
  private String receiptId;
  private String eventType;
  private String kind;
  private String title;

  @Column(precision = 19, scale = 2)
  private java.math.BigDecimal amount;

  private String currency;
  private String dueDate;
  private String recurrence;
  private String accountLast4;
  private Instant occurredAt;
  private String source;
  private double confidence;
  private String extractorVersion;
  private String status;
  private Instant createdAt;
  private Instant expiresAt;
  private Instant confirmedAt;
  private String subjectId;
  @Lob private String payloadJson;
  private String taskId;
  private String supersededBy;
  private String city;
  private String duplicateOf;
}
