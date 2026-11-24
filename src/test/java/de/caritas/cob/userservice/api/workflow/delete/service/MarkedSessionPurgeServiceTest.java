package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarkedSessionPurgeServiceTest {

  @Mock private SessionRepository sessionRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private DeleteSessionService deleteSessionService;
  @InjectMocks private MarkedSessionPurgeService service;

  @Test
  void purgeMarkedSessions_Should_purgeTheRoomThenTheSession() {
    var session = session(1L, "!room:matrix");
    when(sessionRepository.findAllByDeleteDateNotNull()).thenReturn(List.of(session));
    when(matrixSynapseService.purgeRoom("!room:matrix")).thenReturn(true);
    when(deleteSessionService.performSessionDeletionKeepingFailed(session)).thenReturn(List.of());

    var errors = service.purgeMarkedSessions();

    assertThat(errors).isEmpty();
    var order = inOrder(matrixSynapseService, deleteSessionService);
    order.verify(matrixSynapseService).purgeRoom("!room:matrix");
    order.verify(deleteSessionService).performSessionDeletionKeepingFailed(session);
  }

  @Test
  void purgeMarkedSessions_Should_keepTheSessionForTheNextRun_When_theRoomCannotBePurged() {
    var failing = session(1L, "!stuck:matrix");
    var fine = session(2L, null);
    when(sessionRepository.findAllByDeleteDateNotNull()).thenReturn(List.of(failing, fine));
    when(matrixSynapseService.purgeRoom("!stuck:matrix")).thenReturn(false);
    when(deleteSessionService.performSessionDeletionKeepingFailed(fine)).thenReturn(List.of());

    var errors = service.purgeMarkedSessions();

    assertThat(errors)
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getIdentifier()).isEqualTo("1");
              assertThat(error.getDeletionTargetType()).isEqualTo(DeletionTargetType.MATRIX);
            });
    verify(deleteSessionService, never()).performSessionDeletionKeepingFailed(failing);
    verify(deleteSessionService).performSessionDeletionKeepingFailed(fine);
  }

  @Test
  void purgeMarkedSessions_Should_carryOn_When_oneSessionThrows() {
    var broken = session(1L, null);
    var fine = session(2L, null);
    when(sessionRepository.findAllByDeleteDateNotNull()).thenReturn(List.of(broken, fine));
    when(deleteSessionService.performSessionDeletionKeepingFailed(broken))
        .thenThrow(new IllegalStateException("db"));
    when(deleteSessionService.performSessionDeletionKeepingFailed(fine)).thenReturn(List.of());

    var errors = service.purgeMarkedSessions();

    assertThat(errors).hasSize(1);
    verify(deleteSessionService).performSessionDeletionKeepingFailed(fine);
    verify(matrixSynapseService, never()).purgeRoom(any());
  }

  private static Session session(long id, String roomId) {
    var session = new Session();
    session.setId(id);
    session.setMatrixRoomId(roomId);
    return session;
  }
}
