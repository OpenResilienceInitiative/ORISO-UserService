package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FE#811 / ADR-002 §1. A counsellor can only read an enquiry their own Matrix client syncs, and
 * {@code /sync} is empty for a non-member — so "the department are real members from room creation"
 * is the whole fix. These tests pin the membership contract: everyone joins, one broken counsellor
 * never costs the others their membership, and the advice seeker's enquiry never fails because of
 * it.
 */
@ExtendWith(MockitoExtension.class)
class AgencySilentMembershipServiceTest {

  private static final Long AGENCY_ID = 4711L;
  private static final String ROOM_ID = "!holding:oriso.org";
  private static final String AGENCY_TOKEN = "agency-access-token";

  @Mock private ConsultantRepository consultantRepository;
  @Mock private SessionRoomGateway sessionRoomGateway;
  @Mock private UserHelper userHelper;
  @Mock private UsernameTranscoder usernameTranscoder;

  @InjectMocks private AgencySilentMembershipService underTest;

  private Consultant consultantWithAccount(String id, String matrixUserId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setUsername(id);
    consultant.setFirstName("First");
    consultant.setLastName("Last");
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }

  @BeforeEach
  void setUp() {
    lenient().when(userHelper.getRandomPassword()).thenReturn("generated-password");
  }

  @Test
  @DisplayName("every active consultant of the agency is invited and joined into the room")
  void joinAgencyConsultants_joinsEveryConsultantOfTheAgency() throws Exception {
    var first = consultantWithAccount("c-1", "@c1:oriso.org");
    var second = consultantWithAccount("c-2", "@c2:oriso.org");
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(first, second));
    when(sessionRoomGateway.loginAsUser("@c1:oriso.org")).thenReturn("token-1");
    when(sessionRoomGateway.loginAsUser("@c2:oriso.org")).thenReturn("token-2");
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-1")).thenReturn(true);
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-2")).thenReturn(true);

    var joined = underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN);

    assertEquals(2, joined);
    verify(sessionRoomGateway).inviteUser(ROOM_ID, "@c1:oriso.org", AGENCY_TOKEN);
    verify(sessionRoomGateway).inviteUser(ROOM_ID, "@c2:oriso.org", AGENCY_TOKEN);
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "token-1");
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "token-2");
  }

  @Test
  @DisplayName("a consultant without a Matrix account gets one provisioned and persisted")
  void joinAgencyConsultants_provisionsMissingMatrixAccount() throws Exception {
    var fresh = consultantWithAccount("c-new", null);
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(fresh));
    when(usernameTranscoder.decodeUsername("c-new")).thenReturn("c-new");
    when(sessionRoomGateway.createUser("c-new", "generated-password", "First Last"))
        .thenReturn("@c-new:oriso.org");
    when(sessionRoomGateway.loginAsUser("@c-new:oriso.org")).thenReturn("token-new");
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-new")).thenReturn(true);

    var joined = underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN);

    assertEquals(1, joined);
    assertEquals("@c-new:oriso.org", fresh.getMatrixUserId());
    verify(consultantRepository).save(fresh);
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "token-new");
  }

  @Test
  @DisplayName("an already-registered Matrix account is resolved via login probe, not duplicated")
  void joinAgencyConsultants_resolvesExistingMatrixAccountAfterCreateRejection() throws Exception {
    var fresh = consultantWithAccount("c-known", null);
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(fresh));
    when(usernameTranscoder.decodeUsername("c-known")).thenReturn("c-known");
    when(sessionRoomGateway.createUser(eq("c-known"), anyString(), anyString()))
        .thenThrow(new MatrixCreateUserException("already exists"));
    when(sessionRoomGateway.userIdFor("c-known")).thenReturn("@c-known:oriso.org");
    when(sessionRoomGateway.loginAsUser("@c-known:oriso.org")).thenReturn("token-known");
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-known")).thenReturn(true);

    var joined = underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN);

    assertEquals(1, joined);
    assertEquals("@c-known:oriso.org", fresh.getMatrixUserId());
  }

  @Test
  @DisplayName("an invite rejection (already invited) does not stop the join")
  void joinAgencyConsultants_ignoresInviteRejection() throws Exception {
    var consultant = consultantWithAccount("c-1", "@c1:oriso.org");
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(consultant));
    doThrow(new MatrixInviteUserException("already in room"))
        .when(sessionRoomGateway)
        .inviteUser(ROOM_ID, "@c1:oriso.org", AGENCY_TOKEN);
    when(sessionRoomGateway.loginAsUser("@c1:oriso.org")).thenReturn("token-1");
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-1")).thenReturn(true);

    assertEquals(1, underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN));
  }

  @Test
  @DisplayName("one unusable consultant does not cost the remaining consultants their membership")
  void joinAgencyConsultants_isBestEffortPerConsultant() throws Exception {
    var broken = consultantWithAccount("c-broken", "@broken:oriso.org");
    var healthy = consultantWithAccount("c-ok", "@ok:oriso.org");
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(broken, healthy));
    when(sessionRoomGateway.loginAsUser("@broken:oriso.org"))
        .thenThrow(new RuntimeException("synapse down for this user"));
    when(sessionRoomGateway.loginAsUser("@ok:oriso.org")).thenReturn("token-ok");
    when(sessionRoomGateway.joinRoom(ROOM_ID, "token-ok")).thenReturn(true);

    assertEquals(1, underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN));
    verify(sessionRoomGateway).joinRoom(ROOM_ID, "token-ok");
  }

  @Test
  @DisplayName("a consultant whose Matrix account cannot be provisioned is skipped, not joined")
  void joinAgencyConsultants_skipsConsultantWithoutResolvableAccount() throws Exception {
    var fresh = consultantWithAccount("c-new", null);
    when(consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(AGENCY_ID))
        .thenReturn(List.of(fresh));
    when(usernameTranscoder.decodeUsername("c-new")).thenReturn("c-new");
    when(sessionRoomGateway.createUser(eq("c-new"), anyString(), anyString())).thenReturn(null);

    assertEquals(0, underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, AGENCY_TOKEN));
    verify(consultantRepository, never()).save(any());
    verify(sessionRoomGateway, never()).joinRoom(anyString(), anyString());
  }

  @Test
  @DisplayName("missing agency, room or agency token short-circuits without touching Matrix")
  void joinAgencyConsultants_shortCircuitsOnMissingInput() {
    assertEquals(0, underTest.joinAgencyConsultants(null, ROOM_ID, AGENCY_TOKEN));
    assertEquals(0, underTest.joinAgencyConsultants(AGENCY_ID, "", AGENCY_TOKEN));
    assertEquals(0, underTest.joinAgencyConsultants(AGENCY_ID, ROOM_ID, ""));

    verifyNoInteractions(sessionRoomGateway);
    verifyNoInteractions(consultantRepository);
  }
}
