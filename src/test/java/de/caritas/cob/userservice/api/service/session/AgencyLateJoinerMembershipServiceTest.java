package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.SessionRoomRow;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * US#1060 / FE#811. {@link AgencySilentMembershipService} closes the gap for enquiries that arrive
 * <em>after</em> a counsellor joined the agency; this service closes the mirror-image gap for
 * counsellors who join <em>after</em> the enquiry arrived. Both halves are needed — an enquiry room
 * nobody backfills stays invisible to the new counsellor forever, because Matrix {@code /sync}
 * returns nothing for a non-member and no later event repairs that.
 */
@ExtendWith(MockitoExtension.class)
class AgencyLateJoinerMembershipServiceTest {

  private static final Long AGENCY_ID = 4711L;
  private static final String AGENCY_MATRIX_USER_ID = "@agency4711:example.org";
  private static final String AGENCY_TOKEN = "agency-access-token";
  private static final String CONSULTANT_MATRIX_USER_ID = "@late:example.org";

  @Mock private SessionRepository sessionRepository;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private AgencySilentMembershipService agencySilentMembershipService;
  @Mock private TeamDiscussionRepository teamDiscussionRepository;

  @Mock
  private de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionParticipantWriter
      participantWriter;

  @Mock
  private de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository
      consultantAgencyRepository;

  @Mock private de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService claims;

  @Mock private MatrixRateLimitPause rateLimitPause;
  @Mock private ConsultantRepository consultants;
  private AgencyLateJoinerMembershipService underTest;

  @BeforeEach
  void buildService() {
    var access =
        new AgencyOpenEnquiryMembership(
            consultants,
            consultantAgencyRepository,
            sessionRepository,
            agencySilentMembershipService);
    underTest =
        new AgencyLateJoinerMembershipService(
            sessionRepository,
            matrixCredentialClient,
            sessionRoomGateway,
            access,
            rateLimitPause,
            teamDiscussionRepository,
            consultantAgencyRepository,
            participantWriter,
            claims);
  }

