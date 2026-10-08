package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Licensing;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@TestPropertySource(properties = "spring.profiles.active=testing,verified-request-caller")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "multitenancy.enabled=true")
@Transactional
public class CreateConsultantSagaTenantAwareIT extends AccountInactivityPolicyHttpFixture {
  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
    givenBoundedAccounts();
    org.mockito.Mockito.when(
            tenantService.getRestrictedTenantDataFresh(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
  }

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenantService;

  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate boundedIdentityHttp;

  @MockitoBean private TaskIdentityGrant taskIdentityGrant;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment identityEnvironment;
  @Autowired private ObjectMapper identityMapper;
  private BoundedIdentityHttpFixtures.Provider nativeAccounts;

  private void givenBoundedAccounts() {
    nativeAccounts =
        BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskIdentityGrant,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            id -> {});
    givenHuman("7ad454de-cf29-4557-b8b3-1bf986524de2", 1L, List.of("user-admin", "tenant-admin"));
  }

  private void givenHuman(String id, Long tenant, List<String> roles) {
    var token =
        Jwt.withTokenValue("verified-creator-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "admin")
            .claim("preferred_username", "apau1")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new JwtAuthenticationToken(
            token,
            List.of(
                    "AUTHORIZATION_USER_ADMIN",
                    "AUTHORIZATION_TENANT_ADMIN",
                    "AUTHORIZATION_CONSULTANT_CREATE")
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @org.junit.jupiter.api.AfterEach
  void clearBoundedCaller() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAILADDRESS = "valid@emailaddress.de";
  private static final long TENANT_ID = 1;

  @Autowired private CreateConsultantSaga createConsultantSaga;

  @Autowired private ConsultantRepository consultantRepository;

  @MockitoBean private TenantAdminService tenantAdminService;

  private final EasyRandom easyRandom = new EasyRandom();

  @AfterEach
  public void tearDown() {
    TenantContext.clear();
  }

  @Test
  public void
      createNewConsultant_Should_throwCustomValidationHttpStatusException_When_LicensesAreExceeded() {
    TenantContext.setCurrentTenant(1L);
    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> {
          // given
          givenTenantApiCall();
          createConsultant("username1");
          createConsultant("username2");
          CreateConsultantDTO createConsultantDTO =
              this.easyRandom.nextObject(CreateConsultantDTO.class);
          createConsultantDTO.setTenantId(1L);
          this.createConsultantSaga.createNewConsultant(createConsultantDTO);
          rollbackDBState();
        });
  }

  @Test
  public void
      createNewConsultant_Should_countLicensesPerTenant_When_consultantsExistInOtherTenants() {
    // given: a tenant admin acts inside tenant 1, while two consultants already exist in a
    // different tenant. Tenant 1 allows 2 consultants and currently has none, so creation must
    // succeed even though the global consultant count already reaches the limit.
    TenantContext.setCurrentTenant(1L);
    createConsultantForTenant("otherTenantUser1", 2L);
    createConsultantForTenant("otherTenantUser2", 2L);

    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(2))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setAgencyIds(List.of());
    createConsultantDTO.setTenantId(1L);

    // when
    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    assertThat(consultant.getEmbedded(), notNullValue());
    assertThat(consultant.getEmbedded().getId(), notNullValue());
    assertDefaultInactivityPolicy(consultant.getEmbedded().getId());
    rollbackDBState();
  }

  @Test
  public void createNewConsultant_Should_succeed_When_tenantHasNoConfiguredUserLimit() {
    // Every tenant created through the invite flow has licensing_allowed_users = NULL — measured on
    // Pre-Dev: only the seed tenant carries a limit. `allowedNumberOfUsers` was unboxed straight
    // into a comparison, so creating the first consultant for such a tenant died with a
    // NullPointerException and the admin saw a bare 500. No limit configured means no limit.
    TenantContext.setCurrentTenant(1L);
    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(null))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setAgencyIds(List.of());
    createConsultantDTO.setTenantId(1L);

    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    assertThat(consultant.getEmbedded(), notNullValue());
    assertThat(consultant.getEmbedded().getId(), notNullValue());
    rollbackDBState();
  }

  @Test
  public void createNewConsultant_Should_succeed_When_tenantHasNoLicensingBlockAtAll() {
    // The previous guard was `assert nonNull(...)`, which Java disables at runtime unless -ea is
    // passed — so it never protected anything in production.
    TenantContext.setCurrentTenant(1L);
    var tenant =
        new TenantDTO()
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setAgencyIds(List.of());
    createConsultantDTO.setTenantId(1L);

    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    assertThat(consultant.getEmbedded(), notNullValue());
    rollbackDBState();
  }

  @Test
  public void
      createNewConsultant_Should_addConsultantAndGroupChatConsultantRole_When_isGroupChatConsultantFlagIsEnabled() {
    // given
    TenantContext.setCurrentTenant(1L);
    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(1))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(
        VALID_USERNAME + java.util.UUID.randomUUID().toString().substring(0, 8));
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setPublicSlug(null);
    createConsultantDTO.setIsGroupchatConsultant(true);
    createConsultantDTO.setAgencyIds(List.of());
    createConsultantDTO.setTenantId(1L);

    // when
    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    org.assertj.core.api.Assertions.assertThat(
            nativeAccounts.projections().get(consultant.getEmbedded().getId()).roles())
        .containsExactlyInAnyOrder("consultant", "group-chat-consultant");

    assertThat(consultant.getEmbedded(), notNullValue());
    assertThat(consultant.getEmbedded().getId(), notNullValue());
  }

  private void createConsultant(String username) {
    createConsultantForTenant(username, 1L);
  }

  private void createConsultantForTenant(String username, Long tenantId) {
    Consultant consultant = new Consultant();
    consultant.setAppointments(null);
    consultant.setTenantId(tenantId);
    consultant.setId(username);
    consultant.setMatrixUserId(username);
    consultant.setUsername(username);
    consultant.setFirstName(username);
    consultant.setLastName(username);
    consultant.setEmail(username + "@email.com");
    consultant.setEncourage2fa(true);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setLanguageCode(LanguageCode.de);

    consultantRepository.save(consultant);
  }

  private void rollbackDBState() {
    Iterable<Consultant> all = consultantRepository.findAll();
    for (Consultant c : all) {
      c.setDeleteDate(null);
    }
    consultantRepository.saveAll(all);
    TenantContext.clear();
  }

  private void givenTenantApiCall() {
    var currentTenant = new TenantData(1L, "testdomain");
    TenantContext.setCurrentTenantData(currentTenant);
    var dummyTenant = new TenantDTO();
    var licensing = new Licensing();
    licensing.setAllowedNumberOfUsers(2);
    dummyTenant.setLicensing(licensing);
    ReflectionTestUtils.setField(createConsultantSaga, "tenantAdminService", tenantAdminService);
    when(tenantAdminService.getTenantById(TenantContext.getCurrentTenant()))
        .thenReturn(dummyTenant);
  }
}
