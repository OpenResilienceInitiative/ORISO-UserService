package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomRow;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OldTraegerRoomFinderTest {

  private static final String MOVER = "mover";
  private static final String MATRIX_ID = "@mover:matrix";
  private static final long OLD_TENANT = 7L;

  @Mock private SessionRepository sessionRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @InjectMocks private OldTraegerRoomFinder finder;

  @Test
  void findsTheRoomsTheDatabaseKnowsAboutIncludingTheSupervisionSideRooms() {
    when(sessionRepository.findRoomsReadableBy(
            eq(MOVER),
            eq(List.of(700L)),
            eq(
                List.of(
                    CaseHandoverRequest.Status.GRANTED,
                    CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT))))
        .thenReturn(List.of(new SessionRoomRow(1L, "!owned")));
    when(sessionRepository.findSupervisionSideRoomsOf(MOVER))
        .thenReturn(List.of(new SessionRoomRow(2L, "!side")));
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(List.of()));

    assertThat(rooms())
        .containsExactlyInAnyOrder(new RoomRef(1L, "!owned"), new RoomRef(2L, "!side"));
  }

  /** The finding of #1421: a silent member of a colleague's accepted case is in no query. */
  @Test
  void addsTheRoomsOfTheOldTraegerTheMoverIsActuallyAMemberOf() {
    when(sessionRepository.findRoomsReadableBy(eq(MOVER), anyCollection(), anyCollection()))
        .thenReturn(List.of(new SessionRoomRow(1L, "!owned")));
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(List.of("!owned", "!colleague", "!side-old", "!not-a-case")));
    when(sessionRepository.findSessionRoomsOfTenantIn(
            OLD_TENANT, List.of("!owned", "!colleague", "!side-old", "!not-a-case")))
        .thenReturn(
            List.of(new SessionRoomRow(1L, "!owned"), new SessionRoomRow(3L, "!colleague")));
    when(sessionRepository.findSideRoomsOfTenantIn(
            OLD_TENANT, List.of("!owned", "!colleague", "!side-old", "!not-a-case")))
        .thenReturn(List.of(new SessionRoomRow(3L, "!side-old")));

    assertThat(rooms())
        .containsExactlyInAnyOrder(
            new RoomRef(1L, "!owned"), new RoomRef(3L, "!colleague"), new RoomRef(3L, "!side-old"));
    verify(sessionRepository, never()).findSessionRoomsOfAgencies(anyCollection());
  }

  @Test
  void asksInChunksSoALongRoomListNeverBecomesOneHugeQuery() {
    var joined = IntStream.range(0, 1200).mapToObj(i -> "!room" + i).toList();
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(joined));

    rooms();

    verify(sessionRepository, org.mockito.Mockito.times(3))
        .findSessionRoomsOfTenantIn(eq(OLD_TENANT), anyCollection());
    verify(sessionRepository, org.mockito.Mockito.times(3))
        .findSideRoomsOfTenantIn(eq(OLD_TENANT), anyCollection());
  }

  @Test
  void aMoverInNoRoomNeedsNoMembershipQuery() {
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(List.of()));

    assertThat(rooms()).isEmpty();
    verify(sessionRepository, never()).findSessionRoomsOfTenantIn(anyLong(), anyCollection());
  }

  /** Fail closed: leaving a room one never sat in is a no-op, staying in one is the leak. */
  @Test
  void fallsBackToEveryRoomOfTheOldCentresWhenTheChatServerCannotTell() {
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID)).thenReturn(Optional.empty());
    when(sessionRepository.findSessionRoomsOfAgencies(List.of(700L)))
        .thenReturn(List.of(new SessionRoomRow(3L, "!colleague")));
    when(sessionRepository.findSideRoomsOfAgencies(List.of(700L)))
        .thenReturn(List.of(new SessionRoomRow(3L, "!side")));

    assertThat(rooms())
        .containsExactlyInAnyOrder(new RoomRef(3L, "!colleague"), new RoomRef(3L, "!side"));
    verify(sessionRepository, never()).findSessionRoomsOfTenantIn(anyLong(), anyCollection());
  }

  @Test
  void aRoomFoundTwiceIsQueuedOnce() {
    when(sessionRepository.findRoomsReadableBy(eq(MOVER), anyCollection(), anyCollection()))
        .thenReturn(List.of(new SessionRoomRow(1L, "!owned")));
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(List.of("!owned")));
    when(sessionRepository.findSessionRoomsOfTenantIn(OLD_TENANT, List.of("!owned")))
        .thenReturn(List.of(new SessionRoomRow(1L, "!owned")));

    assertThat(rooms()).containsExactly(new RoomRef(1L, "!owned"));
  }

  @Test
  void aCounsellorWithoutCentreStillGetsAValidQuery() {
    when(matrixSynapseService.findJoinedRoomsForMatrixUser(MATRIX_ID))
        .thenReturn(Optional.of(List.of()));

    finder.roomsToLeave(MOVER, MATRIX_ID, OLD_TENANT, List.of());

    verify(sessionRepository).findRoomsReadableBy(eq(MOVER), eq(List.of(-1L)), anyCollection());
  }

  private List<RoomRef> rooms() {
    return new ArrayList<>(finder.roomsToLeave(MOVER, MATRIX_ID, OLD_TENANT, List.of(700L)));
  }
}