  private void entitled(Consultant consultant, Session... rooms) {
    when(consultants.findActiveByIdForUpdate(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultant.getId(), AGENCY_ID))
        .thenReturn(true);
    for (Session room : rooms) {
      when(sessionRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
    }
  }

  private void guardedTeamRoom() {
    var lease =
        new de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease(
            AgencyLateJoinerMembershipService.TEAM_ACCESS_REPAIR_TASK,
            java.time.LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(anyString(), any())).thenReturn(Optional.of(lease));
    when(claims.runIfHeld(eq(lease), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return true;
            });
  }

  @Test
  void recordedRevocationRemainsPendingWhenMatrixIdentityIsMissing() {
    var consultant = lateJoiner();
    consultant.setMatrixUserId(null);
    assertEquals(
        false, underTest.removeConsultantFromTeamRoom(consultant, AGENCY_ID, "!team:test"));
    verifyNoInteractions(sessionRoomGateway, matrixCredentialClient);
  }

  private Consultant lateJoiner() {
    var consultant = new Consultant();
    consultant.setId("c-late");
    consultant.setUsername("c-late");
    consultant.setMatrixUserId(CONSULTANT_MATRIX_USER_ID);
    return consultant;
  }

  private Session openEnquiry(Long id, String roomId) {
    var session = new Session();
    session.setId(id);
    session.setAgencyId(AGENCY_ID);
    session.setStatus(SessionStatus.NEW);
    session.setMatrixRoomId(roomId);
    return session;
  }

  private void agencyServiceAccountAvailable() {
    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId(AGENCY_MATRIX_USER_ID);
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(sessionRoomGateway.loginAsUser(AGENCY_MATRIX_USER_ID)).thenReturn(AGENCY_TOKEN);
  }

  private void openEnquiries(Session... sessions) {
    openEnquiriesThrough(
        Arrays.stream(sessions).mapToLong(Session::getId).max().orElse(1), sessions);
  }

  private void openEnquiriesThrough(long upperId, Session... sessions) {
    var rows =
        Arrays.stream(sessions)
            .map(session -> new SessionRoomRow(session.getId(), session.getMatrixRoomId()))
            .sorted(Comparator.comparing(SessionRoomRow::sessionId))
            .toList();
    when(sessionRepository.findAgencySessionUpperId(AGENCY_ID)).thenReturn(Optional.of(upperId));
    var page = PageRequest.ofSize(AgencyLateJoinerMembershipService.ENQUIRY_ROOM_PAGE_SIZE);
    when(sessionRepository.findLateJoinerEnquiryRooms(
            eq(AGENCY_ID),
            eq(AgencyOpenEnquiryMembership.OPEN_STATUSES),
            anyLong(),
            eq(upperId),
            eq(page)))
        .thenAnswer(
            invocation ->
                rows.stream()
                    .filter(row -> row.sessionId() > invocation.<Long>getArgument(2))
                    .limit(page.getPageSize())
                    .toList());
  }

  @Test
  @DisplayName("a counsellor added to the agency is joined into every open enquiry room")
  void joinConsultantIntoOpenEnquiryRooms_joinsEveryOpenEnquiryRoomOfTheAgency() {
    var consultant = lateJoiner();
    var one = openEnquiry(1L, "!one:example.org");
    var two = openEnquiry(2L, "!two:example.org");
    openEnquiries(one, two);
    entitled(consultant, one, two);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!one:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!two:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(2, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));
    verify(sessionRepository, never()).findByAgencyIdAndStatusAndConsultantIsNull(any(), any());

    verify(agencySilentMembershipService)
        .tryJoinConsultantIntoRoom(consultant, "!one:example.org", AGENCY_TOKEN);
    verify(agencySilentMembershipService)
        .tryJoinConsultantIntoRoom(consultant, "!two:example.org", AGENCY_TOKEN);
  }

