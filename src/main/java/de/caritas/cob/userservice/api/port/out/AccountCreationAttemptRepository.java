package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AccountCreationAttempt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountCreationAttemptRepository
    extends JpaRepository<AccountCreationAttempt, Long> {
  Optional<AccountCreationAttempt> findByOperationKey(UUID operationKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT attempt FROM AccountCreationAttempt attempt WHERE attempt.operationKey = :operationKey")
  Optional<AccountCreationAttempt> findByOperationKeyForUpdate(
      @Param("operationKey") UUID operationKey);
}
