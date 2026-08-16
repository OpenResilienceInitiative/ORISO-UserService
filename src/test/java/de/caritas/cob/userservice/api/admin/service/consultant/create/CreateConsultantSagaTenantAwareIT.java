package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.CONSULTANT;
import static de.caritas.cob.userservice.api.config.auth.UserRole.GROUP_CHAT_CONSULTANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.Licensing;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "multitenancy.enabled=true")
@Transactional
public class CreateConsultantSagaTenantAwareIT {

  private static final String VALID_USERNAME = "validUsername";
  private static final String VALID_EMAILADDRESS = "valid@emailaddress.de";
  private static final long TENANT_ID = 1;

  @Autowired private CreateConsultantSaga createConsultantSaga;

  @Autowired private ConsultantRepository consultantRepository;

  @MockitoBean private TenantAdminService tenantAdminService;

  @MockitoBean private KeycloakService keycloakService;

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

    when(keycloakService.createUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(CreatedIdentity.class));
    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(2))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);
    createConsultantDTO.setTenantId(1L);

    // when
    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    assertThat(consultant.getEmbedded(), notNullValue());
    assertThat(consultant.getEmbedded().getId(), notNullValue());
    rollbackDBState();
  }

  @Test
  public void createNewConsultant_Should_succeed_When_tenantHasNoConfiguredUserLimit() {
    // Every tenant created through the invite flow has licensing_allowed_users = NULL — measured on
    // Pre-Dev: only the seed tenant carries a limit. `allowedNumberOfUsers` was unboxed straight
    // into a comparison, so creating the first consultant for such a tenant died with a
    // NullPointerException and the admin saw a bare 500. No limit configured means no limit.
    TenantContext.setCurrentTenant(1L);
    when(keycloakService.createUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(CreatedIdentity.class));
    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(null))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);
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
    when(keycloakService.createUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(CreatedIdentity.class));
    var tenant =
        new TenantDTO()
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(false);
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
    when(keycloakService.createUser(any(), anyString(), any()))
        .thenReturn(easyRandom.nextObject(CreatedIdentity.class));
    var tenant =
        new TenantDTO()
            .licensing(new Licensing().allowedNumberOfUsers(1))
            .settings(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.Settings()
                    .featureGroupChatV2Enabled(false));
    when(tenantAdminService.getTenantById(Mockito.anyLong())).thenReturn(tenant);

    CreateConsultantDTO createConsultantDTO = this.easyRandom.nextObject(CreateConsultantDTO.class);
    createConsultantDTO.setTenantId(TENANT_ID);
    createConsultantDTO.setUsername(VALID_USERNAME);
    createConsultantDTO.setEmail(VALID_EMAILADDRESS);
    createConsultantDTO.setIsGroupchatConsultant(true);
    createConsultantDTO.setTenantId(1L);

    // when
    ConsultantAdminResponseDTO consultant =
        createConsultantSaga.createNewConsultant(createConsultantDTO);

    // then
    verify(keycloakService)
        .assignRoles(
            anyString(), eq(Set.of(CONSULTANT.getValue(), GROUP_CHAT_CONSULTANT.getValue())));

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
