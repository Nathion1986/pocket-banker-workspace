package com.pocketbanker.data;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface LocationChallengeRepository
    extends JpaRepository<LocationChallengeEntity, String> {
  Optional<LocationChallengeEntity> findByIdAndUserId(String id, String userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from LocationChallengeEntity c where c.id = :id")
  Optional<LocationChallengeEntity> lockById(String id);
}
