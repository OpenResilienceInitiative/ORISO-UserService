package de.caritas.cob.userservice.api.admin.service.consultant.create;

import de.caritas.cob.userservice.api.model.AccountCreationAttempt;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores only the ownership and native result needed to safely resume a tracked creation. */
@Component
@RequiredArgsConstructor
public class AccountCreationAttemptJournal {
  private final AccountCreationAttemptRepository repository;
  private final Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AccountCreationAttempt begin(UUID operationKey, String ownerId, Long tenantId) {
    require(operationKey, "operation key");
    require(ownerId, "owner id");
    if (tenantId == null || tenantId < 0) {
      throw new IllegalArgumentException("tenant id is required");
    }
    return repository
        .findByOperationKey(operationKey)
        .orElseGet(() -> saveOrReadExisting(operationKey, ownerId, tenantId));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AccountCreationAttempt recordNativeIdentity(UUID operationKey, String nativeIdentityId) {
    require(operationKey, "operation key");
    require(nativeIdentityId, "native identity id");
    AccountCreationAttempt attempt =
        repository
            .findByOperationKeyForUpdate(operationKey)
            .orElseThrow(() -> new IllegalArgumentException("unknown creation attempt"));
    if (attempt.getNativeIdentityId() != null
        && !attempt.getNativeIdentityId().equals(nativeIdentityId)) {
      throw new IllegalStateException("creation attempt already has a different native identity");
    }
    if (attempt.getNativeIdentityId() == null) {
      attempt.setNativeIdentityId(nativeIdentityId);
      attempt.setStatus(AccountCreationAttempt.Status.NATIVE_ID_KNOWN);
      attempt.setUpdatedAt(now());
    }
    return attempt;
  }

  private AccountCreationAttempt saveOrReadExisting(
      UUID operationKey, String ownerId, Long tenantId) {
    LocalDateTime now = now();
    try {
      return repository.saveAndFlush(
          AccountCreationAttempt.builder()
              .operationKey(operationKey)
              .ownerId(ownerId)
              .tenantId(tenantId)
              .status(AccountCreationAttempt.Status.PENDING)
              .createdAt(now)
              .updatedAt(now)
              .build());
    } catch (DataIntegrityViolationException exception) {
      return repository.findByOperationKey(operationKey).orElseThrow(() -> exception);
    }
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private static void require(Object value, String name) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
