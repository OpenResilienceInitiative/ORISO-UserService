package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MessageClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountManagerTest {

  @InjectMocks AccountManager accountManager;

  @Mock ConsultantRepository consultantRepository;

  @Mock de.caritas.cob.userservice.api.port.out.AdminRepository adminRepository;

  @Mock ConsultantAgencyRepository consultantAgencyRepository;

  @Mock UserServiceMapper userServiceMapper;

  @Mock AgencyService agencyService;

  @Mock TenantService tenantService;

  @Mock Page<Consultant.ConsultantBase> page;

  // AccountManager#resolveEffectiveTenantId falls back to the thread-bound TenantContext when the
  // access token carries no tenant (the mocked AuthenticatedUser returns a null token here). These
  // tests stub the repository for the no-tenant case (effectiveTenantId == null), so any tenant
  // leaked by an earlier test in the suite would make the stubbed call mismatch under strict
  // stubbing. Clear the context around every test to keep them hermetic regardless of run order.
  @BeforeEach
  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void findConsultantsByInfix_Should_NotFilterByAgenciesIfAgencyListIsEmpty() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", false, Lists.newArrayList(), 1, 10, "email", true);

    // then
    Mockito.verify(consultantRepository)
        .findAllByInfix(Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class));
  }

  @Test
  void findConsultantsByInfix_Should_NotFail_When_AgencyServiceRespondsWithClientError() {
    // given: consultants whose agencies were all deleted make the AgencyService
    // bulk lookup respond with 404, raised by the generated client as a client error
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);
    Mockito.when(agencyService.getAgenciesWithoutCaching(Mockito.anyList()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    // when
    assertDoesNotThrow(
        () ->
            accountManager.findConsultantsByInfix(
                "infix", false, Lists.newArrayList(), 1, 10, "email", true));

    // then: the listing is still mapped, just without agency data
    Mockito.verify(userServiceMapper)
        .mapOf(
            Mockito.eq(page),
            Mockito.anyList(),
            Mockito.eq(List.of()),
            Mockito.anyList(),
            Mockito.anyMap(),
            Mockito.any());
  }

  @Test
  void findConsultantsByInfix_Should_OnlyResolveAgenciesOfNotDeletedRelations() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.eq("infix"), Mockito.isNull(), Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", false, Lists.newArrayList(), 1, 10, "email", true);

    // then: soft-deleted consultant-agency relations must not be resolved at all
    Mockito.verify(consultantAgencyRepository)
        .findByConsultantIdInAndDeleteDateIsNull(Mockito.<List<String>>any());
  }

  @Test
  void findConsultantsByInfix_Should_FilterByAgenciesIfAgencyListIsNotEmpty() {
    // given
    Mockito.when(
            consultantRepository.findAllByInfixAndAgencyIds(
                Mockito.eq("infix"),
                Mockito.anyCollection(),
                Mockito.isNull(),
                Mockito.any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", true, Lists.newArrayList(1L), 1, 10, "email", true);

    // then
    Mockito.verify(consultantRepository)
        .findAllByInfixAndAgencyIds(
            Mockito.eq("infix"),
            Mockito.eq(Lists.newArrayList(1L)),
            Mockito.isNull(),
            Mockito.any(PageRequest.class));
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-02
  // ---------------------------------------------------------------------------

  @Mock UserRepository userRepository;
  @Mock SessionRepository sessionRepository;
  @Mock UsernameTranscoder usernameTranscoder;
  @Mock AuthenticatedUser authenticatedUser;
  @Mock AppointmentService appointmentService;
  @Mock MessageClient messageClient;
  @Mock PatchConsultantSaga patchConsultantSaga;

  // findConsultant

  @Test
  void findConsultant_Should_ReturnEmpty_When_ConsultantNotFound() {
    Mockito.when(consultantRepository.findByIdAndDeleteDateIsNull("id-1"))
        .thenReturn(Optional.empty());

    assertThat(accountManager.findConsultant("id-1")).isEmpty();
  }

  @Test
  void findConsultant_Should_ReturnMappedConsultant_When_Found() {
    var consultant = new Consultant();
    consultant.setId("id-1");
    Mockito.when(consultantRepository.findByIdAndDeleteDateIsNull("id-1"))
        .thenReturn(Optional.of(consultant));
    Mockito.when(userServiceMapper.mapOf(Mockito.any(Consultant.class), Mockito.anyMap()))
        .thenReturn(Map.of("id", "id-1"));

    assertThat(accountManager.findConsultant("id-1")).isPresent();
  }

  // findConsultantByUsername

  @Test
  void findConsultantByUsername_Should_ReturnEmpty_When_NotFoundByEitherName() {
    Mockito.when(usernameTranscoder.transformedOf("user1")).thenReturn("user1_t");
    Mockito.when(consultantRepository.findByUsernameAndDeleteDateIsNull(Mockito.anyString()))
        .thenReturn(Optional.empty());

    assertThat(accountManager.findConsultantByUsername("user1")).isEmpty();
  }

  @Test
  void findConsultantByUsername_Should_ReturnConsultant_When_FoundByDirectUsername() {
    var consultant = new Consultant();
    consultant.setId("id-1");
    Mockito.when(usernameTranscoder.transformedOf("user1")).thenReturn("user1_t");
    Mockito.when(consultantRepository.findByUsernameAndDeleteDateIsNull("user1"))
        .thenReturn(Optional.of(consultant));
    Mockito.when(userServiceMapper.mapOf(Mockito.any(Consultant.class), Mockito.anyMap()))
        .thenReturn(Map.of("id", "id-1"));

    assertThat(accountManager.findConsultantByUsername("user1")).isPresent();
  }

  @Test
  void findConsultantByUsername_Should_ReturnConsultant_When_FoundByTransformedUsername() {
    var consultant = new Consultant();
    consultant.setId("id-2");
    Mockito.when(usernameTranscoder.transformedOf("user1")).thenReturn("user1_t");
    Mockito.when(consultantRepository.findByUsernameAndDeleteDateIsNull("user1"))
        .thenReturn(Optional.empty());
    Mockito.when(consultantRepository.findByUsernameAndDeleteDateIsNull("user1_t"))
        .thenReturn(Optional.of(consultant));
    Mockito.when(userServiceMapper.mapOf(Mockito.any(Consultant.class), Mockito.anyMap()))
        .thenReturn(Map.of("id", "id-2"));

    assertThat(accountManager.findConsultantByUsername("user1")).isPresent();
  }

  // isTeamAdvisedBy

  @Test
  void isTeamAdvisedBy_Should_ReturnFalse_When_NotTeamSession() {
    var session = new Session();
    session.setTeamSession(false);
    Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

    assertThat(accountManager.isTeamAdvisedBy(1L, "c-1")).isFalse();
  }

  @Test
  void isTeamAdvisedBy_Should_ReturnTrue_When_TeamSessionAndConsultantInAgency() {
    var session = new Session();
    session.setTeamSession(true);
    session.setAgencyId(10L);
    Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
    Mockito.when(
            consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                "c-1", 10L))
        .thenReturn(true);

    assertThat(accountManager.isTeamAdvisedBy(1L, "c-1")).isTrue();
  }

  @Test
  void isTeamAdvisedBy_Should_ReturnFalse_When_TeamSessionButConsultantNotInAgency() {
    var session = new Session();
    session.setTeamSession(true);
    session.setAgencyId(10L);
    Mockito.when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
    Mockito.when(
            consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                "c-1", 10L))
        .thenReturn(false);

    assertThat(accountManager.isTeamAdvisedBy(1L, "c-1")).isFalse();
  }

  // findAdviceSeeker

  @Test
  void findAdviceSeeker_Should_ReturnEmpty_When_UserNotFound() {
    Mockito.when(userRepository.findByUserIdAndDeleteDateIsNull("u-1"))
        .thenReturn(Optional.empty());

    assertThat(accountManager.findAdviceSeeker("u-1")).isEmpty();
  }

  @Test
  void findAdviceSeeker_Should_ReturnMappedUser_When_Found() {
    var user = new User("u-1", null, "username", "email@test.com", false);
    Mockito.when(userRepository.findByUserIdAndDeleteDateIsNull("u-1"))
        .thenReturn(Optional.of(user));
    Mockito.when(userServiceMapper.mapOf(user)).thenReturn(Map.of("userId", "u-1"));

    assertThat(accountManager.findAdviceSeeker("u-1")).isPresent();
  }

  // findAdviceSeekerByChatUserId

  @Test
  void findAdviceSeekerByChatUserId_Should_ReturnUser_When_Found() {
    var user = new User("u-1", null, "username", "email@test.com", false);
    Mockito.when(userRepository.findByRcUserIdAndDeleteDateIsNull("rc-1"))
        .thenReturn(Optional.of(user));

    assertThat(accountManager.findAdviceSeekerByChatUserId("rc-1")).contains(user);
  }

  @Test
  void findAdviceSeekerByChatUserId_Should_ReturnEmpty_When_NotFound() {
    Mockito.when(userRepository.findByRcUserIdAndDeleteDateIsNull("rc-999"))
        .thenReturn(Optional.empty());

    assertThat(accountManager.findAdviceSeekerByChatUserId("rc-999")).isEmpty();
  }

  // patchUser

  @Test
  void patchUser_Should_ReturnEmpty_When_NeitherUserNorConsultantFound() {
    Map<String, Object> patch = new HashMap<>();
    patch.put("id", "u-1");
    Mockito.when(userRepository.findByUserIdAndDeleteDateIsNull("u-1"))
        .thenReturn(Optional.empty());
    Mockito.when(consultantRepository.findByIdAndDeleteDateIsNull("u-1"))
        .thenReturn(Optional.empty());

    assertThat(accountManager.patchUser(patch)).isEmpty();
  }

  @Test
  void patchUser_Should_PatchAdviceSeeker_When_UserFound() {
    var user = new User("u-1", null, "username", "email@test.com", false);
    var patched = new User("u-1", null, "username", "new@test.com", false);
    Map<String, Object> patch = new HashMap<>();
    patch.put("id", "u-1");
    Mockito.when(userRepository.findByUserIdAndDeleteDateIsNull("u-1"))
        .thenReturn(Optional.of(user));
    Mockito.when(userServiceMapper.adviceSeekerOf(user, patch)).thenReturn(patched);
    Mockito.when(userRepository.save(patched)).thenReturn(patched);
    Mockito.when(userServiceMapper.mapOf(patched)).thenReturn(Map.of("userId", "u-1"));

    assertThat(accountManager.patchUser(patch)).isPresent();
    Mockito.verify(userRepository).save(patched);
  }

  @Test
  void patchUser_Should_PatchConsultant_When_ConsultantFound() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    var patched = new Consultant();
    Map<String, Object> patch = new HashMap<>();
    patch.put("id", "c-1");
    Mockito.when(userRepository.findByUserIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.empty());
    Mockito.when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    Mockito.when(userServiceMapper.consultantOf(consultant, patch)).thenReturn(patched);
    Mockito.when(patchConsultantSaga.executeTransactional(patched, patch))
        .thenReturn(Map.of("id", "c-1"));

    assertThat(accountManager.patchUser(patch)).isPresent();
    Mockito.verify(patchConsultantSaga).executeTransactional(patched, patch);
  }

  @Test
  void findConsultantsByInfix_Should_UseTenantIdFromAccessToken_When_ValidJwtProvided() {
    // payload = {"tenantId":5} → base64url = eyJ0ZW5hbnRJZCI6NX0
    Mockito.when(authenticatedUser.getAccessToken())
        .thenReturn("eyJhbGciOiJSUzI1NiJ9.eyJ0ZW5hbnRJZCI6NX0.signature");
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.any(), Mockito.any(), Mockito.any(PageRequest.class)))
        .thenReturn(page);

    accountManager.findConsultantsByInfix("test", false, List.of(), 0, 10, "id", true);

    Mockito.verify(consultantRepository)
        .findAllByInfix(Mockito.eq("test"), Mockito.eq(5L), Mockito.any(PageRequest.class));
  }

  @Test
  void findConsultantsByInfix_Should_MapTenantName_When_ConsultantHasTenantId() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setTenantId(5L);
    var tenantDto = new RestrictedTenantDTO();
    tenantDto.setName("Caritas");
    var consultantBase = Mockito.mock(Consultant.ConsultantBase.class);
    Mockito.when(consultantBase.getId()).thenReturn("c-1");

    Mockito.when(authenticatedUser.getAccessToken()).thenReturn(null);
    Mockito.when(
            consultantRepository.findAllByInfix(
                Mockito.any(), Mockito.any(), Mockito.any(PageRequest.class)))
        .thenReturn(page);
    Mockito.when(page.stream()).thenReturn(java.util.stream.Stream.of(consultantBase));
    Mockito.when(consultantRepository.findAllByIdIn(List.of("c-1")))
        .thenReturn(List.of(consultant));
    Mockito.when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(List.of("c-1")))
        .thenReturn(List.of());
    Mockito.when(userServiceMapper.agencyIdsOf(List.of())).thenReturn(List.of());
    Mockito.when(adminRepository.findExistingIdsByIdIn(List.of("c-1")))
        .thenReturn(java.util.Collections.emptySet());
    Mockito.when(tenantService.getRestrictedTenantData(5L)).thenReturn(tenantDto);
    Mockito.when(
            userServiceMapper.mapOf(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(Map.of("total", 1));

    var result = accountManager.findConsultantsByInfix("test", false, List.of(), 0, 10, "id", true);

    assertThat(result).containsKey("total");
    Mockito.verify(tenantService).getRestrictedTenantData(5L);
  }
}
