package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * US#1060 acceptance: a counsellor added to an agency <em>after</em> an enquiry already arrived
 * must end up a member of that enquiry's Matrix room.
 *
 * <p>US#905 joins the agency's counsellors into the room at room creation, which only ever helps
 * counsellors who were already in the agency at that moment. Matrix {@code /sync} hands a room to
 * members only, so for everybody who joined the agency later the open initial requests simply do
 * not exist — no error, no empty state, nothing to act on.
 *
 * <p>This test drives the two real admin entry points end to end rather than the fan-out service,
 * so it fails on a missing <em>wiring</em> just as loudly as on a missing service. The PUT path
 * ({@code setConsultantAgencies}) is the one the admin panel actually calls and is transactional;
 * the POST path ({@code createNewConsultantAgency}) is not, which is exactly why the membership
 * hook must survive both.
 *
 * <p>This class must not be {@code @Transactional}: the hook runs after the assignment is
 * committed, and a test-managed transaction that never commits would silently swallow it.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
class ConsultantAgencyLateJoinerMembershipIT {

  private static final Long AGENCY_ID = 91015L;
  private static final Long OTHER_AGENCY_ID = 91016L;
  private static final Long UNKNOWN_AGENCY_ID = 91099L;
  private static final String ENQUIRY_ROOM_ID = "!existing-enquiry:oriso.org";
  private static final String OTHER_AGENCY_ROOM_ID = "!other-agency-enquiry:oriso.org";
  private static final String CONSULTANT_MATRIX_USER_ID = "@late.joiner:oriso.org";
  private static final String AGENCY_TOKEN = "agency-service-account-token";
  private static final String CONSULTANT_TOKEN = "late-joiner-token";
  private static final String ROLE_SET_KEY = "valid-role-set";

  private final EasyRandom easyRandom = new EasyRandom();

  @Autowired private ConsultantAdminFacade consultantAdminFacade;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private UserAgencyRepository userAgencyRepository;

  @Autowired private SessionRepository sessionRepository;

  @MockitoBean private AgencyService agencyService;

  @MockitoBean private KeycloakService keycloakService;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @MockitoBean private AgencyMatrixCredentialClient matrixCredentialClient;

  @MockitoBean private SessionRoomGateway sessionRoomGateway;

  @BeforeEach
  void setUp() {
    givenAgencyExists(AGENCY_ID);
    givenAgencyExists(OTHER_AGENCY_ID);
    givenConsultingTypeSettings();
    givenAgencyServiceAccount();
    when(sessionRoomGateway.loginAsUser(CONSULTANT_MATRIX_USER_ID)).thenReturn(CONSULTANT_TOKEN);
    when(sessionRoomGateway.joinRoom(anyString(), eq(CONSULTANT_TOKEN))).thenReturn(true);
  }

  @Test
  @DisplayName("a counsellor added after the enquiry arrived is joined into its existing room")
  void addingAConsultantToAnAgencyWithAnExistingOpenEnquiry_joinsThemIntoThatRoom() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(AGENCY_ID, ENQUIRY_ROOM_ID);

    consultantAdminFacade.createNewConsultantAgency(consultant.getId(), agencyRelation(AGENCY_ID));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
  }

  @Test
  @DisplayName("the admin panel's agency editor joins the counsellor into the new agency's rooms")
  void replacingAConsultantsAgencies_joinsThemIntoTheNewAgencysOpenEnquiryRooms() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(AGENCY_ID, ENQUIRY_ROOM_ID);

    consultantAdminFacade.setConsultantAgencies(
        consultant.getId(), List.of(agencyRelation(AGENCY_ID)));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
  }

  @Test
  @DisplayName("the backfill stays inside the assigned agency and never leaks another one's rooms")
  void addingAConsultantToAnAgency_leavesOtherAgenciesEnquiryRoomsAlone() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(AGENCY_ID, ENQUIRY_ROOM_ID);
    givenOpenEnquiry(OTHER_AGENCY_ID, OTHER_AGENCY_ROOM_ID);

    consultantAdminFacade.createNewConsultantAgency(consultant.getId(), agencyRelation(AGENCY_ID));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
    verify(sessionRoomGateway, never()).joinRoom(eq(OTHER_AGENCY_ROOM_ID), anyString());
  }

  @Test
  @DisplayName("a rolled-back agency edit must not leave the counsellor inside the Matrix room")
  void replacingAgencies_joinsNoRoom_When_aLaterAssignmentInTheSameTransactionFails() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(AGENCY_ID, ENQUIRY_ROOM_ID);
    when(agencyService.getAgency(UNKNOWN_AGENCY_ID)).thenReturn(null);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAdminFacade.setConsultantAgencies(
                consultant.getId(),
                List.of(agencyRelation(AGENCY_ID), agencyRelation(UNKNOWN_AGENCY_ID))));

    verifyNoInteractions(sessionRoomGateway);
  }

  private CreateConsultantAgencyDTO agencyRelation(Long agencyId) {
    var relation = new CreateConsultantAgencyDTO();
    relation.setAgencyId(agencyId);
    relation.setRoleSetKey(ROLE_SET_KEY);
    return relation;
  }

  private void givenAgencyExists(Long agencyId) {
    var agency = new AgencyDTO();
    agency.setId(agencyId);
    agency.setTeamAgency(false);
    agency.setConsultingType(0);
    when(agencyService.getAgency(agencyId)).thenReturn(agency);
    when(agencyService.getAgenciesWithoutCaching(List.of(agencyId))).thenReturn(List.of(agency));
  }

  private void givenConsultingTypeSettings() {
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class));
  }

  private void givenAgencyServiceAccount() {
    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:oriso.org");
    credentials.setMatrixPassword("agency-password");
    when(matrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(credentials));
    when(sessionRoomGateway.loginUser("agency", "agency-password")).thenReturn(AGENCY_TOKEN);
  }

  private Consultant givenConsultantWithoutAgency() {
    var consultant = easyRandom.nextObject(Consultant.class);
    consultant.setConsultantAgencies(null);
    consultant.setSessions(null);
    consultant.setConsultantMobileTokens(null);
    consultant.setConsultantTopics(null);
    consultant.setTenantId(null);
    consultant.setMatrixUserId(CONSULTANT_MATRIX_USER_ID);
    consultant.setDeleteDate(null);
    consultant.setLanguages(null);
    consultant.setAppointments(null);
    return consultantRepository.save(consultant);
  }

  private void givenOpenEnquiry(Long agencyId, String matrixRoomId) {
    var user = easyRandom.nextObject(User.class);
    user.setSessions(null);
    user.setUserMobileTokens(null);
    user.setUserAgencies(null);
    userRepository.save(user);

    var userAgency = new UserAgency();
    userAgency.setAgencyId(agencyId);
    userAgency.setUser(user);
    userAgencyRepository.save(userAgency);

    var session = new Session();
    session.setStatus(SessionStatus.NEW);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setPostcode("12345");
    session.setConsultant(null);
    session.setUser(user);
    session.setAgencyId(agencyId);
    session.setMatrixRoomId(matrixRoomId);
    session.setLanguageCode(LanguageCode.de);
    session.setTeamSession(true);
    session.setSessionTopics(Lists.newArrayList());
    session.setIsConsultantDirectlySet(false);
    sessionRepository.save(session);
  }
}
