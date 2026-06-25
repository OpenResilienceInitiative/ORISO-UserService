package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionServiceAccessTest {

  private static final Long SESSION_ID = 42L;
  private static final String OWNER_ID = "owner-id";
  private static final String OTHER_USER_ID = "other-user-id";
  private static final String CONSULTANT_ID = "consultant-id";
  private static final String UNASSIGNED_CONSULTANT_ID = "unassigned-consultant-id";

  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantTopicRepository consultantTopicRepository;
  @Mock private GroupChatParticipantRepository groupChatParticipantRepository;
  @Mock private AgencyService agencyService;
  @Mock private ConsultantService consultantService;
  @Mock private UserService userService;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private ConsultantSessionTopicEnrichmentService sessionTopicEnrichmentService;
  @Mock private SessionSupervisorRepository sessionSupervisorRepository;

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionService =
        new SessionService(
            sessionRepository,
            consultantTopicRepository,
            groupChatParticipantRepository,
            agencyService,
            consultantService,
            userService,
            consultingTypeManager,
            sessionTopicEnrichmentService,
            sessionSupervisorRepository);

    lenient()
        .when(
            sessionSupervisorRepository.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
                any(), anyString()))
        .thenReturn(Optional.empty());
  }

  @Test
  void assertUserHasAccess_ShouldReturnSession_WhenUserOwnsSession() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

    var result = sessionService.assertUserHasAccess(SESSION_ID, user(OWNER_ID));

    assertEquals(session, result);
  }

  @Test
  void assertUserHasAccess_ShouldThrowForbiddenException_WhenUserDoesNotOwnSession() {
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOwnedBy(OWNER_ID)));

    assertThrows(
        ForbiddenException.class,
        () -> sessionService.assertUserHasAccess(SESSION_ID, user(OTHER_USER_ID)));
  }

  @Test
  void assertUserHasAccess_ShouldReturnSession_WhenConsultantIsAssignedToSession() {
    var consultant = consultant(CONSULTANT_ID);
    var session = sessionOwnedBy(OWNER_ID);
    session.setConsultant(consultant);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantService.getConsultant(CONSULTANT_ID)).thenReturn(Optional.of(consultant));

    var result = sessionService.assertUserHasAccess(SESSION_ID, consultantUser(CONSULTANT_ID));

    assertEquals(session, result);
  }

  @Test
  void assertUserHasAccess_ShouldThrowForbiddenException_WhenConsultantIsNotAssigned() {
    var session = sessionOwnedBy(OWNER_ID);
    session.setConsultant(consultant(CONSULTANT_ID));
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantService.getConsultant(UNASSIGNED_CONSULTANT_ID))
        .thenReturn(Optional.of(consultant(UNASSIGNED_CONSULTANT_ID)));

    assertThrows(
        ForbiddenException.class,
        () ->
            sessionService.assertUserHasAccess(
                SESSION_ID, consultantUser(UNASSIGNED_CONSULTANT_ID)));
  }

  private AuthenticatedUser user(String userId) {
    return authenticatedUser(userId, Set.of(UserRole.USER.getValue()));
  }

  private AuthenticatedUser consultantUser(String userId) {
    return authenticatedUser(userId, Set.of(UserRole.CONSULTANT.getValue()));
  }

  private AuthenticatedUser authenticatedUser(String userId, Set<String> roles) {
    return new AuthenticatedUser(userId, "username", roles, "token", null);
  }

  private Session sessionOwnedBy(String ownerId) {
    return Session.builder()
        .id(SESSION_ID)
        .user(new User(ownerId, null, "owner", "owner@example.org", false))
        .consultingTypeId(0)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("12345")
        .status(Session.SessionStatus.IN_PROGRESS)
        .matrixRoomId("!room:matrix")
        .teamSession(false)
        .build();
  }

  private Consultant consultant(String consultantId) {
    return Consultant.builder()
        .id(consultantId)
        .rocketChatId("rc-" + consultantId)
        .username("consultant-" + consultantId)
        .firstName("first")
        .lastName("last")
        .email(consultantId + "@example.org")
        .build();
  }
}
