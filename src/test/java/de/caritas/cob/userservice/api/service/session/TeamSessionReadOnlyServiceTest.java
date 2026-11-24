package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamSessionReadOnlyServiceTest {

  private static final String ROOM = "!case:matrix.example";
  private static final String OWNER = "@owner:matrix.example";
  private static final String ASKER = "@asker:matrix.example";
  private static final String COLLEAGUE = "@colleague:matrix.example";
  private static final String FORMER_OWNER = "@former:matrix.example";
  private static final String SUPERVISOR = "@supervisor:matrix.example";
  private static final String TECHNICAL = "@agency:matrix.example";

  @Mock MatrixSynapseService matrix;
  @Mock ConsultantRepository consultants;
  @Mock SessionSupervisorRepository supervisors;
  @Mock SessionRepository sessions;
  @InjectMocks TeamSessionReadOnlyService service;

  @Test
  void aSessionOutsideTeamCounsellingIsLeftAsItIs() {
    var session = session();
    session.setTeamSession(false);

    assertThat(service.enforce(session)).isTrue();
    verifyNoInteractions(matrix);
  }

  @Test
  void lowersEveryColleagueButNeverTheOwnerTheAskerASupervisorOrATechnicalAccount() {
    var session = session();
    when(matrix.getRoomMembers(ROOM))
        .thenReturn(
            Optional.of(List.of(OWNER, ASKER, COLLEAGUE, FORMER_OWNER, SUPERVISOR, TECHNICAL)));
    var supervision = new SessionSupervisor();
    supervision.setSupervisorConsultant(consultant(SUPERVISOR));
    when(supervisors.findBySessionIdAndIsActiveTrue(42L)).thenReturn(List.of(supervision));
    when(consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(
            List.of(COLLEAGUE, FORMER_OWNER, TECHNICAL)))
        .thenReturn(List.of(consultant(COLLEAGUE), consultant(FORMER_OWNER)));
    when(matrix.loginAsUserAccessToken(OWNER)).thenReturn("owner-token");
    when(matrix.loginAsUserAccessToken(FORMER_OWNER)).thenReturn("former-token");
    when(matrix.getPowerLevels(ROOM, "owner-token"))
        .thenReturn(
            Optional.of(
                Map.of("users_default", 0, "users", Map.of(OWNER, 100, FORMER_OWNER, 100))));
    when(matrix.makeUserReadOnly(
            eq(ROOM), anyString(), eq(-1), eq(OWNER), eq("owner-token"), any()))
        .thenReturn(Optional.of(0));

    assertThat(service.enforce(session)).isTrue();
    // A colleague below the owner is lowered with the owner's token alone.
    verify(matrix).makeUserReadOnly(ROOM, COLLEAGUE, -1, OWNER, "owner-token", null);
    // An equal former owner may only lower itself, so its own token is supplied.
    verify(matrix).makeUserReadOnly(ROOM, FORMER_OWNER, -1, OWNER, "owner-token", "former-token");
    verify(matrix, never()).makeUserReadOnly(eq(ROOM), eq(OWNER), eq(-1), any(), any(), any());
    verify(matrix, never()).makeUserReadOnly(eq(ROOM), eq(ASKER), eq(-1), any(), any(), any());
    verify(matrix, never()).makeUserReadOnly(eq(ROOM), eq(SUPERVISOR), eq(-1), any(), any(), any());
    verify(matrix, never()).makeUserReadOnly(eq(ROOM), eq(TECHNICAL), eq(-1), any(), any(), any());
  }

  @Test
  void aColleagueWhoIsAlreadyAReaderIsNotWrittenAgain() {
    var session = session();
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of(OWNER, ASKER, COLLEAGUE)));
    when(consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(List.of(COLLEAGUE)))
        .thenReturn(List.of(consultant(COLLEAGUE)));
    when(matrix.loginAsUserAccessToken(OWNER)).thenReturn("owner-token");
    when(matrix.getPowerLevels(ROOM, "owner-token"))
        .thenReturn(Optional.of(Map.of("users", Map.of(OWNER, 100, COLLEAGUE, -1))));

    assertThat(service.enforce(session)).isTrue();
    verify(matrix, never()).makeUserReadOnly(any(), any(), eq(-1), any(), any(), any());
  }

  @Test
  void unreadableMembersOrARefusedWriteStaysPendingForTheRepairJob() {
    var session = session();
    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.empty());
    assertThat(service.enforce(session)).isFalse();

    when(matrix.getRoomMembers(ROOM)).thenReturn(Optional.of(List.of(OWNER, COLLEAGUE)));
    when(consultants.findAllByMatrixUserIdInAndDeleteDateIsNull(List.of(COLLEAGUE)))
        .thenReturn(List.of(consultant(COLLEAGUE)));
    when(matrix.loginAsUserAccessToken(OWNER)).thenReturn("owner-token");
    when(matrix.getPowerLevels(ROOM, "owner-token")).thenReturn(Optional.of(Map.of()));
    when(matrix.makeUserReadOnly(ROOM, COLLEAGUE, -1, OWNER, "owner-token", null))
        .thenReturn(Optional.empty());
    assertThat(service.enforce(session)).isFalse();
  }

  @Test
  void enforceSafelyNeverThrows() {
    when(sessions.findById(42L)).thenThrow(new IllegalStateException("database down"));

    service.enforceSafely(42L);
    verify(matrix, never()).getRoomMembers(isNull());
  }

  private static Session session() {
    var asker = new User();
    asker.setMatrixUserId(ASKER);
    var session = new Session();
    session.setId(42L);
    session.setTeamSession(true);
    session.setMatrixRoomId(ROOM);
    session.setUser(asker);
    session.setConsultant(consultant(OWNER));
    return session;
  }

  private static Consultant consultant(String matrixId) {
    var consultant = new Consultant();
    consultant.setId(matrixId.substring(1, matrixId.indexOf(':')));
    consultant.setMatrixUserId(matrixId);
    return consultant;
  }
}
