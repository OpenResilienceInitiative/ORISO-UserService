package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
  private static final String AGENCY_MATRIX_USER_ID = "@agency4711:oriso.org";
  private static final String AGENCY_MATRIX_PASSWORD = "agency-secret";
  private static final String AGENCY_TOKEN = "agency-access-token";
  private static final String CONSULTANT_MATRIX_USER_ID = "@late:oriso.org";

  @Mock private SessionRepository sessionRepository;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private AgencySilentMembershipService agencySilentMembershipService;

  @InjectMocks private AgencyLateJoinerMembershipService underTest;

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
    credentials.setMatrixPassword(AGENCY_MATRIX_PASSWORD);
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(sessionRoomGateway.loginUser("agency4711", AGENCY_MATRIX_PASSWORD))
        .thenReturn(AGENCY_TOKEN);
  }

  /** The service consults both open-enquiry queues, so every fixture has to answer for both. */
  private void openEnquiries(Session... sessions) {
    givenEnquiriesWithStatus(SessionStatus.NEW, sessions);
    givenEnquiriesWithStatus(SessionStatus.INITIAL);
  }

  private void enquiriesAwaitingTheirFirstMessage(Session... sessions) {
    givenEnquiriesWithStatus(SessionStatus.NEW);
    givenEnquiriesWithStatus(SessionStatus.INITIAL, sessions);
  }

  private void givenEnquiriesWithStatus(SessionStatus status, Session... sessions) {
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(AGENCY_ID, status))
        .thenReturn(List.of(sessions));
  }

  @Test
  @DisplayName("a counsellor added to the agency is joined into every open enquiry room")
  void joinConsultantIntoOpenEnquiryRooms_joinsEveryOpenEnquiryRoomOfTheAgency() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!one:oriso.org"), openEnquiry(2L, "!two:oriso.org"));
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!one:oriso.org", AGENCY_TOKEN))
        .thenReturn(true);
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!two:oriso.org", AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(2, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(agencySilentMembershipService)
        .joinConsultantIntoRoom(consultant, "!one:oriso.org", AGENCY_TOKEN);
    verify(agencySilentMembershipService)
        .joinConsultantIntoRoom(consultant, "!two:oriso.org", AGENCY_TOKEN);
  }

  @Test
  @DisplayName("an enquiry still awaiting its first message is backfilled as well")
  void joinConsultantIntoOpenEnquiryRooms_alsoCoversEnquiriesAwaitingTheirFirstMessage() {
    var consultant = lateJoiner();
    var awaitingFirstMessage = openEnquiry(3L, "!awaiting:oriso.org");
    awaitingFirstMessage.setStatus(SessionStatus.INITIAL);
    enquiriesAwaitingTheirFirstMessage(awaitingFirstMessage);
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!awaiting:oriso.org", AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(1, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(agencySilentMembershipService)
        .joinConsultantIntoRoom(consultant, "!awaiting:oriso.org", AGENCY_TOKEN);
  }

  @Test
  @DisplayName("a directly addressed enquiry is never fanned out to a late joiner")
  void joinConsultantIntoOpenEnquiryRooms_skipsDirectlyAssignedEnquiries() {
    var consultant = lateJoiner();
    var directEnquiry = openEnquiry(1L, "!direct:oriso.org");
    directEnquiry.setIsConsultantDirectlySet(true);
    openEnquiries(directEnquiry, openEnquiry(2L, "!department:oriso.org"));
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!department:oriso.org", AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(1, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verify(agencySilentMembershipService, never())
        .joinConsultantIntoRoom(any(), eq("!direct:oriso.org"), anyString());
  }

  @Test
  @DisplayName("an enquiry without a Matrix room yet is skipped instead of failing the assignment")
  void joinConsultantIntoOpenEnquiryRooms_skipsEnquiriesWithoutRoom() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, null), openEnquiry(2L, "  "));

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(matrixCredentialClient);
    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("no usable agency service account means no join, but never an exception")
  void joinConsultantIntoOpenEnquiryRooms_toleratesMissingAgencyServiceAccount() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!one:oriso.org"));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.empty());

    assertEquals(0, underTest.joinConsultantIntoOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(agencySilentMembershipService);
  }

  @Test
  @DisplayName("one unjoinable room does not cost the late joiner the remaining rooms")
  void joinConsultantIntoOpenEnquiryRooms_isBestEffortPerRoom() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!broken:oriso.org"), openEnquiry(2L, "!healthy:oriso.org"));
    agencyServiceAccountAvailable();
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!broken:oriso.org", AGENCY_TOKEN))
        .thenThrow(new RuntimeException("synapse rejected this room"));
    when(agencySilentMembershipService.joinConsultantIntoRoom(
            consultant, "!healthy:oriso.org", AGENCY_TOKEN))
        .thenReturn(true);

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
  @DisplayName("a counsellor removed from the agency loses membership in its open enquiry rooms")
  void removeConsultantFromOpenEnquiryRooms_removesFromEveryOpenEnquiryRoom() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!one:oriso.org"), openEnquiry(2L, "!two:oriso.org"));
    agencyServiceAccountAvailable();
    when(sessionRoomGateway.removeUserFromRoom(
            "!one:oriso.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenReturn(true);
    when(sessionRoomGateway.removeUserFromRoom(
            "!two:oriso.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(2, underTest.removeConsultantFromOpenEnquiryRooms(consultant, AGENCY_ID));
  }

  @Test
  @DisplayName("a counsellor without a Matrix account has nothing to revoke")
  void removeConsultantFromOpenEnquiryRooms_skipsConsultantWithoutMatrixAccount() {
    var consultant = lateJoiner();
    consultant.setMatrixUserId(null);

    assertEquals(0, underTest.removeConsultantFromOpenEnquiryRooms(consultant, AGENCY_ID));

    verifyNoInteractions(sessionRepository);
    verifyNoInteractions(matrixCredentialClient);
    verifyNoInteractions(sessionRoomGateway);
  }

  @Test
  @DisplayName("one failing removal does not stop the remaining rooms from being revoked")
  void removeConsultantFromOpenEnquiryRooms_isBestEffortPerRoom() {
    var consultant = lateJoiner();
    openEnquiries(openEnquiry(1L, "!broken:oriso.org"), openEnquiry(2L, "!healthy:oriso.org"));
    agencyServiceAccountAvailable();
    when(sessionRoomGateway.removeUserFromRoom(
            "!broken:oriso.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenThrow(new RuntimeException("synapse rejected this room"));
    when(sessionRoomGateway.removeUserFromRoom(
            "!healthy:oriso.org", CONSULTANT_MATRIX_USER_ID, AGENCY_TOKEN))
        .thenReturn(true);

    assertEquals(1, underTest.removeConsultantFromOpenEnquiryRooms(consultant, AGENCY_ID));
  }
}
