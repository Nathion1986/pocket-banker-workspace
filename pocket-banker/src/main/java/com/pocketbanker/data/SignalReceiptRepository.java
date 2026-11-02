package com.pocketbanker.data;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface SignalReceiptRepository extends JpaRepository<SignalReceiptEntity, String> {
  Optional<SignalReceiptEntity> findBySourceIdAndExternalIdHash(
      String sourceId, String externalIdHash);

  Optional<SignalReceiptEntity> findByIdAndUserId(String id, String userId);

  Page<SignalReceiptEntity> findByUserIdOrderByReceivedAtDesc(String userId, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from SignalReceiptEntity r where r.id = :id")
  Optional<SignalReceiptEntity> lockById(String id);

  @Modifying
  @Query(
      "update SignalReceiptEntity r set r.status = 'retry_required', r.reason = 'lease_expired', r.updatedAt = :now where r.status in ('queued','processing') and r.leaseUntil < :now")
  int recoverExpired(java.time.Instant now);
}
