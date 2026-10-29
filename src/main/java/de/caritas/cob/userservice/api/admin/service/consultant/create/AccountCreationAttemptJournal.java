package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;

import de.caritas.cob.userservice.api.model.AccountCreationAttempt;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture;
import de.caritas.cob.userservice.api.service.creationoutcome.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores only the ownership and native result needed to safely resume a tracked creation. */
@Component
public class AccountCreationAttemptJournal {
  private final AccountCreationAttemptRepository repository;
  private final Clock clock;
  private final CreationOperationWriter trackedWriter;

  /** Retained storage-only constructor for existing foundation callers. */
  public AccountCreationAttemptJournal(AccountCreationAttemptRepository repository, Clock clock) {
    this(repository, clock, null);
  }

  @Autowired
  public AccountCreationAttemptJournal(
      AccountCreationAttemptRepository repository,
      Clock clock,
      CreationOperationWriter trackedWriter) {
    this.repository = repository;
    this.clock = clock;
    this.trackedWriter = trackedWriter;
  }

  private CreationOperationWriter tracked() {
    if (trackedWriter == null)
      throw new IllegalStateException("Tracked creation journal unavailable");
    return trackedWriter;
  }

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
    if (attempt.getReceiptId() != null)
      throw new IllegalStateException("Tracked creation requires its versioned capture");
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

  public Claim claim(Prepared prepared) {
    try {
      return tracked().prepare(prepared);
    } catch (DuplicateKeyException collision) {
      // The proxied insert has ended (and rolled back) before opening this read transaction.
      var existing =
          tracked()
              .findByKey(prepared.candidate().operationKey())
              .orElseThrow(() -> new IllegalStateException("Operation unavailable"));
      if (!existing.ownerId().equals(prepared.candidate().ownerId()))
        throw new CreationOperationFailure(existing.operationId(), 404, Reason.NONE);
      if (!existing.scope().equals(prepared.scope())
          || !existing.requestFingerprint().equals(prepared.fingerprint()))
        throw new CreationOperationFailure(existing.operationId(), 409, Reason.NONE);
      return new Claim(existing, false);
    }
  }

  public Optional<Receipt> findByKey(UUID key) {
    return tracked().findByKey(key);
  }

  public Optional<Receipt> findById(UUID id) {
    return tracked().findById(id);
  }

  public NativeCreationCapture beginDispatch(CreationOperationContext context, Claim claim) {
    context.consumeDispatch(claim);
    context.bind(
        tracked().beginDispatch(context.receipt().operationId(), claim.receipt().version()));
    return new NativeCreationCapture() {
      @Override
      public void captured(CreatedIdentity identity) {
        try {
          context.bind(
              tracked()
                  .capture(
                      context.receipt().operationId(),
                      context.receipt().version(),
                      identity == null ? null : identity.getUserId()));
        } catch (RuntimeException failure) {
          throw new CreationOperationFailure(
              context.receipt().operationId(), 503, Reason.NATIVE_CAPTURE_FAILED);
        }
      }

      @Override
      public void compensationStarting() {
        compensation(CompensationState.STARTED_OUTCOME_UNKNOWN);
      }

      @Override
      public void compensationReturned() {
        compensation(CompensationState.RETURNED_UNVERIFIED);
      }

      private void compensation(CompensationState state) {
        try {
          context.bind(
              tracked()
                  .transition(
                      context.receipt().operationId(),
                      context.receipt().version(),
                      new CreationTransition.Compensation(state, Reason.NONE)));
        } catch (RuntimeException failure) {
          throw new CreationOperationFailure(
              context.receipt().operationId(), 503, Reason.JOURNAL_UNAVAILABLE);
        }
      }
    };
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
