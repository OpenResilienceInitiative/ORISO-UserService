package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixCaseReplyActorAuthorizerTest {
  @Mock ConsultantRepository consultants;
  @Mock ConsultantAgencyRepository agencies;
  @Mock CaseHandoverRequestRepository handovers;
  @Mock SessionSupervisorRepository supervisors;
  @Mock MatrixSynapseService matrix;
  @InjectMocks MatrixCaseReplyActorAuthorizer authorizer;

  @Test
  void assignedConsultantMayWriteOnlyWhileCurrentPrimaryRoomContainsBothParties() {
    var session = session();
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@owner:matrix.example"))
        .thenReturn(Optional.of(session.getConsultant()));
    when(matrix.getRoomMembers("!primary:matrix.example"))
        .thenReturn(Optional.of(List.of("@owner:matrix.example", "@asker:matrix.example")))
        .thenReturn(Optional.of(List.of("@asker:matrix.example")));

    assertThat(authorizer.isCurrentWriter(session, "@owner:matrix.example", false)).isTrue();
    assertThat(authorizer.isCurrentWriter(session, "@owner:matrix.example", true)).isTrue();
    assertThat(authorizer.isCurrentWriter(session, "@owner:matrix.example", true)).isFalse();
    verifyNoInteractions(agencies, handovers, supervisors);
  }

  @Test
  void unknownCurrentRoomMembershipDefersInsteadOfAllowingMail() {
    var session = session();
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@owner:matrix.example"))
        .thenReturn(Optional.of(session.getConsultant()));
    when(matrix.getRoomMembers("!primary:matrix.example")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authorizer.isCurrentWriter(session, "@owner:matrix.example", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Matrix room membership is unavailable");
  }

  @Test
  void activeAgencyTeamMemberMayWriteButReadOnlyCoAccessCannot() {
    var session = session();
    session.setTeamSession(true);
    var team = consultant("team", "@team:matrix.example");
    team.setTeamConsultant(true);
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@team:matrix.example"))
        .thenReturn(Optional.of(team));
    when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull("team", 4711L))
        .thenReturn(true);
    when(handovers.findActiveGrantExcluding(eq(42L), eq("team"), eq(null), any(), any(), any()))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                CaseHandoverRequest.builder()
                    .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
                    .build()))
        .thenReturn(
            List.of(
                CaseHandoverRequest.builder().reasonCode("COUNSELLOR_ASKED_FOR_ADVICE").build()));

    assertThat(authorizer.isCurrentWriter(session, "@team:matrix.example", false)).isTrue();
    assertThat(authorizer.isCurrentWriter(session, "@team:matrix.example", false)).isFalse();
    assertThat(authorizer.isCurrentWriter(session, "@team:matrix.example", false)).isFalse();
  }

  @Test
  void supervisorOrNonTeamAgencyMemberCannotSendAnAskerReplyMail() {
    var session = session();
    session.setTeamSession(true);
    var team = consultant("team", "@team:matrix.example");
    team.setTeamConsultant(true);
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@team:matrix.example"))
        .thenReturn(Optional.of(team));
    when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull("team", 4711L))
        .thenReturn(true);
    when(supervisors.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(42L, "team"))
        .thenReturn(Optional.of(new SessionSupervisor()));

    assertThat(authorizer.isCurrentWriter(session, "@team:matrix.example", false)).isFalse();
    verifyNoInteractions(handovers, matrix);
  }

  @Test
  void controllerLegacyCompatibilityDoesNotBroadenThePublicMailAuthorizer() {
    var session = session();
    session.setTenantId(null);
    session.getConsultant().setTenantId(null);

    assertThat(authorizer.isCurrentAssignedOrTeamWriter(session, session.getConsultant())).isTrue();
    assertThat(authorizer.isCurrentWriter(session, "@owner:matrix.example", false)).isFalse();
    verifyNoInteractions(consultants, agencies, handovers, supervisors, matrix);
  }

  private static Session session() {
    return Session.builder()
        .id(42L)
        .tenantId(7L)
        .agencyId(4711L)
        .matrixRoomId("!primary:matrix.example")
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("10000")
        .status(Session.SessionStatus.IN_PROGRESS)
        .user(
            User.builder()
                .userId("asker")
                .username("asker")
                .email("asker@example.net")
                .matrixUserId("@asker:matrix.example")
                .build())
        .consultant(consultant("owner", "@owner:matrix.example"))
        .build();
  }

  private static Consultant consultant(String id, String matrixId) {
    return Consultant.builder()
        .id(id)
        .matrixUserId(matrixId)
        .tenantId(7L)
        .username(id)
        .firstName("Test")
        .lastName("Consultant")
        .email(id + "@example.net")
        .build();
  }
}
