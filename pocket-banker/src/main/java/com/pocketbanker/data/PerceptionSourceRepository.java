package com.pocketbanker.data;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface PerceptionSourceRepository extends JpaRepository<PerceptionSourceEntity, String> {
  Page<PerceptionSourceEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable page);

  List<PerceptionSourceEntity> findByKindAndStatus(String kind, String status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PerceptionSourceEntity s where s.id = :id")
  Optional<PerceptionSourceEntity> lockById(String id);
}
