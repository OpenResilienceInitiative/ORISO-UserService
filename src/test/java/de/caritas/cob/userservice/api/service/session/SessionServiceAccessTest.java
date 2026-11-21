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
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
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
  @Mock private SessionSupervisionMarkerService supervisionMarkerService;
  @Mock private SessionOwnershipService sessionOwnershipService;
  @Mock private MembersOnlyChatAccess membersOnlyChatAccess;

  @Mock
  private de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionPendingReadAccess
      rejectionReadAccess;

  @Mock
  private de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository
      directInvitationMembers;

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
            sessionSupervisorRepository,
            supervisionMarkerService,
            rejectionReadAccess,
            sessionOwnershipService,
            membersOnlyChatAccess,
            directInvitationMembers);

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

  @Test
  void isConsultantPermittedToSession_ShouldAllowRejectedRead_ForUnassignedConsultantWithGrant() {
    var consultant = consultant(UNASSIGNED_CONSULTANT_ID);
    var session = sessionOwnedBy(OWNER_ID);
    session.setStatus(Session.SessionStatus.REJECTED);
    when(rejectionReadAccess.canRead(session, consultant)).thenReturn(true);

    assertEquals(true, sessionService.isConsultantPermittedToSession(consultant, session));
  }

  @Test
  void isConsultantPermittedToSession_ShouldDenyRejectedRead_ForAssignedConsultantWithoutGrant() {
    var consultant = consultant(CONSULTANT_ID);
    var session = sessionOwnedBy(OWNER_ID);
    session.setStatus(Session.SessionStatus.REJECTED);
    session.setConsultant(consultant);
    when(rejectionReadAccess.canRead(session, consultant)).thenReturn(false);

    assertEquals(false, sessionService.isConsultantPermittedToSession(consultant, session));
  }

  @Test
  void
      assertUserHasAccess_ShouldThrowForbiddenException_WhenAssignedConsultantLeftTheMembersOnlyChat() {
    var former = consultant(CONSULTANT_ID);
    var session = sessionOwnedBy(OWNER_ID);
    session.setConsultant(former);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantService.getConsultant(CONSULTANT_ID)).thenReturn(Optional.of(former));
    org.mockito.Mockito.doThrow(new ForbiddenException("not a participant"))
        .when(membersOnlyChatAccess)
        .requireAccess(session, former);

    assertThrows(
        ForbiddenException.class,
        () -> sessionService.assertUserHasAccess(SESSION_ID, consultantUser(CONSULTANT_ID)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aRejectedTeamCaseUsesTheBoundedReadGrantInsteadOfAgencyMembership(boolean granted) {
    var consultant = consultant(UNASSIGNED_CONSULTANT_ID);
    consultant.setConsultantAgencies(
        Set.of(
            de.caritas.cob.userservice.api.model.ConsultantAgency.builder().agencyId(70L).build()));
    var session = sessionOwnedBy(OWNER_ID);
    session.setAgencyId(70L);
    session.setTeamSession(true);
    session.setStatus(Session.SessionStatus.REJECTED);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantService.getConsultant(UNASSIGNED_CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    lenient().when(rejectionReadAccess.canRead(session, consultant)).thenReturn(granted);
    if (granted) {
      assertEquals(
          session,
          sessionService.assertUserHasAccess(SESSION_ID, consultantUser(UNASSIGNED_CONSULTANT_ID)));
    } else {
      assertThrows(
          ForbiddenException.class,
          () ->
              sessionService.assertUserHasAccess(
                  SESSION_ID, consultantUser(UNASSIGNED_CONSULTANT_ID)));
    }
  }

  @Test
  void anOrdinaryTeamEnquiryRemainsReadableByItsAgencyColleague() {
    var consultant = consultant(UNASSIGNED_CONSULTANT_ID);
    consultant.setConsultantAgencies(
        Set.of(
            de.caritas.cob.userservice.api.model.ConsultantAgency.builder().agencyId(70L).build()));
    var session = sessionOwnedBy(OWNER_ID);
    session.setAgencyId(70L);
    session.setTeamSession(true);
    session.setStatus(Session.SessionStatus.NEW);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantService.getConsultant(UNASSIGNED_CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
    assertEquals(
        session,
        sessionService.assertUserHasAccess(SESSION_ID, consultantUser(UNASSIGNED_CONSULTANT_ID)));
  }

  // #1747: the second and every later person of an appointment invitation is a member, not the
  // owner. Before the fix the asker lookups refused them ("Asker … not allowed to access session").
  @Test
  void getSessionsByUserAndSessionIds_ShouldOpenSharedConversation_WhenAskerIsConfirmedMember() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(null, null)));

    var result =
        sessionService.getSessionsByUserAndSessionIds(
            OTHER_USER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue()));

    assertEquals(1, result.size());
    assertEquals(SESSION_ID, result.get(0).getSession().getId());
  }

  @Test
  void getSessionsByUserAndRoomIds_ShouldOpenSharedConversation_WhenAskerIsConfirmedMember() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findByMatrixRoomIdIn(Set.of("!room:matrix")))
        .thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(null, null)));

    var result =
        sessionService.getSessionsByUserAndRoomIds(
            OTHER_USER_ID, Set.of("!room:matrix"), Set.of(UserRole.USER.getValue()));

    assertEquals(1, result.size());
  }

  @Test
  void getSessionsByUserAndSessionIds_ShouldRefuse_WhenAskerIsNeitherOwnerNorMember() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of());

    assertThrows(
        ForbiddenException.class,
        () ->
            sessionService.getSessionsByUserAndSessionIds(
                OTHER_USER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue())));
  }

  @Test
  void getSessionsByUserAndSessionIds_ShouldRefuse_WhenMembershipIsBeingRemoved() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(java.time.LocalDateTime.now(), null)));

    assertThrows(
        ForbiddenException.class,
        () ->
            sessionService.getSessionsByUserAndSessionIds(
                OTHER_USER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue())));
  }

  @Test
  void getSessionsByUserAndSessionIds_ShouldRefuse_WhenMemberWasRemoved() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(null, java.time.LocalDateTime.now())));

    assertThrows(
        ForbiddenException.class,
        () ->
            sessionService.getSessionsByUserAndSessionIds(
                OTHER_USER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue())));
  }

  @Test
  void assertAskerHasAccess_ShouldStillRefuseEdits_WhenAskerIsOnlyMember() {
    var session = sessionOwnedBy(OWNER_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    lenient()
        .when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(null, null)));

    assertThrows(
        ForbiddenException.class,
        () -> sessionService.assertAskerHasAccess(SESSION_ID, user(OTHER_USER_ID)));
  }

  @Test
  void getInvitationMemberSessionsForUserId_ShouldListSharedConversationsButNotOwnedOnes() {
    var shared = sessionOwnedBy(OWNER_ID);
    var owned = sessionOwnedBy(OTHER_USER_ID);
    owned.setId(43L);
    when(directInvitationMembers.findMemberSessionIdsByUserId(OTHER_USER_ID))
        .thenReturn(java.util.List.of(SESSION_ID, 43L));
    when(sessionRepository.findAllById(Set.of(SESSION_ID, 43L)))
        .thenReturn(java.util.List.of(shared, owned));

    var result = sessionService.getInvitationMemberSessionsForUserId(OTHER_USER_ID);

    assertEquals(1, result.size());
    assertEquals(SESSION_ID, result.get(0).getSession().getId());
  }

  @Test
  void getInvitationMemberSessionsForUserId_ShouldBeEmpty_WhenAskerIsNoMember() {
    when(directInvitationMembers.findMemberSessionIdsByUserId(OTHER_USER_ID))
        .thenReturn(java.util.List.of());

    assertEquals(0, sessionService.getInvitationMemberSessionsForUserId(OTHER_USER_ID).size());
  }

  @Test
  void getSessionsByUserAndSessionIds_ShouldRefuse_WhenTheJoinIsStillPending() {
    var session = sessionOwnedBy(OWNER_ID);
    var pending = memberRow(null, null);
    pending.setJoinAttempt("attempt-1");
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(pending));

    assertThrows(
        ForbiddenException.class,
        () ->
            sessionService.getSessionsByUserAndSessionIds(
                OTHER_USER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue())));
  }

  // #1747 review: a later person must never receive the first person's identity or postcode.
  @Test
  void aMemberNeverReceivesTheOwnersIdentityOrPostcode() {
    var session = sessionOwnedBy(OWNER_ID);
    session.setPostcode("10965");
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));
    when(sessionRepository.findByMatrixRoomIdIn(Set.of("!room:matrix")))
        .thenReturn(java.util.List.of(session));
    when(directInvitationMembers.findByUserIdAndSessionIdIn(OTHER_USER_ID, Set.of(SESSION_ID)))
        .thenReturn(java.util.List.of(memberRow(null, null)));
    when(directInvitationMembers.findMemberSessionIdsByUserId(OTHER_USER_ID))
        .thenReturn(java.util.List.of(SESSION_ID));

    var askerRoles = Set.of(UserRole.USER.getValue());
    var views =
        java.util.List.of(
            sessionService
                .getSessionsByUserAndSessionIds(OTHER_USER_ID, Set.of(SESSION_ID), askerRoles)
                .get(0),
            sessionService
                .getSessionsByUserAndRoomIds(OTHER_USER_ID, Set.of("!room:matrix"), askerRoles)
                .get(0),
            sessionService.getInvitationMemberSessionsForUserId(OTHER_USER_ID).get(0));

    for (var view : views) {
      assertEquals(SESSION_ID, view.getSession().getId());
      org.junit.jupiter.api.Assertions.assertNull(view.getUser(), "no owner alias for a member");
      org.junit.jupiter.api.Assertions.assertNull(
          view.getSession().getPostcode(), "no owner postcode for a member");
    }
  }

  @Test
  void theOwnerStillReceivesTheirOwnIdentityAndPostcode() {
    var session = sessionOwnedBy(OWNER_ID);
    session.setPostcode("10965");
    when(sessionRepository.findAllById(Set.of(SESSION_ID))).thenReturn(java.util.List.of(session));

    var view =
        sessionService
            .getSessionsByUserAndSessionIds(
                OWNER_ID, Set.of(SESSION_ID), Set.of(UserRole.USER.getValue()))
            .get(0);

    assertEquals(OWNER_ID, view.getUser().getId());
    assertEquals("10965", view.getSession().getPostcode());
    org.mockito.Mockito.verifyNoInteractions(directInvitationMembers);
  }

  private de.caritas.cob.userservice.api.model.DirectInvitationMember memberRow(
      java.time.LocalDateTime revokeDueAt, java.time.LocalDateTime removedAt) {
    var row = new de.caritas.cob.userservice.api.model.DirectInvitationMember();
    row.setSessionId(SESSION_ID);
    row.setUserId(OTHER_USER_ID);
    row.setInviteLinkId(7L);
    row.setJoinedAt(java.time.LocalDateTime.now());
    row.setRevokeDueAt(revokeDueAt);
    row.setRemovedAt(removedAt);
    return row;
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
        .languageCode(com.neovisionaries.i18n.LanguageCode.de)
        .build();
  }

  private Consultant consultant(String consultantId) {
    return Consultant.builder()
        .id(consultantId)
        .matrixUserId("rc-" + consultantId)
        .username("consultant-" + consultantId)
        .firstName("first")
        .lastName("last")
        .email(consultantId + "@example.org")
        .build();
  }
}
