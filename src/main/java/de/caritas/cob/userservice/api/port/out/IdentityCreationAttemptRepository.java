package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface IdentityCreationAttemptRepository
    extends JpaRepository<IdentityCreationAttempt, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from IdentityCreationAttempt a where a.id = :id")
  Optional<IdentityCreationAttempt> findByIdForUpdate(@Param("id") String id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<IdentityCreationAttempt> findFirstByRequestKeyAndStatusInOrderByUpdateDateDesc(
      String requestKey, Collection<String> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<IdentityCreationAttempt> findByAccountId(String accountId);

  List<IdentityCreationAttempt> findByBootstrapSessionIdIsNotNullAndStatus(
      String status, Pageable page);

  List<IdentityCreationAttempt> findByStatusInOrderByUpdateDateAsc(
      Collection<String> states, Pageable page);
}