  @Test
  void joinsUsableRoomsOnceAcrossPagesUsingOneAgencyToken() {
    var consultant = lateJoiner();
    int pageSize = AgencyLateJoinerMembershipService.ENQUIRY_ROOM_PAGE_SIZE;
    var rooms =
        IntStream.rangeClosed(1, pageSize + 1)
            .mapToObj(
                id -> openEnquiry((long) id, id == pageSize ? "\t" : "!room" + id + ":example.org"))
            .toArray(Session[]::new);
    var usableRooms =
        Arrays.stream(rooms).filter(room -> room.getId() != pageSize).toArray(Session[]::new);
    long upperId = rooms.length + 1L;
    openEnquiriesThrough(upperId, rooms);
    entitled(consultant, usableRooms);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            eq(consultant), anyString(), eq(AGENCY_TOKEN)))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(
        usableRooms.length, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    var reads = inOrder(sessionRepository);
    reads.verify(sessionRepository).findAgencySessionUpperId(AGENCY_ID);
    reads
        .verify(sessionRepository)
        .findLateJoinerEnquiryRooms(
            AGENCY_ID,
            AgencyOpenEnquiryMembership.OPEN_STATUSES,
            Long.MIN_VALUE,
            upperId,
            PageRequest.ofSize(pageSize));
    reads
        .verify(sessionRepository)
        .findLateJoinerEnquiryRooms(
            AGENCY_ID,
            AgencyOpenEnquiryMembership.OPEN_STATUSES,
            pageSize,
            upperId,
            PageRequest.ofSize(pageSize));
    verify(sessionRepository, times(2))
        .findLateJoinerEnquiryRooms(any(), any(), anyLong(), anyLong(), any());
    verify(matrixCredentialClient).fetchMatrixCredentials(AGENCY_ID);
    verify(sessionRoomGateway).loginAsUser(AGENCY_MATRIX_USER_ID);
    var roomIds = ArgumentCaptor.forClass(String.class);
    verify(agencySilentMembershipService, times(usableRooms.length))
        .tryJoinConsultantIntoRoom(eq(consultant), roomIds.capture(), eq(AGENCY_TOKEN));
    assertEquals(
        Arrays.stream(usableRooms).map(Session::getMatrixRoomId).toList(), roomIds.getAllValues());
  }

  @Test
  void aRoomChangedSinceTheSnapshotGetsNoDepartmentGrant() {
    var consultant = lateJoiner();
    var selected = openEnquiry(1L, "!original:example.org");
    var current = openEnquiry(1L, "!replacement:example.org");
    openEnquiries(selected);
    entitled(consultant, current);
    agencyServiceAccountAvailable();

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(sessionRepository).findByIdForUpdate(selected.getId());
    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  void detachedCounsellorIsNotJoinedFromAnOldEvent() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!one:example.org"));
    agencyServiceAccountAvailable();
    when(consultants.findActiveByIdForUpdate(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    lenient()
        .when(
            agencySilentMembershipService.tryJoinConsultantIntoRoom(
                consultant, "!one:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    var order = inOrder(consultants, consultantAgencyRepository);
    order.verify(consultants).findActiveByIdForUpdate(consultant.getId());
    order
        .verify(consultantAgencyRepository)
        .existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(consultant.getId(), AGENCY_ID);
    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  void aCaseAcceptedSinceTheSnapshotGetsNoDepartmentGrant() {
    var consultant = lateJoiner();
    var earlier = openEnquiry(1L, "!one:example.org");
    var accepted = openEnquiry(1L, "!one:example.org");
    accepted.setStatus(SessionStatus.IN_PROGRESS);
    openEnquiries(earlier);
    entitled(consultant, accepted);
    agencyServiceAccountAvailable();

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("an enquiry still awaiting its first message is backfilled as well")
  void joinConsultantIntoOpenEnquiryRooms_alsoCoversEnquiriesAwaitingTheirFirstMessage() {
    var consultant = lateJoiner();
    var awaitingFirstMessage = openEnquiry(3L, "!awaiting:example.org");
    awaitingFirstMessage.setStatus(SessionStatus.INITIAL);
    openEnquiries(awaitingFirstMessage);
    entitled(consultant, awaitingFirstMessage);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!awaiting:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(1, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(agencySilentMembershipService)
        .tryJoinConsultantIntoRoom(consultant, "!awaiting:example.org", AGENCY_TOKEN);
  }

  @Test
  @DisplayName("a directly addressed enquiry is never fanned out to a late joiner")
  void joinConsultantIntoOpenEnquiryRooms_skipsDirectlyAssignedEnquiries() {
    var consultant = lateJoiner();
    var directEnquiry = openEnquiry(1L, "!direct:example.org");
    directEnquiry.setIsConsultantDirectlySet(true);
    var department = openEnquiry(2L, "!department:example.org");
    openEnquiries(directEnquiry, department);
    entitled(consultant, directEnquiry, department);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!department:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(1, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(agencySilentMembershipService, never())
        .tryJoinConsultantIntoRoom(any(), eq("!direct:example.org"), anyString());
  }

  @Test
  @DisplayName("an enquiry without a Matrix room yet is skipped instead of failing the assignment")
  void joinConsultantIntoOpenEnquiryRooms_skipsEnquiriesWithoutRoom() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, null), openEnquiry(2L, "  "), openEnquiry(3L, "\t"));

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(matrixCredentialClient);
    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("no usable agency service account means no join, but never an exception")
  void joinConsultantIntoOpenEnquiryRooms_toleratesMissingAgencyServiceAccount() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!one:example.org"));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.empty());

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("one unjoinable room does not cost the late joiner the remaining rooms")
  void joinConsultantIntoOpenEnquiryRooms_isBestEffortPerRoom() {
    var consultant = lateJoiner();
    var broken = openEnquiry(1L, "!broken:example.org");
    var healthy = openEnquiry(2L, "!healthy:example.org");
    openEnquiries(broken, healthy);
    entitled(consultant, broken, healthy);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!broken:example.org", AGENCY_TOKEN))
        .thenThrow(new RuntimeException("synapse rejected this room"));
    when(agencySilentMembershipService.tryJoinConsultantIntoRoom(
            consultant, "!healthy:example.org", AGENCY_TOKEN))
        .thenReturn(JoinAttempt.JOINED);

    assertEquals(1, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));
  }

  @Test
  @DisplayName("missing consultant or agency short-circuits without touching Matrix")
  void joinConsultantIntoOpenEnquiryRooms_shortCircuitsOnMissingInput() {
    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(null, AGENCY_ID));
    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(lateJoiner(), null));

    verifyNoInteractions(sessionRepository);
    verifyNoInteractions(matrixCredentialClient);
    verifyNoInteractions(sessionRoomGateway);
    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("an agency without a single open enquiry room never reaches out to Matrix at all")
  void joinConsultantIntoOpenEnquiryRooms_neverTouchesMatrix_When_theAgencyHasNoOpenEnquiryRoom() {
    var consultant = lateJoiner();
    openEnquiries();

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(matrixCredentialClient, sessionRoomGateway, agencySilentMembershipService);
  }

  @Test
  @DisplayName("agency removal also revokes open team discussions the counsellor joined")
  void removeConsultantFromTeamRooms_removesJoinedTeamDiscussionRooms() {
    var consultant = lateJoiner();
    when(teamDiscussionRepository.findRoomIdsForParticipantInAgency(
            consultant.getId(), AGENCY_ID, TeamDiscussion.Status.OPEN))
        .thenReturn(List.of("!team:example.org"));
    agencyServiceAccountAvailable();
    guardedTeamRoom();
    var marked = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              marked.set(true);
              return null;
            })
        .when(participantWriter)
        .markAgencyRevocation(consultant.getId(), AGENCY_ID);
    when(sessionRoomGateway.removeUserFromRoom(
            "!team:example.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenAnswer(
            invocation -> {
              assertEquals(true, marked.get(), "Repair marker must commit before the kick");
              return true;
            });

    assertEquals(1, underTest.removeConsultantFromTeamRooms(consultant, AGENCY_ID));

    verify(sessionRoomGateway)
        .removeUserFromRoom("!team:example.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN);
  }

  @Test
  void removeConsultantFromTeamRooms_alsoRevokesArchivedTeamRooms() {
    guardedTeamRoom();
    var consultant = lateJoiner();
    when(teamDiscussionRepository.findRoomIdsForParticipantInAgency(
            consultant.getId(), AGENCY_ID, TeamDiscussion.Status.ARCHIVED))
        .thenReturn(List.of("!archive:example.org"));
    when(teamDiscussionRepository.findRoomIdsForParticipantInAgency(
            consultant.getId(), AGENCY_ID, TeamDiscussion.Status.OPEN))
        .thenReturn(List.of());
    agencyServiceAccountAvailable();
    when(sessionRoomGateway.removeUserFromRoom(
            "!archive:example.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(1, underTest.removeConsultantFromTeamRooms(consultant, AGENCY_ID));
  }

  @Test
  @DisplayName("a counsellor without a Matrix account has nothing to revoke")
  void removeConsultantFromTeamRooms_skipsConsultantWithoutMatrixAccount() {
    var consultant = lateJoiner();
    consultant.setMatrixUserId(null);

    assertEquals(0, underTest.removeConsultantFromTeamRooms(consultant, AGENCY_ID));

    verifyNoInteractions(sessionRepository);
    verifyNoInteractions(matrixCredentialClient);
    verifyNoInteractions(sessionRoomGateway);
  }
}
