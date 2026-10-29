package de.caritas.cob.userservice.api.service.creationoutcome;

import static de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;

import de.caritas.cob.userservice.api.port.out.CreationOperationStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every public operation crosses a separate Spring proxy transaction from the journal. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class CreationOperationWriter {
  private final CreationOperationStore repository;

  public CreationOperationWriter(CreationOperationStore repository) {
    this.repository = repository;
  }

  public Claim prepare(Prepared p) {
    var now = now();
    var c = p.candidate();
    var receipt =
        new Receipt(
            c.operationId(),
            c.operationKey(),
            c.ownerId(),
            p.scope(),
            (short) 1,
            p.fingerprint(),
            0,
            now,
            now,
            null,
            null,
            null,
            DispatchState.PREPARED,
            WorkflowState.NOT_STARTED,
            RowState.NOT_ATTEMPTED,
            OnboardingState.NOT_ATTEMPTED,
            CompensationState.NOT_STARTED,
            Reason.NONE);
    if (repository.insert(receipt) != 1) throw failure(receipt.operationId());
    return new Claim(receipt, true);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Receipt> findByKey(UUID key) {
    return repository.findByKey(key);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Receipt> findById(UUID id) {
    return repository.findById(id);
  }

  public Receipt beginDispatch(UUID id, long version) {
    var old = required(id);
    if (old.version() != version || old.dispatch() != DispatchState.PREPARED) throw conflict(id);
    var next =
        copy(
            old,
            now(),
            null,
            null,
            DispatchState.DISPATCH_UNKNOWN,
            old.workflow(),
            old.row(),
            old.onboarding(),
            old.compensation(),
            Reason.NATIVE_OUTCOME_UNKNOWN);
    if (repository.compareAndSet(next, version) != 1) throw conflict(id);
    return next;
  }

  public Receipt capture(UUID id, long version, String nativeId) {
    if (nativeId == null || nativeId.isBlank() || nativeId.length() > 255) throw conflict(id);
    return update(
        id,
        version,
        old -> {
          if (old.nativeId() != null) {
            if (!old.nativeId().equals(nativeId)) throw conflict(id);
            return old;
          }
          if (old.dispatch() != DispatchState.DISPATCH_UNKNOWN) throw conflict(id);
          return copy(
              old,
              old.dispatchedAt(),
              now(),
              nativeId,
              DispatchState.NATIVE_ID_CAPTURED,
              old.workflow(),
              old.row(),
              old.onboarding(),
              old.compensation(),
              old.reason() == Reason.NATIVE_OUTCOME_UNKNOWN ? Reason.NONE : old.reason());
        });
  }

  public Receipt transition(UUID id, long version, CreationTransition transition) {
    Objects.requireNonNull(transition);
    return update(
        id,
        version,
        old -> {
          var workflow = old.workflow();
          var row = old.row();
          var onboarding = old.onboarding();
          var compensation = old.compensation();
          Reason reason;
          switch (transition) {
            case CreationTransition.Workflow change -> {
              legal(workflow, change.state(), id);
              workflow = change.state();
              reason = change.reason();
            }
            case CreationTransition.Row change -> {
              legal(row, change.state(), id);
              row = change.state();
              reason = change.reason();
            }
            case CreationTransition.Onboarding change -> {
              legal(onboarding, change.state(), id);
              onboarding = change.state();
              reason = change.reason();
            }
            case CreationTransition.Compensation change -> {
              legal(compensation, change.state(), id);
              compensation = change.state();
              reason = change.reason();
            }
          }
          Objects.requireNonNull(reason);
          if (workflow == old.workflow()
              && row == old.row()
              && onboarding == old.onboarding()
              && compensation == old.compensation()
              && (reason == Reason.NONE || reason == old.reason())) return old;
          return copy(
              old,
              old.dispatchedAt(),
              old.capturedAt(),
              old.nativeId(),
              old.dispatch(),
              workflow,
              row,
              onboarding,
              compensation,
              reason == Reason.NONE ? old.reason() : reason);
        });
  }

  private Receipt update(UUID id, long expectedVersion, UnaryOperator<Receipt> mutation) {
    var current = required(id);
    // One bounded rebase, whether the supplied version is already stale or a concurrent CAS wins.
    boolean rebased = current.version() != expectedVersion;
    for (int attempt = 0; attempt < 2; attempt++) {
      var next = mutation.apply(current);
      if (next == current) return current;
      if (repository.compareAndSet(next, current.version()) == 1) return next;
      if (rebased || attempt == 1) throw conflict(id);
      current = required(id);
      rebased = true;
    }
    throw conflict(id);
  }

  private static void legal(Enum<?> from, Enum<?> to, UUID id) {
    if (from == to) return;
    boolean allowed =
        switch (from) {
          case WorkflowState f ->
              (f == WorkflowState.NOT_STARTED && to == WorkflowState.IN_PROGRESS)
                  || (f == WorkflowState.IN_PROGRESS
                      && (to == WorkflowState.RETURNED
                          || to == WorkflowState.FAILED
                          || to == WorkflowState.UNKNOWN));
          case RowState f ->
              (f == RowState.NOT_ATTEMPTED && to == RowState.COMMIT_UNKNOWN)
                  || (f == RowState.COMMIT_UNKNOWN
                      && (to == RowState.COMMITTED || to == RowState.ROLLED_BACK));
          case OnboardingState f ->
              (f == OnboardingState.NOT_ATTEMPTED && to == OnboardingState.OUTCOME_UNKNOWN)
                  || (f == OnboardingState.OUTCOME_UNKNOWN
                      && (to == OnboardingState.ISSUER_RETURNED || to == OnboardingState.FAILED));
          case CompensationState f ->
              (f == CompensationState.NOT_STARTED
                      && to == CompensationState.STARTED_OUTCOME_UNKNOWN)
                  || (f == CompensationState.STARTED_OUTCOME_UNKNOWN
                      && to == CompensationState.RETURNED_UNVERIFIED);
          default -> false;
        };
    if (!allowed) throw conflict(id);
  }

  private Receipt required(UUID id) {
    return repository.findById(id).orElseThrow(() -> failure(id));
  }

  private static Receipt copy(
      Receipt old,
      Instant dispatched,
      Instant captured,
      String nativeId,
      DispatchState dispatch,
      WorkflowState workflow,
      RowState row,
      OnboardingState onboarding,
      CompensationState compensation,
      Reason reason) {
    return new Receipt(
        old.operationId(),
        old.operationKey(),
        old.ownerId(),
        old.scope(),
        old.fingerprintVersion(),
        old.requestFingerprint(),
        old.version() + 1,
        old.createdAt(),
        now(),
        dispatched,
        captured,
        nativeId,
        dispatch,
        workflow,
        row,
        onboarding,
        compensation,
        reason);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static CreationOperationFailure conflict(UUID id) {
    return new CreationOperationFailure(id, 409, Reason.JOURNAL_UNAVAILABLE);
  }

  private static CreationOperationFailure failure(UUID id) {
    return new CreationOperationFailure(id, 503, Reason.JOURNAL_UNAVAILABLE);
  }
}
