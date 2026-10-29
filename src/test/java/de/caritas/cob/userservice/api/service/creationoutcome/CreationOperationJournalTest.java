package de.caritas.cob.userservice.api.service.creationoutcome;

import static de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.consultant.create.AccountCreationAttemptJournal;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import de.caritas.cob.userservice.api.port.out.CreationOperationStore;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class CreationOperationJournalTest {
  private final CreationOperationStore repository = mock(CreationOperationStore.class);
  private final CreationOperationWriter writer = new CreationOperationWriter(repository);
  private final AccountCreationAttemptJournal journal =
      new AccountCreationAttemptJournal(
          mock(AccountCreationAttemptRepository.class), Clock.systemUTC(), writer);
  private final Candidate candidate = new Candidate(UUID.randomUUID(), UUID.randomUUID(), "owner");
  private final Scope scope = new Scope(7, List.of(1L), "COUNSELLOR");

  private Prepared prepared(Candidate c) {
    return new Prepared(c, scope, "a".repeat(64));
  }

  private Receipt receipt(DispatchState dispatch, long version, String nativeId) {
    var time = Instant.parse("2026-10-20T00:00:00Z");
    return new Receipt(
        candidate.operationId(),
        candidate.operationKey(),
        candidate.ownerId(),
        scope,
        (short) 1,
        "a".repeat(64),
        version,
        time,
        time,
        dispatch == DispatchState.PREPARED ? null : time,
        nativeId == null ? null : time,
        nativeId,
        dispatch,
        WorkflowState.NOT_STARTED,
        RowState.NOT_ATTEMPTED,
        OnboardingState.NOT_ATTEMPTED,
        CompensationState.NOT_STARTED,
        Reason.NONE);
  }

  private void collision(Receipt winner) {
    doThrow(new DuplicateKeyException("synthetic")).when(repository).insert(any());
    when(repository.findByKey(candidate.operationKey())).thenReturn(Optional.of(winner));
  }

  @Test
  void immutableClaimRejectsChangedOwnerScopeOrPayload() {
    collision(receipt(DispatchState.PREPARED, 0, null));
    assertThatThrownBy(
            () ->
                journal.claim(
                    prepared(new Candidate(UUID.randomUUID(), candidate.operationKey(), "other"))))
        .isInstanceOfSatisfying(
            CreationOperationFailure.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(
            () ->
                journal.claim(
                    new Prepared(
                        candidate, new Scope(8, List.of(1L), "COUNSELLOR"), "a".repeat(64))))
        .isInstanceOfSatisfying(
            CreationOperationFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    assertThatThrownBy(() -> journal.claim(new Prepared(candidate, scope, "b".repeat(64))))
        .isInstanceOfSatisfying(
            CreationOperationFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    verify(repository, never()).compareAndSet(any(), anyLong());
  }

  @Test
  void replayReturnsPersistedWinnerNotCandidate() {
    Receipt winner = receipt(DispatchState.PREPARED, 0, null);
    collision(winner);
    Candidate loser = new Candidate(UUID.randomUUID(), candidate.operationKey(), "owner");
    var claim = journal.claim(prepared(loser));
    var context = new CreationOperationContext(loser);
    context.bind(claim.receipt());
    assertThat(claim.newlyInserted()).isFalse();
    assertThat(context.persistedOperationId()).contains(candidate.operationId());
    assertThatThrownBy(() -> journal.beginDispatch(context, claim))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unknownNeverBecomesDispatchable() {
    var replays = new java.util.ArrayList<Receipt>();
    for (DispatchState state : DispatchState.values()) {
      replays.add(receipt(state, 3, state == DispatchState.NATIVE_ID_CAPTURED ? "native" : null));
    }
    for (WorkflowState state : WorkflowState.values())
      replays.add(
          dimensions(
              state,
              RowState.NOT_ATTEMPTED,
              OnboardingState.NOT_ATTEMPTED,
              CompensationState.NOT_STARTED,
              Reason.NATIVE_OUTCOME_UNKNOWN));
    for (RowState state : RowState.values())
      replays.add(
          dimensions(
              WorkflowState.UNKNOWN,
              state,
              OnboardingState.NOT_ATTEMPTED,
              CompensationState.NOT_STARTED,
              Reason.NATIVE_OUTCOME_UNKNOWN));
    for (OnboardingState state : OnboardingState.values())
      replays.add(
          dimensions(
              WorkflowState.UNKNOWN,
              RowState.COMMIT_UNKNOWN,
              state,
              CompensationState.NOT_STARTED,
              Reason.NATIVE_OUTCOME_UNKNOWN));
    for (CompensationState state : CompensationState.values())
      replays.add(
          dimensions(
              WorkflowState.UNKNOWN,
              RowState.COMMIT_UNKNOWN,
              OnboardingState.OUTCOME_UNKNOWN,
              state,
              Reason.NATIVE_OUTCOME_UNKNOWN));
    for (Reason reason : Reason.values())
      replays.add(
          dimensions(
              WorkflowState.UNKNOWN,
              RowState.COMMIT_UNKNOWN,
              OnboardingState.OUTCOME_UNKNOWN,
              CompensationState.STARTED_OUTCOME_UNKNOWN,
              reason));
    when(repository.compareAndSet(any(), anyLong())).thenReturn(1);
    for (Receipt persisted : replays) {
      when(repository.findById(candidate.operationId())).thenReturn(Optional.of(persisted));
      collision(persisted);
      var claim = journal.claim(prepared(candidate));
      var context = new CreationOperationContext(candidate);
      context.bind(claim.receipt());
      assertThatThrownBy(() -> journal.beginDispatch(context, claim))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(repository, never()).compareAndSet(any(), anyLong());
  }

  private Receipt dimensions(
      WorkflowState workflow,
      RowState row,
      OnboardingState onboarding,
      CompensationState compensation,
      Reason reason) {
    var r = receipt(DispatchState.DISPATCH_UNKNOWN, 4, null);
    return new Receipt(
        r.operationId(),
        r.operationKey(),
        r.ownerId(),
        r.scope(),
        r.fingerprintVersion(),
        r.requestFingerprint(),
        r.version(),
        r.createdAt(),
        r.updatedAt(),
        r.dispatchedAt(),
        r.capturedAt(),
        r.nativeId(),
        r.dispatch(),
        workflow,
        row,
        onboarding,
        compensation,
        reason);
  }

  @Test
  void terminalDimensionsCannotRegress() {
    var terminal =
        dimensions(
            WorkflowState.RETURNED,
            RowState.COMMITTED,
            OnboardingState.ISSUER_RETURNED,
            CompensationState.RETURNED_UNVERIFIED,
            Reason.NONE);
    when(repository.findById(candidate.operationId())).thenReturn(Optional.of(terminal));
    for (var transition :
        List.<CreationTransition>of(
            new CreationTransition.Workflow(WorkflowState.IN_PROGRESS, Reason.NONE),
            new CreationTransition.Row(RowState.COMMIT_UNKNOWN, Reason.NONE),
            new CreationTransition.Onboarding(OnboardingState.OUTCOME_UNKNOWN, Reason.NONE),
            new CreationTransition.Compensation(
                CompensationState.STARTED_OUTCOME_UNKNOWN, Reason.NONE))) {
      assertThatThrownBy(() -> writer.transition(candidate.operationId(), 4, transition))
          .isInstanceOf(CreationOperationFailure.class);
    }
    verify(repository, never()).compareAndSet(any(), anyLong());
  }

  @Test
  void illegalAndStaleTransitionsFailClosed() {
    Receipt known = receipt(DispatchState.NATIVE_ID_CAPTURED, 2, "native");
    when(repository.findById(candidate.operationId())).thenReturn(Optional.of(known));
    assertThat(writer.capture(candidate.operationId(), 0, "native")).isEqualTo(known);
    assertThatThrownBy(() -> writer.capture(candidate.operationId(), 2, "different"))
        .isInstanceOf(CreationOperationFailure.class);
    assertThatThrownBy(
            () ->
                writer.transition(
                    candidate.operationId(),
                    2,
                    new CreationTransition.Workflow(WorkflowState.RETURNED, Reason.NONE)))
        .isInstanceOf(CreationOperationFailure.class);
    when(repository.compareAndSet(any(), anyLong())).thenReturn(0);
    assertThatThrownBy(
            () ->
                writer.transition(
                    candidate.operationId(),
                    2,
                    new CreationTransition.Workflow(WorkflowState.IN_PROGRESS, Reason.NONE)))
        .isInstanceOf(CreationOperationFailure.class);
    verify(repository, times(2)).compareAndSet(any(), anyLong());
    verify(repository, times(5)).findById(candidate.operationId());
  }

  @Test
  void winningInvocationDispatchesOnlyOnceAndCaptureCannotLoseId() {
    Receipt initial = receipt(DispatchState.PREPARED, 0, null);
    when(repository.findById(candidate.operationId())).thenReturn(Optional.of(initial));
    when(repository.compareAndSet(any(), anyLong())).thenReturn(1);
    var claim = new Claim(initial, true);
    var context = new CreationOperationContext(candidate);
    context.bind(initial);
    var capture = journal.beginDispatch(context, claim);
    assertThat(context.receipt().dispatch()).isEqualTo(DispatchState.DISPATCH_UNKNOWN);
    assertThatThrownBy(() -> journal.beginDispatch(context, claim))
        .isInstanceOf(IllegalStateException.class);
    when(repository.findById(candidate.operationId()))
        .thenAnswer(inv -> Optional.of(context.receipt()));
    capture.captured(new CreatedIdentity("native"));
    capture.compensationStarting();
    capture.compensationReturned();
    assertThat(context.receipt().nativeId()).isEqualTo("native");
    assertThat(context.receipt().dispatch()).isEqualTo(DispatchState.NATIVE_ID_CAPTURED);
    assertThat(context.receipt().compensation()).isEqualTo(CompensationState.RETURNED_UNVERIFIED);
  }
}
