package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionOwnershipServiceTest {

  private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 13, 12, 0);

  @Mock private SessionRepository sessionRepository;
  @Mock private EntityManager entityManager;
  @InjectMocks private SessionOwnershipService ownershipService;

  @Test
  void ownerChangesIncrementRevisionButSameOwnerIsANoOp() {
    var ownerA = consultant("a");
    var ownerB = consultant("b");
    var session = session(17L, ownerA, 4L);
    when(sessionRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(session));

    var changed =
        ownershipService.updateOwnerAndStatus(session(17L, ownerA, 4L), ownerB, IN_PROGRESS);

    assertThat(changed.revision()).isEqualTo(5L);
    assertThat(session.getConsultant()).isSameAs(ownerB);
    assertThat(session.getOwnershipRevision()).isEqualTo(5L);

    var unchanged = ownershipService.updateOwnerAndStatus(session, ownerB, IN_PROGRESS);

    assertThat(unchanged.revision()).isEqualTo(5L);
    assertThat(session.getOwnershipRevision()).isEqualTo(5L);
  }

  @Test
  void flushedRowVersionIsPropagatedBackToDetachedCaller() {
    var ownerA = consultant("a");
    var ownerB = consultant("b");
    var current = session(21L, ownerA, 0L);
    current.setRowVersion(6L);
    var detachedCaller = session(21L, ownerA, 0L);
    detachedCaller.setRowVersion(6L);
    when(sessionRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(current));
    doAnswer(
            ignored -> {
              current.setRowVersion(7L);
              return null;
            })
        .when(entityManager)
        .flush();

    ownershipService.updateOwnerAndStatus(detachedCaller, ownerB, IN_PROGRESS);

    assertThat(detachedCaller.getRowVersion()).isEqualTo(7L);
  }

  @Test
  void staleCallerRowVersionCannotBeBlessedByOwnershipUpdate() {
    var ownerA = consultant("a");
    Consultant ownerB = mock(Consultant.class);
    var current = session(22L, ownerA, 0L);
    current.setRowVersion(9L);
    var staleCaller = session(22L, ownerA, 0L);
    staleCaller.setRowVersion(8L);
    when(sessionRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(current));

    assertThatThrownBy(
            () -> ownershipService.updateOwnerAndStatus(staleCaller, ownerB, IN_PROGRESS))
        .isInstanceOf(ConflictException.class);

    assertThat(current.getConsultant()).isSameAs(ownerA);
    assertThat(staleCaller.getRowVersion()).isEqualTo(8L);
    verify(sessionRepository, never()).save(current);
  }

  @Test
  void clearingOwnerIncrementsRevisionAndPreservesNonnegativeMonotonicValue() {
    var owner = consultant("a");
    var session = session(18L, owner, 0L);
    when(sessionRepository.findByIdForUpdate(18L)).thenReturn(Optional.of(session));

    ownershipService.updateOwnerAndStatus(session(18L, owner, 0L), null, NEW);

    assertThat(session.getConsultant()).isNull();
    assertThat(session.getOwnershipRevision()).isOne();
  }

  @Test
  void staleExpectedOwnerOrRevisionCannotOverwriteCurrentOwner() {
    var ownerA = consultant("a");
    var ownerB = consultant("b");
    var current = session(19L, ownerB, 8L);
    when(sessionRepository.findByIdForUpdate(19L)).thenReturn(Optional.of(current));

    assertThatThrownBy(
            () ->
                ownershipService.updateOwnerAndStatus(
                    session(19L, ownerA, 7L), ownerA, IN_PROGRESS))
        .isInstanceOf(ConflictException.class);

    assertThat(current.getConsultant()).isSameAs(ownerB);
    assertThat(current.getOwnershipRevision()).isEqualTo(8L);
    verify(sessionRepository, never()).save(current);
  }

  @Test
  void compensationOnlyRevertsItsOwnStillCurrentAssignmentEvenAfterActorReturns() {
    var ownerA = consultant("a");
    var ownerB = consultant("b");
    var current = session(20L, ownerA, 2L);
    when(sessionRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(current));

    var assignment =
        ownershipService.updateOwnerAndStatus(session(20L, ownerA, 2L), ownerB, IN_PROGRESS);
    ownershipService.updateOwnerAndStatus(session(20L, ownerB, 3L), ownerA, IN_PROGRESS);

    assertThat(
            ownershipService.compensateOwnerChange(
                20L, assignment, ownerA, NEW, LocalDateTime.now()))
        .isFalse();
    assertThat(current.getConsultant()).isSameAs(ownerA);
    assertThat(current.getOwnershipRevision()).isEqualTo(4L);
  }

  @Test
  void removalLocksIdsInStableOrderAndRechecksOwner() {
    var deleting = consultant("delete");
    var newer = consultant("newer");
    var first = session(1L, newer, 6L);
    var second = session(2L, deleting, 3L);
    second.setStatus(NEW);
    when(sessionRepository.findIdsByConsultantAndStatusInOrderById(deleting, List.of(NEW)))
        .thenReturn(List.of(2L, 1L));
    when(sessionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
    when(sessionRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

    ownershipService.clearOwnerFromSessions(deleting, List.of(NEW));

    var order = inOrder(sessionRepository);
    order.verify(sessionRepository).findByIdForUpdate(1L);
    order.verify(sessionRepository).findByIdForUpdate(2L);
    assertThat(first.getConsultant()).isSameAs(newer);
    assertThat(first.getOwnershipRevision()).isEqualTo(6L);
    assertThat(second.getConsultant()).isNull();
    assertThat(second.getOwnershipRevision()).isEqualTo(4L);
  }

  @Test
  void removalKeepsOwnerWhenStatusChangedAfterCandidateSelection() {
    var deleting = consultant("delete");
    var nowInProgress = session(3L, deleting, 2L);
    when(sessionRepository.findIdsByConsultantAndStatusInOrderById(deleting, List.of(NEW)))
        .thenReturn(List.of(3L));
    when(sessionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(nowInProgress));

    ownershipService.clearOwnerFromSessions(deleting, List.of(NEW));

    assertThat(nowInProgress.getConsultant()).isSameAs(deleting);
    assertThat(nowInProgress.getOwnershipRevision()).isEqualTo(2L);
    verify(sessionRepository, never()).save(nowInProgress);
  }

  private static Consultant consultant(String id) {
    Consultant consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn(id);
    return consultant;
  }

  private static Session session(Long id, Consultant owner, long revision) {
    return Session.builder()
        .id(id)
        .consultant(owner)
        .ownershipRevision(revision)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("00000")
        .status(IN_PROGRESS)
        .build();
  }
}
