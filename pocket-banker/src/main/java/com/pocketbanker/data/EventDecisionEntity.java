package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "event_decisions")
@Getter
@Setter
public class EventDecisionEntity {
  @Id private String id;
  private String eventId;
  private String userId;
  private String decision;
  private Instant decidedAt;
}
