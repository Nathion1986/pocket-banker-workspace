package com.pocketbanker.data;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mail_cursors")
@Getter
@Setter
public class MailCursorEntity {
  @Id private String sourceId;
  private String scopeHash;
  private long uidValidity;
  private long lastUid;
  private Instant updatedAt;
}
