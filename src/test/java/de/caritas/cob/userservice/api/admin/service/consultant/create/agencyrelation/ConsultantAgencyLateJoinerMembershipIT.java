package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
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
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.port.out.SessionRoomRow;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@WithTenant(ConsultantAgencyLateJoinerMembershipIT.TENANT)
class ConsultantAgencyLateJoinerMembershipIT {

  /** The Träger whose admin adds the counsellor; its enquiries are the only ones in reach. */
  static final long TENANT = 1L;

  private static final AtomicLong NEXT_FIXTURE_AGENCY_ID = new AtomicLong(91100L);
  private static final Long UNKNOWN_AGENCY_ID = 91099L;
  private static final String ENQUIRY_ROOM_ID = "!existing-enquiry:example.org";
  private static final String OTHER_AGENCY_ROOM_ID = "!other-agency-enquiry:example.org";
  private static final String CONSULTANT_MATRIX_USER_ID = "@late.joiner:example.org";
  private static final String AGENCY_TOKEN = "agency-service-account-token";
  private static final String CONSULTANT_TOKEN = "late-joiner-token";
  private static final String ROLE_SET_KEY = "valid-role-set";
  private static final LocalDateTime FIXTURE_DATE = LocalDateTime.of(2026, 1, 1, 12, 0);

  private final long agencyId = NEXT_FIXTURE_AGENCY_ID.getAndIncrement();
  private final long otherAgencyId = NEXT_FIXTURE_AGENCY_ID.getAndIncrement();
  private final EasyRandom easyRandom = new EasyRandom();

  @Autowired private ConsultantAdminFacade consultantAdminFacade;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private UserAgencyRepository userAgencyRepository;

  @Autowired private SessionRepository sessionRepository;

  @Autowired private AuthenticatedUser caller;

  @MockitoBean private AgencyService agencyService;

  @MockitoBean private KeycloakService keycloakService;

  @MockitoBean private ConsultingTypeManager consultingTypeManager;

  @MockitoBean private AgencyMatrixCredentialClient matrixCredentialClient;

  @MockitoBean private SessionRoomGateway sessionRoomGateway;

  @BeforeEach
  void setUp() {
    Tenants.actAs(caller, "tenant-admin", TENANT, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);
    givenAgencyExists(agencyId);
    givenAgencyExists(otherAgencyId);
    givenConsultingTypeSettings();
    givenAgencyServiceAccount();
    when(sessionRoomGateway.loginAsUser(CONSULTANT_MATRIX_USER_ID)).thenReturn(CONSULTANT_TOKEN);
    when(sessionRoomGateway.joinRoom(anyString(), eq(CONSULTANT_TOKEN))).thenReturn(true);
  }

  @Test
  @DisplayName("a counsellor added after the enquiry arrived is joined into its existing room")
  void addingAConsultantToAnAgencyWithAnExistingOpenEnquiry_joinsThemIntoThatRoom() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(agencyId, ENQUIRY_ROOM_ID);

