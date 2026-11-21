package de.caritas.cob.userservice.api.service.sessionappointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.calendar.CounsellorFreeTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * #1747: the second and every later person of an appointment invitation reads the appointment of
 * the shared conversation, although the first person owns the session.
 */
@ExtendWith(MockitoExtension.class)
class SessionAppointmentServiceMemberReadTest {

  private static final Long SESSION_ID = 232L;
  private static final String OWNER_ID = "first-person";
  private static final String MEMBER_ID = "second-person";

  @Mock private SessionAppointmentRepository appointments;
  @Mock private SessionRepository sessions;
  @Mock private AuthenticatedUser caller;
  @Mock private CaseHandoverService caseHandovers;
  @Mock private CounsellorFreeTime freeTime;
  @Mock private ConsultantRepository consultants;
  @Mock private DirectInvitationMemberRepository invitationMembers;
  @InjectMocks private SessionAppointmentService service;

  @BeforeEach
  void setUp() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setUser(new User(OWNER_ID, null, "owner", "owner@example.org", false));
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
    lenient().when(caller.getUserId()).thenReturn(MEMBER_ID);
    lenient().when(caller.isConsultant()).thenReturn(false);
    // A mocked Long getter answers 0; the caller here has no tenant, as in a single-tenant setup.
    lenient().when(caller.getTenantId()).thenReturn(null);
    lenient().when(appointments.findNewestOfSession(eq(SESSION_ID), any())).thenReturn(List.of());
  }

  @Test
  void list_ShouldAnswer_WhenCallerIsConfirmedMemberOfTheSharedConversation() {
    when(invitationMembers.findBySessionIdAndUserId(SESSION_ID, MEMBER_ID))
        .thenReturn(Optional.of(member(null)));

    assertThat(service.list(SESSION_ID)).isEmpty();
  }

  @Test
  void list_ShouldStayHidden_WhenMembershipIsBeingRemoved() {
    when(invitationMembers.findBySessionIdAndUserId(SESSION_ID, MEMBER_ID))
        .thenReturn(Optional.of(member(LocalDateTime.now())));

    assertThatThrownBy(() -> service.list(SESSION_ID)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void list_ShouldStayHidden_WhenCallerIsNoMember() {
    when(invitationMembers.findBySessionIdAndUserId(SESSION_ID, MEMBER_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.list(SESSION_ID)).isInstanceOf(NotFoundException.class);
  }

  private static DirectInvitationMember member(LocalDateTime revokeDueAt) {
    var row = new DirectInvitationMember();
    row.setSessionId(SESSION_ID);
    row.setUserId(MEMBER_ID);
    row.setInviteLinkId(7L);
    row.setJoinedAt(LocalDateTime.now());
    row.setRevokeDueAt(revokeDueAt);
    return row;
  }
}
