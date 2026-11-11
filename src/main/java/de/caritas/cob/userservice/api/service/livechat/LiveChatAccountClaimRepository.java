package de.caritas.cob.userservice.api.service.livechat;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface LiveChatAccountClaimRepository
    extends CrudRepository<LiveChatAccountClaim, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT claim FROM LiveChatAccountClaim claim WHERE claim.userId = :userId")
  Optional<LiveChatAccountClaim> findForUpdate(@Param("userId") String userId);
}