    consultantAdminFacade.createNewConsultantAgency(consultant.getId(), agencyRelation(agencyId));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
  }

  @Test
  @DisplayName("the admin panel's agency editor joins the counsellor into the new agency's rooms")
  void replacingAConsultantsAgencies_joinsThemIntoTheNewAgencysOpenEnquiryRooms() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(agencyId, ENQUIRY_ROOM_ID);

    consultantAdminFacade.setConsultantAgencies(
        consultant.getId(), List.of(agencyRelation(agencyId)));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
  }

  @Test
  @DisplayName("the backfill stays inside the assigned agency and never leaks another one's rooms")
  void addingAConsultantToAnAgency_leavesOtherAgenciesEnquiryRoomsAlone() {
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(agencyId, ENQUIRY_ROOM_ID);
    givenOpenEnquiry(otherAgencyId, OTHER_AGENCY_ROOM_ID);

    consultantAdminFacade.createNewConsultantAgency(consultant.getId(), agencyRelation(agencyId));

    verify(sessionRoomGateway).joinRoom(ENQUIRY_ROOM_ID, CONSULTANT_TOKEN);
    verify(sessionRoomGateway, never()).joinRoom(eq(OTHER_AGENCY_ROOM_ID), anyString());
  }

  @Test
  void candidateQueryPagesOnlyEligibleRoomsUpToTheStartingId() {
    var first = givenOpenEnquiry(agencyId, "!new:example.org");
    givenEnquiry(agencyId, "!direct:example.org", SessionStatus.NEW, true, null);
    givenEnquiry(
        agencyId,
        "!assigned:example.org",
        SessionStatus.NEW,
        false,
        givenConsultantWithoutAgency());
    givenEnquiry(agencyId, "!closed:example.org", SessionStatus.DONE, false, null);
    givenOpenEnquiry(agencyId, null);
    givenOpenEnquiry(agencyId, "  ");
    givenOpenEnquiry(otherAgencyId, OTHER_AGENCY_ROOM_ID);
    Tenants.in(
        2L,
        () ->
            givenEnquiry(
                agencyId, "!other-tenant:example.org", SessionStatus.NEW, false, null, 2L));
    var second = givenEnquiry(agencyId, "!initial:example.org", SessionStatus.INITIAL, false, null);
    Tenants.in(
        2L,
        () ->
            givenEnquiry(
                agencyId, "!other-tenant-later:example.org", SessionStatus.NEW, false, null, 2L));
    long upperId = sessionRepository.findAgencySessionUpperId(agencyId).orElseThrow();
    assertThat(upperId).isEqualTo(second.getId());
    givenOpenEnquiry(agencyId, "!after-sweep-began:example.org");

    var statuses = List.of(SessionStatus.NEW, SessionStatus.INITIAL);
    var page = PageRequest.ofSize(1);
    assertThat(
            sessionRepository.findLateJoinerEnquiryRooms(
                agencyId, statuses, Long.MIN_VALUE, upperId, page))
        .containsExactly(new SessionRoomRow(first.getId(), first.getMatrixRoomId()));
    assertThat(
            sessionRepository.findLateJoinerEnquiryRooms(
                agencyId, statuses, first.getId(), upperId, page))
        .containsExactly(new SessionRoomRow(second.getId(), second.getMatrixRoomId()));
    assertThat(
            sessionRepository.findLateJoinerEnquiryRooms(
                agencyId, statuses, second.getId(), upperId, page))
        .isEmpty();
  }

  @Test
  @DisplayName("a rolled-back agency edit must not leave the counsellor inside the Matrix room")
  void replacingAgencies_joinsNoRoom_When_aLaterAssignmentInTheSameTransactionFails() {
    // Only the platform reaches an agency AgencyService does not know; a Träger admin is refused.
    Tenants.actAs(
        caller,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
    var consultant = givenConsultantWithoutAgency();
    givenOpenEnquiry(agencyId, ENQUIRY_ROOM_ID);
    when(agencyService.getAgency(UNKNOWN_AGENCY_ID)).thenReturn(null);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAdminFacade.setConsultantAgencies(
                consultant.getId(),
                List.of(agencyRelation(agencyId), agencyRelation(UNKNOWN_AGENCY_ID))));

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
    agency.setTenantId(TENANT);
    agency.setTeamAgency(false);
    agency.setConsultingType(0);
    when(agencyService.getAgency(agencyId)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(agencyId)).thenReturn(agency);
    when(agencyService.getAgenciesWithoutCaching(List.of(agencyId))).thenReturn(List.of(agency));
  }

  private void givenConsultingTypeSettings() {
    when(consultingTypeManager.getConsultingTypeSettings(0))
        .thenReturn(easyRandom.nextObject(ExtendedConsultingTypeResponseDTO.class));
  }

  private void givenAgencyServiceAccount() {
    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:example.org");
    when(matrixCredentialClient.fetchMatrixCredentials(any())).thenReturn(Optional.of(credentials));
    when(sessionRoomGateway.loginAsUser("@agency:example.org")).thenReturn(AGENCY_TOKEN);
  }

  private Consultant givenConsultantWithoutAgency() {
    var consultant = new Consultant();
    consultant.setId(UUID.randomUUID().toString());
    consultant.setUsername("late-joiner-" + consultant.getId());
    consultant.setFirstName("Synthetic");
    consultant.setLastName("Counsellor");
    consultant.setEmail(consultant.getId() + "@example.org");
    consultant.setTenantId(TENANT);
    consultant.setMatrixUserId(CONSULTANT_MATRIX_USER_ID);
    consultant.setEncourage2fa(true);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setNotifyEnquiriesRepeating(false);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(false);
    consultant.setCreateDate(FIXTURE_DATE);
    consultant.setUpdateDate(FIXTURE_DATE);
    consultant.setStatus(ConsultantStatus.CREATED);
    consultant.setLanguageCode(LanguageCode.de);
    return consultantRepository.save(consultant);
  }

  private Session givenOpenEnquiry(Long agencyId, String matrixRoomId) {
    return givenEnquiry(agencyId, matrixRoomId, SessionStatus.NEW, false, null);
  }

  private Session givenEnquiry(
      Long agencyId,
      String matrixRoomId,
      SessionStatus status,
      boolean directlyAddressed,
      Consultant consultant) {
    return givenEnquiry(agencyId, matrixRoomId, status, directlyAddressed, consultant, TENANT);
  }

  private Session givenEnquiry(
      Long agencyId,
      String matrixRoomId,
      SessionStatus status,
      boolean directlyAddressed,
      Consultant consultant,
      long tenantId) {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername("asker-" + user.getUserId());
    user.setEmail(user.getUserId() + "@example.org");
    user.setTenantId(tenantId);
    user.setLanguageCode(LanguageCode.de);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    userRepository.save(user);

    var userAgency = new UserAgency();
    userAgency.setAgencyId(agencyId);
    userAgency.setUser(user);
    userAgencyRepository.save(userAgency);

    var session = new Session();
    session.setCreateDate(FIXTURE_DATE);
    session.setUpdateDate(FIXTURE_DATE);
    session.setStatus(status);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setPostcode("12345");
    session.setConsultant(consultant);
    session.setUser(user);
    session.setAgencyId(agencyId);
    session.setTenantId(tenantId);
    session.setMatrixRoomId(matrixRoomId);
    session.setLanguageCode(LanguageCode.de);
    session.setTeamSession(true);
    session.setSessionTopics(Lists.newArrayList());
    session.setIsConsultantDirectlySet(directlyAddressed);
    return sessionRepository.save(session);
  }
}
