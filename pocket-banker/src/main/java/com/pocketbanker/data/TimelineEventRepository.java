package com.pocketbanker.data;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface TimelineEventRepository extends JpaRepository<TimelineEventEntity, String> {
  Page<TimelineEventEntity> findByUserIdAndStatusOrderByOccurredAtDescIdAsc(
      String userId, String status, Pageable page);

  @Query(
      "select e from TimelineEventEntity e where e.userId = :userId and e.status = :status and e.eventType <> 'prediction' and e.supersededBy is null order by e.occurredAt desc, e.id")
  Page<TimelineEventEntity> facts(String userId, String status, Pageable page);

  Optional<TimelineEventEntity> findByReceiptId(String receiptId);

  Optional<TimelineEventEntity> findByIdAndUserId(String id, String userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from TimelineEventEntity e where e.id = :id and e.userId = :userId")
  Optional<TimelineEventEntity> lockOwned(String id, String userId);

  @Modifying
  @Query(
      "update TimelineEventEntity e set e.status = 'expired' where e.userId = :userId and e.status = 'candidate' and e.expiresAt <= :now")
  int expireCandidates(String userId, java.time.Instant now);
}
